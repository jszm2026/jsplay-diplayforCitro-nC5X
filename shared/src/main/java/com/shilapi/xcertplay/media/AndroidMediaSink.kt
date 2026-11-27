package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Debug
import java.util.Arrays
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Surface
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.airplay.toHexString
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

/** Owns one focus request for all eligible tracks in a CarPlay sink. */
internal class AudioFocusCoordinator(
    context: Context?,
    private val enabled: Boolean,
    private val report: (String) -> Unit = {},
) {
    private data class Entry(val channel: AudioChannel, val attributes: AudioAttributes)

    private val manager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val active = LinkedHashMap<AudioTrack, Entry>()
    private var request: AudioFocusRequest? = null
    private var requestedChannel: AudioChannel? = null
    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        synchronized(this) {
            runCatching { report("Audio: focus change=$change activeTracks=${active.size}") }
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> setVolume(DUCKED_VOLUME)
                AudioManager.AUDIOFOCUS_GAIN -> setVolume(FULL_VOLUME)
                // Keep CarPlay audio running on permanent or transient loss. Some head units
                // do not send a later gain callback after taking focus back.
            }
        }
    }

    @Synchronized
    fun acquire(track: AudioTrack, channel: AudioChannel, attributes: AudioAttributes) {
        if (!enabled || manager == null || channel == AudioChannel.NAVIGATION) return
        active[track] = Entry(channel, attributes)
        refreshRequest()
    }

    @Synchronized
    fun release(track: AudioTrack) {
        if (active.remove(track) != null) refreshRequest()
    }

    private fun refreshRequest() {
        val primary = active.values.maxByOrNull { it.channel.focusPriority() }
        if (primary == null) {
            request?.let { manager?.abandonAudioFocusRequest(it) }
            request = null
            requestedChannel = null
            return
        }
        if (request != null && requestedChannel == primary.channel) return
        request?.let { manager?.abandonAudioFocusRequest(it) }
        val gain = when (primary.channel) {
            AudioChannel.MEDIA -> AudioManager.AUDIOFOCUS_GAIN
            AudioChannel.PHONE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioChannel.ASSISTANT -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            AudioChannel.NAVIGATION -> return
        }
        val next = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(primary.attributes)
            .setOnAudioFocusChangeListener(listener, Handler(Looper.getMainLooper()))
            .build()
        request = next
        requestedChannel = primary.channel
        val result = manager?.requestAudioFocus(next)
        val line = "Audio: focus requested channel=${primary.channel} gain=$gain granted=$result activeTracks=${active.size}"
        Log.i(TAG, line)
        runCatching { report(line) }
    }

    private fun setVolume(volume: Float) {
        active.keys.forEach { track -> runCatching { track.setStereoVolume(volume, volume) } }
    }

    private fun AudioChannel.focusPriority(): Int = when (this) {
        AudioChannel.MEDIA -> 3
        AudioChannel.PHONE -> 2
        AudioChannel.ASSISTANT -> 1
        AudioChannel.NAVIGATION -> 0
    }

    private companion object {
        const val TAG = "DiPlay-AudioFocus"
        const val FULL_VOLUME = 1f
        const val DUCKED_VOLUME = 0.2f
    }
}

/**
 * Android rendering backend for the CarPlay media engine. Video frames are
 * decoded with MediaCodec onto a Surface; audio streams are decoded to PCM and
 * played through AudioTrack. Each audio stream keeps its own track and usage so
 * media and navigation guidance stay independently routable. Call [close]
 * when the session tears down.
 */
class AndroidMediaSink(
    surface: Surface? = null,
    private val videoWidth: Int = 1280,
    private val videoHeight: Int = 720,
    private val preferSoftwareHevcDecoder: Boolean = false,
    private val advancedAudioChannelMapping: Boolean = false,
    private val audioFocusEnabled: Boolean = false,
    private val mediaChannel: Int = 0,
    private val navigationChannel: Int = 0,
    context: Context? = null,
    private val navigationStreamType: Int = AudioChannelMapper.DEFAULT_NAVIGATION_STREAM_TYPE,
    onScreenStreamActiveChanged: ((Int, Boolean) -> Unit)? = null,
    private val mediaBufferMillis: Int = MediaAudioBuffer.DEFAULT_MILLIS,
    private val videoFps: Int = 60,
    private val onAudioDiagnostic: (String) -> Unit = {},
    /** True while any music ("media") audio stream is running; called from media threads. */
    private val onMediaAudioChanged: (Boolean) -> Unit = {},
) : MediaSink {
    private val appContext = context?.applicationContext
    private val audioManager = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val audioFocusCoordinator = AudioFocusCoordinator(
        appContext,
        audioFocusEnabled,
        onAudioDiagnostic,
    )
    private val screenStateLock = Any()
    private val activeScreenTypes = mutableSetOf<Int>()
    private val defaultSurface = surface
    @Volatile private var screenStreamActiveChanged = onScreenStreamActiveChanged
    private val surfaces = ConcurrentHashMap<Int, Surface>()
    private val videoDecoders = ConcurrentHashMap<Int, VideoDecoder>()
    private val mediaAudioTypes = mutableSetOf<AudioStreamId>()
    private val audioRenderers = ConcurrentHashMap<AudioStreamId, AudioRenderer>()
    private val microphoneUplinks = ConcurrentHashMap<AudioStreamId, MicrophoneUplink>()
    private val audioModeLock = Any()
    private var communicationModeStream: AudioStreamId? = null
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private val pendingVideoCodec = ConcurrentHashMap<Int, VideoCodec>()
    private val surfaceCreatedCounts = ConcurrentHashMap<Int, AtomicLong>()
    private val surfaceDestroyedCounts = ConcurrentHashMap<Int, AtomicLong>()
    private val videoRecoveryHandlers = ConcurrentHashMap<Int, () -> Unit>()
    private val videoDiagnosticHandlers = ConcurrentHashMap<Int, (String) -> Unit>()
    private val recoveryPending = AtomicBoolean(false)
    // Extra decoders draw the same stream on other surfaces, such as the centre card.
    private val mirrorLock = Any()
    private val mirrorSurfaces = HashMap<Pair<Int, String>, Surface>()
    private val mirrorDecoders = HashMap<Pair<Int, String>, VideoDecoder>()
    private val lastVideoConfig = ConcurrentHashMap<Int, Pair<VideoCodec, ByteArray>>()
    private val recoveryExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "carplay-video-recovery").apply { isDaemon = true }
    }

    override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {
        videoRecoveryHandlers[type] = handler
    }

    override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {
        videoDiagnosticHandlers[type] = handler
    }

    private fun requestVideoRecovery(type: Int) {
        if (!recoveryPending.compareAndSet(false, true)) return
        try {
            recoveryExecutor.execute {
                try { videoRecoveryHandlers[type]?.invoke() }
                catch (error: Exception) { Log.w("xcertplay-usb", "Video keyframe request failed", error) }
                finally { recoveryPending.set(false) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { recoveryPending.set(false) }
    }

    fun setSurface(type: Int, surface: Surface) {
        surfaces[type] = surface
        videoDecoders[type]?.setSurface(surface)
    }

    fun surfaceCreated(type: Int) {
        val count = surfaceCreatedCounts.computeIfAbsent(type) { AtomicLong() }.incrementAndGet()
        videoDecoders[type]?.recordSurfaceCreated(count)
    }

    fun surfaceDestroyed(type: Int) {
        val count = surfaceDestroyedCounts.computeIfAbsent(type) { AtomicLong() }.incrementAndGet()
        videoDecoders[type]?.recordSurfaceDestroyed(count)
    }

    fun clearSurface(type: Int, surface: Surface) {
        if (surfaces.remove(type, surface)) videoDecoders[type]?.setSurface(null)
    }

    /**
     * Also decodes stream [type] onto [surface] with its own decoder, one per [key], which
     * starts at the next keyframe it asks for; null stops it. The stream's own surface is not affected.
     */
    fun setMirrorSurface(type: Int, key: String, surface: Surface?) {
        val id = type to key
        synchronized(mirrorLock) {
            mirrorDecoders.remove(id)?.close()
            if (surface == null) {
                mirrorSurfaces.remove(id)
                return
            }
            mirrorSurfaces[id] = surface
        }
        lastVideoConfig[type]?.let { (codec, data) -> mirrorDecoders(type).forEach { it.configure(codec, data) } }
    }

    private fun mirrorDecoders(type: Int): List<VideoDecoder> = synchronized(mirrorLock) {
        if (mirrorSurfaces.isEmpty()) return emptyList()
        mirrorSurfaces.filterKeys { it.first == type }.map { (id, surface) ->
            mirrorDecoders.getOrPut(id) { newVideoDecoder(type, surface, " stream=$type mirror=${id.second}") }
        }
    }

    fun setScreenStreamActiveChangedListener(listener: ((Int, Boolean) -> Unit)?) {
        synchronized(screenStateLock) {
            screenStreamActiveChanged = listener
            activeScreenTypes.forEach { listener?.invoke(it, true) }
        }
    }

    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        pendingVideoCodec[type] = codec
    }

    override fun onVideoConfig(type: Int, codecData: ByteArray) {
        val codec = pendingVideoCodec[type] ?: VideoCodec.H264
        lastVideoConfig[type] = codec to codecData
        videoDecoder(type).configure(codec, codecData)
        mirrorDecoders(type).forEach { it.configure(codec, codecData) }
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
        videoDecoder(type).submit(naluBytes)
        mirrorDecoders(type).forEach { it.submit(naluBytes) }
    }

    override fun onVideoFrameDropped(type: Int) {
        videoDecoder(type).signalDiscontinuity()
        mirrorDecoders(type).forEach { it.signalDiscontinuity() }
    }

    override fun onScreenStreamActive(type: Int, active: Boolean) {
        if (!active) {
            videoRecoveryHandlers.remove(type)
            videoDiagnosticHandlers.remove(type)
            videoDecoders.remove(type)?.close()
            synchronized(mirrorLock) {
                mirrorDecoders.keys.filter { it.first == type }.forEach { mirrorDecoders.remove(it)?.close() }
            }
            lastVideoConfig.remove(type)
            pendingVideoCodec.remove(type)
        }
        synchronized(screenStateLock) {
            if (active) activeScreenTypes.add(type) else activeScreenTypes.remove(type)
            screenStreamActiveChanged?.invoke(type, active)
        }
    }

    override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {
        audioRenderer(id, format).start()
        if (format.audioType == "media") updateMediaAudio(id, true)
    }

    override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
        audioRenderer(id, format).submit(rtp, sample)
    }

    override fun onAudioStopped(id: AudioStreamId) {
        audioRenderers.remove(id)?.close()
        updateMediaAudio(id, false)
    }

    private fun updateMediaAudio(id: AudioStreamId, active: Boolean) {
        val (before, after) = synchronized(mediaAudioTypes) {
            val before = mediaAudioTypes.isNotEmpty()
            if (active) mediaAudioTypes.add(id) else mediaAudioTypes.remove(id)
            before to mediaAudioTypes.isNotEmpty()
        }
        if (before != after) onMediaAudioChanged(after)
    }

    override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {
        // This callback runs on the downlink thread; microphone failures must not stop playback.
        try {
            if (config.audioType == "telephony") enterCommunicationMode(id)
            val uplink = microphoneUplinks.computeIfAbsent(id) { MicrophoneUplink(config, onAudioDiagnostic) }
            if (!uplink.start()) {
                microphoneUplinks.remove(id, uplink)
                restoreAudioMode(id)
            }
        } catch (error: Exception) {
            Log.e("xcertplay-usb", "microphone start failed stream=$id", error)
            MicrophoneCaptureStats.reportStartFailure(config, error, onAudioDiagnostic)
            onMicrophoneStopped(id)
        }
    }

    override fun onMicrophoneStopped(id: AudioStreamId) {
        try {
            microphoneUplinks.remove(id)?.close()
        } finally {
            restoreAudioMode(id)
        }
    }

    private fun enterCommunicationMode(id: AudioStreamId) {
        val manager = audioManager ?: return
        synchronized(audioModeLock) {
            if (communicationModeStream != null) return
            // Select the HAL communication path before AudioRecord is created.
            savedAudioMode = manager.mode
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            communicationModeStream = id
            Log.i("xcertplay-usb", "audio mode $savedAudioMode -> ${manager.mode} for telephony stream=$id")
        }
    }

    private fun restoreAudioMode(id: AudioStreamId?) {
        val manager = audioManager ?: return
        synchronized(audioModeLock) {
            val active = communicationModeStream ?: return
            if (id != null && id != active) return
            communicationModeStream = null
            try {
                manager.mode = savedAudioMode
                Log.i("xcertplay-usb", "audio mode restored to ${manager.mode}")
            } catch (error: RuntimeException) {
                Log.w("xcertplay-usb", "could not restore audio mode $savedAudioMode", error)
            }
        }
    }

    fun close() {
        synchronized(screenStateLock) {
            activeScreenTypes.forEach { screenStreamActiveChanged?.invoke(it, false) }
            activeScreenTypes.clear()
            screenStreamActiveChanged = null
        }
        videoDecoders.values.forEach(VideoDecoder::close)
        videoDecoders.clear()
        synchronized(mirrorLock) {
            mirrorDecoders.values.forEach(VideoDecoder::close)
            mirrorDecoders.clear()
            mirrorSurfaces.clear()
        }
        videoRecoveryHandlers.clear()
        videoDiagnosticHandlers.clear()
        recoveryExecutor.shutdownNow()
        audioRenderers.values.forEach(AudioRenderer::close)
        audioRenderers.clear()
        val hadMedia = synchronized(mediaAudioTypes) { mediaAudioTypes.isNotEmpty().also { mediaAudioTypes.clear() } }
        if (hadMedia) onMediaAudioChanged(false)
        try {
            microphoneUplinks.values.forEach(MicrophoneUplink::close)
        } finally {
            microphoneUplinks.clear()
            restoreAudioMode(null)
        }
    }

    private fun videoDecoder(type: Int): VideoDecoder =
        videoDecoders.computeIfAbsent(type) { newVideoDecoder(type, surfaces[type] ?: defaultSurface) }

    private fun newVideoDecoder(type: Int, surface: Surface?, statsLabel: String? = null) = VideoDecoder(
        type,
        surface,
        videoWidth,
        videoHeight,
        preferSoftwareHevcDecoder,
        requestKeyFrame = { requestVideoRecovery(type) },
        report = { videoDiagnosticHandlers[type]?.invoke(it) },
        statsLabel = statsLabel,
        targetFps = videoFps,
        initialSurfaceCreatedCount = surfaceCreatedCounts[type]?.get() ?: 0L,
        initialSurfaceDestroyedCount = surfaceDestroyedCounts[type]?.get() ?: 0L,
    )

    @Synchronized
    private fun audioRenderer(id: AudioStreamId, format: AudioFormat): AudioRenderer {
        val existing = audioRenderers[id]
        if (existing?.format == format) return existing
        existing?.close()
        return AudioRenderer(
            format,
            advancedAudioChannelMapping,
            audioFocusEnabled,
            mediaChannel,
            navigationChannel,
            audioFocusCoordinator,
            navigationStreamType,
            mediaBufferMillis,
            onAudioDiagnostic,
        ).also { audioRenderers[id] = it }
    }
}

/** Serial MediaCodec video decoder: one worker owns configure and frame feeding. */
private enum class DecoderPerfMetric {
    INPUT_DEQUEUE_WALL_NS, INPUT_DEQUEUE_CPU_NS,
    INPUT_ACQUIRE_WALL_NS, INPUT_ACQUIRE_RETRIES,
    INPUT_QUEUE_WALL_NS, INPUT_QUEUE_CPU_NS,
    OUTPUT_DEQUEUE_WALL_NS, OUTPUT_DEQUEUE_CPU_NS,
    OUTPUT_RELEASE_WALL_NS, OUTPUT_RELEASE_CPU_NS,
    DRAIN_TOTAL_WALL_NS, DRAIN_TOTAL_CPU_NS, DRAIN_OUTPUT_COUNT,
    OUTPUT_PTS_AGE_US, RECEIVED_TO_OUTPUT_NS, STALE_OUTPUT_DROPS,
    RESYNC_WAITING_DROPS,
    RECOVERY_IDR_WAIT_NS, RECOVERY_REQUEST_COUNT,
}

private data class SurfacePerfStats(
    val codecConfiguredSurface: Boolean,
    val surfaceCreatedCount: Long,
    val surfaceDestroyedCount: Long,
    val codecReconfigureCount: Long,
)

private data class DecoderRateStats(
    val inputReceivedFps: Double,
    val inputSubmitFps: Double,
    val codecOutputFps: Double,
    val renderReleaseFps: Double,
)

private class DecoderPerfWindow(private val label: String) {
    private val samples = Array(DecoderPerfMetric.values().size) { LongArray(SAMPLE_CAPACITY) }
    private val counts = IntArray(DecoderPerfMetric.values().size)
    private val cursors = IntArray(DecoderPerfMetric.values().size)
    private var windowStartNs = System.nanoTime()
    private var staleDrops = 0L

    @Synchronized fun record(metric: DecoderPerfMetric, value: Long) {
        val index = metric.ordinal
        if (counts[index] < SAMPLE_CAPACITY) {
            samples[index][counts[index]++] = value
        } else {
            samples[index][cursors[index]] = value
            cursors[index] = (cursors[index] + 1) % SAMPLE_CAPACITY
        }
    }

    @Synchronized fun recordStage(
        wallMetric: DecoderPerfMetric,
        cpuMetric: DecoderPerfMetric,
        wallStartNs: Long,
        cpuStartNs: Long,
    ) {
        val wallEndNs = System.nanoTime()
        record(wallMetric, wallEndNs - wallStartNs)
        val cpuEndNs = Debug.threadCpuTimeNanos()
        if (cpuStartNs >= 0 && cpuEndNs >= cpuStartNs) record(cpuMetric, cpuEndNs - cpuStartNs)
    }

    @Synchronized fun staleOutputDropped() { staleDrops++ }

    @Synchronized fun logIfDue(
        queueStats: () -> VideoQueueStats,
        rates: () -> DecoderRateStats,
        surfaceStats: () -> SurfacePerfStats,
    ): String? {
        val nowNs = System.nanoTime()
        if (nowNs - windowStartNs < WINDOW_NS) return null
        val queue = queueStats()
        val rateStats = rates()
        val surface = surfaceStats()
        val inputAcquireWallMs = averageMillis(DecoderPerfMetric.INPUT_ACQUIRE_WALL_NS)
        val inputAcquireRetries = sum(DecoderPerfMetric.INPUT_ACQUIRE_RETRIES)
        val inputQueueWallMs = averageMillis(DecoderPerfMetric.INPUT_QUEUE_WALL_NS)
        val outputDrainWallMs = averageMillis(DecoderPerfMetric.DRAIN_TOTAL_WALL_NS)
        val outputDrainCount = sum(DecoderPerfMetric.DRAIN_OUTPUT_COUNT)
        val outputFrameAgeMs = averageMillisFromUs(DecoderPerfMetric.OUTPUT_PTS_AGE_US)
        val recoveryCount = sum(DecoderPerfMetric.RECOVERY_REQUEST_COUNT)
        val recoveryWaitIdrMs = averageMillis(DecoderPerfMetric.RECOVERY_IDR_WAIT_NS)
        val line = buildString {
            append("decoderPerf stream=").append(label)
            append(" queueFrames=").append(queue.frames)
            append(" queueBytes=").append(queue.bytes)
            append(" queueOldestAgeMs=").append(queue.oldestAgeMs)
            append(" queueHighWater=").append(queue.highWater)
            append(" queueDroppedFrames=").append(queue.droppedFrames)
            append(" queueSafetyTrimDrops=").append(queue.droppedFrames)
            append(" queueOverflowEvents=").append(queue.overflowEvents)
            append(" resyncWaitingDrops=").append(sum(DecoderPerfMetric.RESYNC_WAITING_DROPS))
            append(" inputFps=").append("%.1f".format(java.util.Locale.US, rateStats.inputSubmitFps))
            append(" outputFps=").append("%.1f".format(java.util.Locale.US, rateStats.renderReleaseFps))
            append(" inputReceivedFps=").append("%.1f".format(java.util.Locale.US, rateStats.inputReceivedFps))
            append(" inputSubmitFps=").append("%.1f".format(java.util.Locale.US, rateStats.inputSubmitFps))
            append(" codecOutputFps=").append("%.1f".format(java.util.Locale.US, rateStats.codecOutputFps))
            append(" renderReleaseFps=").append("%.1f".format(java.util.Locale.US, rateStats.renderReleaseFps))
            append(" codecConfiguredSurface=").append(surface.codecConfiguredSurface)
            append(" surfaceCreatedCount=").append(surface.surfaceCreatedCount)
            append(" surfaceDestroyedCount=").append(surface.surfaceDestroyedCount)
            append(" codecReconfigureCount=").append(surface.codecReconfigureCount)
            for (metric in DecoderPerfMetric.values()) {
                val index = metric.ordinal
                val count = counts[index]
                if (count == 0) continue
                val sorted = samples[index].copyOf(count)
                Arrays.sort(sorted)
                val divisor = if (metric.name.endsWith("_NS")) 1_000L else 1L
                val unit = if (metric.name.endsWith("_NS") || metric.name.endsWith("_US")) "us" else "count"
                append(' ').append(metric.name.lowercase()).append("Avg=").append(sorted.average().toLong() / divisor).append(unit)
                append(' ').append(metric.name.lowercase()).append("P95=").append(percentile(sorted, 0.95) / divisor).append(unit)
                append(' ').append(metric.name.lowercase()).append("P99=").append(percentile(sorted, 0.99) / divisor).append(unit)
                append(' ').append(metric.name.lowercase()).append("Max=").append(sorted.last() / divisor).append(unit)
                counts[index] = 0
                cursors[index] = 0
            }
            append(" inputAcquireWallMs=").append(inputAcquireWallMs)
            append(" inputAcquireRetries=").append(inputAcquireRetries)
            append(" inputQueueWallMs=").append(inputQueueWallMs)
            append(" outputDrainWallMs=").append(outputDrainWallMs)
            append(" outputDrainCount=").append(outputDrainCount)
            append(" outputFrameAgeMs=").append(outputFrameAgeMs)
            append(" dequeueInputP95Ms=").append(percentileMillis(DecoderPerfMetric.INPUT_DEQUEUE_WALL_NS, 0.95, 1_000_000L))
            append(" codecResidenceP95Ms=").append(percentileMillis(DecoderPerfMetric.OUTPUT_PTS_AGE_US, 0.95, 1_000L))
            append(" receivedToOutputP95Ms=").append(percentileMillis(DecoderPerfMetric.RECEIVED_TO_OUTPUT_NS, 0.95, 1_000_000L))
            append(" recoveryCount=").append(recoveryCount)
            append(" recoveryWaitIdrMs=").append(recoveryWaitIdrMs)
            append(" staleOutputDrops=").append(staleDrops)
        }
        staleDrops = 0
        windowStartNs = nowNs
        return line
    }

    private fun averageMillis(metric: DecoderPerfMetric): Long {
        val count = counts[metric.ordinal]
        if (count == 0) return 0L
        return samples[metric.ordinal].copyOf(count).average().toLong() / 1_000_000L
    }

    private fun averageMillisFromUs(metric: DecoderPerfMetric): Long {
        val count = counts[metric.ordinal]
        if (count == 0) return 0L
        return samples[metric.ordinal].copyOf(count).average().toLong() / 1_000L
    }

    private fun percentileMillis(metric: DecoderPerfMetric, fraction: Double, divisor: Long): Long {
        val count = counts[metric.ordinal]
        if (count == 0) return 0L
        val sorted = samples[metric.ordinal].copyOf(count)
        Arrays.sort(sorted)
        return percentile(sorted, fraction) / divisor
    }

    private fun sum(metric: DecoderPerfMetric): Long {
        val count = counts[metric.ordinal]
        var total = 0L
        for (index in 0 until count) total += samples[metric.ordinal][index]
        return total
    }

    private fun percentile(sorted: LongArray, fraction: Double): Long {
        val rank = kotlin.math.ceil(sorted.size * fraction).toInt().coerceAtLeast(1)
        return sorted[rank - 1]
    }

    private companion object {
        const val SAMPLE_CAPACITY = 256
        const val WINDOW_NS = 5_000_000_000L
    }
}

private enum class DecoderRecoveryState { NORMAL, RECOVERY_REQUESTED, WAITING_FOR_IDR }
private class VideoDecoder(
    streamType: Int,
    surface: Surface?,
    private val width: Int,
    private val height: Int,
    private val preferSoftwareHevcDecoder: Boolean,
    private val requestKeyFrame: () -> Unit,
    private val report: (String) -> Unit,
    statsLabel: String? = null,
    private val targetFps: Int = 60,
    initialSurfaceCreatedCount: Long = 0L,
    initialSurfaceDestroyedCount: Long = 0L,
) : Closeable {
    private val queue = VideoDecodeQueue()
    private val perf = DecoderPerfWindow(statsLabel ?: if (streamType == 110) "main" else "stream=$streamType")
    private var lastIdleDrainNs = 0L
    private var rateWindowStartNs = System.nanoTime()
    private val inputReceivedFrameCount = AtomicLong()
    private var inputFrameCount = 0L
    private var codecOutputFrameCount = 0L
    private var renderReleaseFrameCount = 0L
    private val submittedReceiveTimes = ArrayDeque<Long>()
    @Volatile private var configuredCodec: VideoCodec? = null
    @Volatile private var surfaceCreatedCount = initialSurfaceCreatedCount
    @Volatile private var surfaceDestroyedCount = initialSurfaceDestroyedCount
    private var codecReconfigureCount = 0L
    private var recoveryState = DecoderRecoveryState.NORMAL
    private var recoveryStartedNs = 0L
    private var recoveryRequestNs = 0L
    private var lastKeyFrameRequestNs = 0L
    private var recoveryReason = ""
    @Volatile private var running = true
    @Volatile private var decoder: MediaCodec? = null
    // API 19/20 have no MediaCodec.getInputBuffer(); the array form must be cached after start().
    private var inputBuffers: Array<java.nio.ByteBuffer>? = null
    // Reused across dequeueOutputBuffer calls: allocating per frame feeds Dalvik GC pauses.
    private val outputInfo = MediaCodec.BufferInfo()
    private var outputSurface: Surface? = surface
    private var lastConfig: VideoJob.Config? = null
    private var renderedFrameLogged = false
    private var submittedFrameLogged = false
    private var duplicateConfigLogged = false
    private val referenceChain = VideoReferenceChain()
    // The main screen keeps the historical log format; other screens are labelled.
    private val stats = VideoStats(statsLabel ?: if (streamType == 110) "" else " stream=$streamType")
    private val thread = Thread(::run, "carplay-video").apply { isDaemon = true; start() }

    fun configure(codec: VideoCodec, codecData: ByteArray) {
        configuredCodec = codec
        queue.offer(VideoJob.Config(codec, codecData))
    }

    fun submit(nalus: ByteArray) {
        stats.onReceived(nalus.size)
        inputReceivedFrameCount.incrementAndGet()
        val receivedNs = System.nanoTime()
        queue.offer(VideoJob.Frame(nalus, receivedNs, configuredCodec))
    }

    fun signalDiscontinuity() {
        queue.offer(VideoJob.Resync)
    }

    fun setSurface(surface: Surface?) {
        queue.offer(VideoJob.SurfaceChanged(surface))
    }

    fun recordSurfaceCreated(count: Long) {
        surfaceCreatedCount = maxOf(surfaceCreatedCount, count)
    }

    fun recordSurfaceDestroyed(count: Long) {
        surfaceDestroyedCount = maxOf(surfaceDestroyedCount, count)
    }

    override fun close() {
        running = false
        thread.interrupt()
    }

    private fun rateSnapshot(): DecoderRateStats {
        val nowNs = System.nanoTime()
        val elapsedNs = (nowNs - rateWindowStartNs).coerceAtLeast(1L)
        val seconds = elapsedNs / 1_000_000_000.0
        val result = DecoderRateStats(
            inputReceivedFps = inputReceivedFrameCount.get() / seconds,
            inputSubmitFps = inputFrameCount / seconds,
            codecOutputFps = codecOutputFrameCount / seconds,
            renderReleaseFps = renderReleaseFrameCount / seconds,
        )
        inputReceivedFrameCount.set(0L)
        inputFrameCount = 0L
        codecOutputFrameCount = 0L
        renderReleaseFrameCount = 0L
        rateWindowStartNs = nowNs
        return result
    }

    private fun run() {
        // Single-core head unit: the decode worker must outrank background/daemon threads
        // or Wi-Fi bursts make it lose its time slice and the picture stutters behind touch.
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
        try {
            while (running) {
                val job = queue.poll(5)
                try {
                when (job) {
                    is VideoJob.Config -> configureDecoder(job)
                    is VideoJob.Frame -> {
                        if (outputSurface == null) {
                            // Surface 尚未就绪：缓存关键帧起的帧序列，等 Surface 接上时补喂
                            // ——否则错过首个 IDR 后 iPhone 不理会 forceKeyFrame，永远无法出画。
                            park(job)
                        } else if (System.nanoTime() - job.receivedNs > MAX_FRAME_AGE_NS) {
                            queue.offer(VideoJob.Resync)
                            referenceChain.reset()
                            beginRecovery("decoder backlog")
                            report(
                                "video backlog >${MAX_FRAME_AGE_NS / 1_000_000}ms; " +
                                    "waiting for a safe IDR, decoder kept",
                            )
                        } else feed(job.nalus, job.codec, job.receivedNs)
                    }
                    is VideoJob.SurfaceChanged -> changeSurface(job.surface)
                    is VideoJob.Resync -> {
                        referenceChain.reset()
                        beginRecovery("video queue discontinuity")
                    }
                    null -> Unit
                }
                    // 这里的 drain 不可省：feed 尾部的 drain(timeout=0) 时解码常未完成，
                    // 帧实际由本次（poll 5ms 后醒来时）取走渲染——静止场景（内容不变
                    // 才发帧的 iPhone 流）里删掉它会让最后一帧永远滞留在解码器里。
                    // Freescale API19 can block forever when dequeueOutputBuffer is
                    // polled continuously with timeoutUs=0. Drain after work, and
                    // poll an idle decoder at display cadence so a missing output
                    // cannot suspend the video worker indefinitely.
                    val drainNowNs = System.nanoTime()
                    if (job != null || drainNowNs - lastIdleDrainNs >= IDLE_DRAIN_INTERVAL_NS) {
                        lastIdleDrainNs = drainNowNs
                        decoder?.let(::drainOutput)
                    }
                    stats.logIfDue()?.let(report)
                    perf.logIfDue(
                        queueStats = { queue.snapshotAndResetCounters() },
                        rates = { rateSnapshot() },
                        surfaceStats = {
                            SurfacePerfStats(
                                codecConfiguredSurface = decoder != null && outputSurface != null,
                                surfaceCreatedCount = surfaceCreatedCount,
                                surfaceDestroyedCount = surfaceDestroyedCount,
                                codecReconfigureCount = codecReconfigureCount,
                            )
                        },
                    )?.let { Log.i(PERF_TAG, it) }
                    if (referenceChain.needsKeyFrame && lastConfig != null && outputSurface != null) requestKeyFrameIfDue()
                } catch (error: Exception) {
                    if (running) Log.e(TAG, "video decoder job failed: ${job?.javaClass?.simpleName}", error)
                    if (running) report("decoder error ${error.javaClass.simpleName}; waiting for keyframe")
                    releaseDecoder()
                    referenceChain.reset()
                    beginRecovery("decoder error " + error.javaClass.simpleName)
                }
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } finally {
            releaseDecoder()
        }
    }

    private fun configureDecoder(config: VideoJob.Config) {
        val previous = lastConfig
        if (
            decoder != null &&
            previous?.codec == config.codec &&
            previous.codecData.contentEquals(config.codecData)
        ) {
            if (!duplicateConfigLogged) {
                duplicateConfigLogged = true
                Log.i(TAG, "video decoder config unchanged; keeping existing decoder")
            }
            return
        }
        lastConfig = config
        duplicateConfigLogged = false
        releaseDecoder()
        referenceChain.reset()
        val surface = outputSurface ?: return
        val codec = config.codec
        val codecData = config.codecData
        val mime = if (codec == VideoCodec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC
        else MediaFormat.MIMETYPE_VIDEO_AVC
        val csd = if (codec == VideoCodec.H265) {
            MediaCodecSupport.hevcCodecSpecificData(codecData).takeIf { it.isNotEmpty() }
                ?.let { listOf(it) } ?: emptyList()
        } else {
            val (sps, pps) = MediaCodecSupport.avcParameterSets(codecData)
            listOfNotNull(
                sps.takeIf { it.isNotEmpty() }?.let { START_CODE + it },
                pps.takeIf { it.isNotEmpty() }?.let { START_CODE + it },
            )
        }
        // Some vendor decoders (e.g. MediaTek c2.mtk.avc.decoder) reject the tuned
        // parameters with BAD_VALUE. Fall back to a minimal format, then to software.
        val attempts = listOf(
            DecoderAttempt(codecName = null, tuned = true),
            DecoderAttempt(codecName = null, tuned = false),
        ) + softwareDecoderName(mime)?.let { listOf(DecoderAttempt(it, tuned = false)) }.orEmpty()
        var next: MediaCodec? = null
        for (attempt in attempts) {
            next = tryConfigure(mime, csd, surface, attempt)
            if (next != null) break
        }
        if (next == null) {
            report("decoder configuration failed mime=$mime size=${width}x$height")
        }
        decoder = next
        if (next != null) codecReconfigureCount++
        inputBuffers =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) next?.inputBuffers else null
        renderedFrameLogged = false
        submittedFrameLogged = false
        if (next != null) {
            report("decoder=${next.name} mime=$mime size=${width}x$height")
            Log.i(
                TAG,
                "video decoder configured name=${next.name} mime=$mime size=${width}x$height",
            )
        }
    }

    private data class DecoderAttempt(val codecName: String?, val tuned: Boolean)

    private fun buildFormat(mime: String, csd: List<ByteArray>, tuned: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(mime, width, height).apply {
            // KEY_FRAME_RATE exists on API19 and is only a decoder hint; it does not
            // force the phone or the hardware decoder to produce that cadence.
            setInteger(MediaFormat.KEY_FRAME_RATE, targetFps.coerceIn(30, 60))
            if (tuned) {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
                setInteger("priority", 0)
            }
            csd.forEachIndexed { index, bytes -> setByteBuffer("csd-$index", ByteBuffer.wrap(bytes)) }
        }

    private fun tryConfigure(
        mime: String,
        csd: List<ByteArray>,
        surface: Surface,
        attempt: DecoderAttempt,
    ): MediaCodec? {
        var candidate: MediaCodec? = null
        return try {
            val format = buildFormat(mime, csd, attempt.tuned)
            val codec = attempt.codecName?.let { MediaCodec.createByCodecName(it) } ?: createDecoder(mime)
            candidate = codec
            if (attempt.tuned && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                codec.codecInfo.getCapabilitiesForType(mime).isFeatureSupported("low-latency")) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            codec.configure(format, surface, null, 0)
            codec.start()
            codec
        } catch (error: Exception) {
            runCatching { candidate?.release() }
            Log.w(
                TAG,
                "video decoder configure failed name=${attempt.codecName ?: "default"} " +
                    "tuned=${attempt.tuned} mime=$mime size=${width}x$height",
                error,
            )
            null
        }
    }

    private fun softwareDecoderName(mime: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            !it.isEncoder && it.isSoftwareOnly && mime in it.supportedTypes
        }?.name
    }

    private fun createDecoder(mime: String): MediaCodec {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            mime == MediaFormat.MIMETYPE_VIDEO_HEVC &&
            preferSoftwareHevcDecoder
        ) {
            val software = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
                !it.isEncoder && it.isSoftwareOnly && mime in it.supportedTypes
            }
            if (software != null) {
                try {
                    return MediaCodec.createByCodecName(software.name)
                } catch (error: Exception) {
                    Log.w(TAG, "software HEVC decoder unavailable name=${software.name}", error)
                }
            }
        }
        return MediaCodec.createDecoderByType(mime)
    }

    /** Surface 未就绪期间的暂存帧（从最近一个随机访问点起的序列）。 */
    private val parkedFrames = ArrayDeque<VideoJob.Frame>()

    private fun park(job: VideoJob.Frame) {
        val codec = lastConfig?.codec ?: return
        val randomAccess = MediaCodecSupport.isRandomAccess(job.nalus, codec)
        // 关键帧前的 P 帧无法独立解码：只有在关键帧到达后才开始积累；新关键帧重置序列。
        if (randomAccess) parkedFrames.clear()
        if (!randomAccess && parkedFrames.isEmpty()) return
        parkedFrames.add(job)
        var totalBytes = 0L
        for (frame in parkedFrames) totalBytes += frame.nalus.size
        while (totalBytes > MAX_PARK_BYTES && parkedFrames.size > 1) {
            totalBytes -= parkedFrames.removeFirst().nalus.size
        }
    }

    /** Surface 接上后按序补喂暂存帧。 */
    private fun drainParked() {
        if (parkedFrames.isEmpty()) return
        val frames = parkedFrames.toList()
        parkedFrames.clear()
        for (frame in frames) {
            try {
                feed(frame.nalus, frame.codec, frame.receivedNs)
            } catch (error: Exception) {
                Log.w(TAG, "parked frame feed failed: ${error.javaClass.simpleName}")
                break
            }
        }
    }

    private fun changeSurface(surface: Surface?) {
        if (outputSurface === surface) return
        outputSurface = surface
        if (surface == null) {
            releaseDecoder()
            Log.i(TAG, "video decoder detached from surface")
            return
        }
        // API19-compatible path: Surface changes always reconfigure the codec.
        // Do not use MediaCodec.setOutputSurface(), which was added in API23.
        releaseDecoder()
        lastConfig?.let(::configureDecoder)
        // 重建的解码器没有参考帧状态：重置参考链，让 park 序列（关键帧起）或
        // iPhone 的下一个 IDR 立即恢复渲染。关键在主动请求关键帧——静态画面下
        // park 里往往没有缓存帧（无关键帧经过），干等 iPhone 周期性 IDR 会黑屏 3-5 秒。
        referenceChain.reset()
        restartRecoveryAfterSurfaceAttach()
        drainParked()
    }

    /** Restart IDR recovery after a SurfaceView handoff. */
    private fun restartRecoveryAfterSurfaceAttach() {
        recoveryState = DecoderRecoveryState.NORMAL
        recoveryStartedNs = 0L
        recoveryRequestNs = 0L
        recoveryReason = ""
        referenceChain.reset()
        beginRecovery("surface reattached")
    }

    private fun feed(
        nalus: ByteArray,
        frameCodec: VideoCodec? = null,
        receivedNs: Long = System.nanoTime(),
    ) {
        val annexB = MediaCodecSupport.toAnnexB(nalus)
        val config = lastConfig ?: return
        if (outputSurface == null) return
        if (annexB.isEmpty()) { recover("invalid video access unit"); return }
        if (frameCodec != null && frameCodec != config.codec) {
            referenceChain.reset()
            beginRecovery("video codec changed before decode")
            return
        }
        val wasWaitingForKeyFrame = referenceChain.needsKeyFrame
        if (!referenceChain.accepts(annexB, config.codec)) {
            perf.record(DecoderPerfMetric.RESYNC_WAITING_DROPS, 1)
            beginRecovery("waiting for IDR")
            return
        }
        if (decoder == null) configureDecoder(config)
        val codec = decoder ?: return
        if (!submittedFrameLogged) {
            submittedFrameLogged = true
            Log.i(
                TAG,
                "video decoder first input avcc=${nalus.size} annexB=${annexB.size} " +
                    "head=${annexB.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }}",
            )
        }
        if (!submitInput(codec, annexB, receivedNs)) return
        referenceChain.onQueued()
        if (wasWaitingForKeyFrame) completeRecoveryOnKeyFrame()
        // Freescale OMX 输入缓冲预热：只喂一帧时硬件解码器攒着不吐（实测静态画面
        // 返回场景关键帧躺 5.7 秒才出画）。复喂同一 IDR——每次独立解码、画面相同、
        // 参考状态一致——瞬间填满输入管线立即出画。仅首帧渲染前做，常态播放零开销。
        if (!renderedFrameLogged && codec.name.contains("Freescale", ignoreCase = true) &&
            MediaCodecSupport.isRandomAccess(annexB, config.codec)
        ) {
            var primed = 0
            while (primed < 3 && submitInput(codec, annexB, receivedNs)) primed++
        }
        drainOutput(codec)
    }

    /** 取输入缓冲并整帧提交；失败走恢复路径并返回 false。 */
    private fun submitInput(codec: MediaCodec, annexB: ByteArray, receivedNs: Long): Boolean {
        val acquireStartNs = System.nanoTime()
        var acquireRetries = 0L
        var expiredBeforeInput = false
        val index = VideoInputPump.acquire(
            running = { running }, drain = { drainOutput(codec) },
            timeoutNs = INPUT_ACQUIRE_TIMEOUT_NS,
            expired = {
                val expired = System.nanoTime() - receivedNs > MAX_FRAME_AGE_NS
                expiredBeforeInput = expired
                expired
            },
            onRetry = { acquireRetries++ },
            dequeue = {
                val wallStartNs = System.nanoTime()
                val cpuStartNs = Debug.threadCpuTimeNanos()
                try {
                    codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                } finally {
                    perf.recordStage(DecoderPerfMetric.INPUT_DEQUEUE_WALL_NS, DecoderPerfMetric.INPUT_DEQUEUE_CPU_NS, wallStartNs, cpuStartNs)
                }
            },
        )
        perf.record(DecoderPerfMetric.INPUT_ACQUIRE_WALL_NS, System.nanoTime() - acquireStartNs)
        perf.record(DecoderPerfMetric.INPUT_ACQUIRE_RETRIES, acquireRetries)
        if (index < 0) {
            if (expiredBeforeInput) {
                referenceChain.reset()
                beginRecovery("video frame expired before input")
            } else {
                report("video input buffer wait timed out retries=$acquireRetries; decoder kept")
            }
            return false
        }
        // API 19/20: getInputBuffer() does not exist; use the cached inputBuffers array.
        val input = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            codec.getInputBuffer(index)
        } else {
            inputBuffers?.getOrNull(index)
        } ?: run {
            recover("Decoder input buffer unavailable")
            return false
        }
        input.clear()
        if (annexB.size > input.remaining()) {
            recover("video frame exceeded codec input capacity")
            return false
        }
        input.put(annexB)
        val queueWallStartNs = System.nanoTime()
        val queueCpuStartNs = Debug.threadCpuTimeNanos()
        try {
            codec.queueInputBuffer(index, 0, annexB.size, queueWallStartNs / 1000, 0)
            submittedReceiveTimes.addLast(receivedNs)
            inputFrameCount++
        } finally {
            perf.recordStage(DecoderPerfMetric.INPUT_QUEUE_WALL_NS, DecoderPerfMetric.INPUT_QUEUE_CPU_NS, queueWallStartNs, queueCpuStartNs)
        }
        return true
    }

    private fun recover(reason: String) {
        releaseDecoder()
        referenceChain.reset()
        beginRecovery(reason)
    }

    private fun beginRecovery(reason: String) {
        if (recoveryState == DecoderRecoveryState.NORMAL) {
            recoveryState = DecoderRecoveryState.RECOVERY_REQUESTED
            recoveryStartedNs = System.nanoTime()
            recoveryReason = reason
            stats.onRecovery()
            report("recovery state=RECOVERY_REQUESTED reason=" + reason)
        }
        requestKeyFrameIfDue()
    }

    private fun requestKeyFrameIfDue() {
        if (recoveryState == DecoderRecoveryState.NORMAL) {
            recoveryState = DecoderRecoveryState.RECOVERY_REQUESTED
            recoveryStartedNs = System.nanoTime()
            recoveryReason = "reference chain requires IDR"
            stats.onRecovery()
            report("recovery state=RECOVERY_REQUESTED reason=" + recoveryReason)
        }
        if (recoveryState == DecoderRecoveryState.WAITING_FOR_IDR) return
        val now = System.nanoTime()
        if (lastKeyFrameRequestNs != 0L && now - lastKeyFrameRequestNs < KEYFRAME_REQUEST_COOLDOWN_NS) return
        lastKeyFrameRequestNs = now
        recoveryRequestNs = now
        recoveryState = DecoderRecoveryState.WAITING_FOR_IDR
        perf.record(DecoderPerfMetric.RECOVERY_REQUEST_COUNT, 1)
        report("recovery state=WAITING_FOR_IDR requestMs=" + ((now - recoveryStartedNs) / 1_000_000))
        requestKeyFrame()
    }

    private fun completeRecoveryOnKeyFrame() {
        if (recoveryState == DecoderRecoveryState.NORMAL) return
        val now = System.nanoTime()
        if (recoveryRequestNs != 0L) perf.record(DecoderPerfMetric.RECOVERY_IDR_WAIT_NS, now - recoveryRequestNs)
        val durationNs = if (recoveryStartedNs == 0L) 0L else now - recoveryStartedNs
        recoveryState = DecoderRecoveryState.NORMAL
        report("recovery state=NORMAL idrAccepted=true recoveryMs=" + (durationNs / 1_000_000) + " reason=" + recoveryReason)
        recoveryStartedNs = 0L
        recoveryRequestNs = 0L
        recoveryReason = ""
    }

    private fun drainOutput(codec: MediaCodec) {
        val drainWallStartNs = System.nanoTime()
        val drainCpuStartNs = Debug.threadCpuTimeNanos()
        var outputCount = 0
        try {
            val info = outputInfo
            while (running && outputCount < MAX_OUTPUTS_PER_DRAIN) {
                val dequeueWallStartNs = System.nanoTime()
                val dequeueCpuStartNs = Debug.threadCpuTimeNanos()
                val index = try {
                    codec.dequeueOutputBuffer(info, OUTPUT_DEQUEUE_TIMEOUT_US)
                } finally {
                    perf.recordStage(
                        DecoderPerfMetric.OUTPUT_DEQUEUE_WALL_NS,
                        DecoderPerfMetric.OUTPUT_DEQUEUE_CPU_NS,
                        dequeueWallStartNs,
                        dequeueCpuStartNs,
                    )
                }
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> logOutputFormat(codec.outputFormat)
                    index >= 0 -> {
                        outputCount++
                        codecOutputFrameCount++
                        val nowUs = System.nanoTime() / 1000
                        val ptsAgeUs = nowUs - info.presentationTimeUs
                        if (ptsAgeUs >= 0) perf.record(DecoderPerfMetric.OUTPUT_PTS_AGE_US, ptsAgeUs)
                        if (submittedReceiveTimes.isNotEmpty()) {
                            val receivedNs = submittedReceiveTimes.removeFirst()
                            perf.record(
                                DecoderPerfMetric.RECEIVED_TO_OUTPUT_NS,
                                (System.nanoTime() - receivedNs).coerceAtLeast(0L),
                            )
                        }
                        val render = outputSurface != null && ptsAgeUs <= MAX_OUTPUT_FRAME_AGE_US
                        if (outputSurface != null && !render) perf.staleOutputDropped()
                        val releaseWallStartNs = System.nanoTime()
                        val releaseCpuStartNs = Debug.threadCpuTimeNanos()
                        try {
                            codec.releaseOutputBuffer(index, render)
                        } finally {
                            perf.recordStage(
                                DecoderPerfMetric.OUTPUT_RELEASE_WALL_NS,
                                DecoderPerfMetric.OUTPUT_RELEASE_CPU_NS,
                                releaseWallStartNs,
                                releaseCpuStartNs,
                            )
                        }
                        if (render) {
                            stats.onRendered()
                            renderReleaseFrameCount++
                        }
                        if (render && !renderedFrameLogged) {
                            renderedFrameLogged = true
                            report("first frame rendered")
                            Log.i(TAG, "video decoder rendered first frame bytes=" + info.size)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                    else -> break
                }
            }
        } finally {
            perf.recordStage(
                DecoderPerfMetric.DRAIN_TOTAL_WALL_NS,
                DecoderPerfMetric.DRAIN_TOTAL_CPU_NS,
                drainWallStartNs,
                drainCpuStartNs,
            )
            perf.record(DecoderPerfMetric.DRAIN_OUTPUT_COUNT, outputCount.toLong())
        }
    }
    private fun logOutputFormat(format: MediaFormat) {
        report("output format requested=${width}x${height} " +
            "coded=${format.intOrNull(MediaFormat.KEY_WIDTH)}x${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
            "crop=${format.intOrNull("crop-left")},${format.intOrNull("crop-top")}," +
            "${format.intOrNull("crop-right")},${format.intOrNull("crop-bottom")} " +
            "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
            "color=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)}/${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)}/${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}")
        Log.i(
            TAG,
            "video decoder output format " +
                "size=${format.intOrNull(MediaFormat.KEY_WIDTH)}x" +
                "${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
                "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} " +
                "slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
                "standard=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)} " +
                "range=${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)} " +
                "transfer=${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}",
        )
    }

    @Synchronized
    private fun releaseDecoder() {
        val codec = decoder
        decoder = null
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
        submittedReceiveTimes.clear()
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        // 1920x720 H.264 单帧上界远小于 2MB（关键帧实测 ~100KB）；8MB 只是极端保险值，
        // 却让 OMX 每个输入缓冲都按上限分配——车机 1GB 内存上是无谓压力。2MB 足够。
        const val MAX_INPUT_SIZE = 2 * 1024 * 1024
        const val INPUT_TIMEOUT_US = 10_000L
        // On the target single-core head unit, a 400 ms queue has already missed
        // several display intervals. Starting recovery at 300 ms bounds visible
        // latency while still allowing short Wi-Fi bursts to drain normally.
        const val MAX_FRAME_AGE_NS = 300_000_000L
        const val MAX_OUTPUT_FRAME_AGE_US = 300_000L
        const val MAX_OUTPUTS_PER_DRAIN = 8
        const val INPUT_ACQUIRE_TIMEOUT_NS = 80_000_000L
        // The API19 Freescale OMX implementation may ignore a zero timeout and
        // wait on its output semaphore. A 1 ms timeout keeps the call bounded.
        const val OUTPUT_DEQUEUE_TIMEOUT_US = 1_000L
        const val IDLE_DRAIN_INTERVAL_NS = 16_000_000L
        // A second backlog can arrive shortly after a burst recovery. Two seconds
        // leaves that cycle in RECOVERY_REQUESTED without asking the iPhone for an
        // IDR; 500 ms still prevents request storms while keeping recovery bounded.
        const val KEYFRAME_REQUEST_COOLDOWN_NS = 500_000_000L
        const val PERF_TAG = "C5X_PERF"
        /** Surface 未就绪期间最多暂存的帧字节（约 3-5 秒码流）。 */
        const val MAX_PARK_BYTES = 8 * 1024 * 1024
        val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }
}

private fun MediaFormat.intOrNull(key: String): Int? =
    if (!containsKey(key)) {
        null
    } else {
        try {
            getInteger(key)
        } catch (_: Exception) {
            null
        }
    }

/** Decodes AAC-LC/Opus to PCM and plays it, or plays wired LPCM directly. */
private class AudioRenderer(
    val format: AudioFormat,
    private val advancedAudioChannelMapping: Boolean,
    private val audioFocusEnabled: Boolean,
    private val mediaChannel: Int,
    private val navigationChannel: Int,
    private val audioFocusCoordinator: AudioFocusCoordinator,
    private val navigationStreamType: Int,
    private val mediaBufferMillis: Int,
    private val report: (String) -> Unit,
) : Closeable {
    private data class AudioPacket(val rtp: ByteArray, val sample: Int, val receivedNs: Long)

    private var trackAttributes: AudioAttributes? = null
    private var mappedChannel: AudioChannel? = null
    private val queue = LinkedBlockingQueue<AudioPacket>(MAX_QUEUED_PACKETS)
    @Volatile private var running = true
    @Volatile private var started = false
    private var codec: MediaCodec? = null
    // API 19/20 have no MediaCodec.getInputBuffer()/getOutputBuffer(); the array
    // forms must be cached after start() (same pattern as the video decoder).
    private var codecInputBuffers: Array<ByteBuffer>? = null
    private var codecOutputBuffers: Array<ByteBuffer>? = null
    // Software AAC decoding on API 19/20, where vendor OMX AAC is unusable.
    private var fdkHandle = 0L
    private var fdkPcm = ShortArray(FDK_PCM_SHORTS)
    private var fdkBytes = ByteArray(FDK_PCM_SHORTS * 2)
    private var track: AudioTrack? = null
    private var pcm = ByteArray(64 * 1024)
    private var playbackStarted = false
    private var prebufferBytes = 0
    private var startThresholdBytes = 0
    private var fadeApplied = false
    private var droppedPacketsLogged = false
    private var firstAacPayloadLogged = false
    private var firstOpusShortPacketLogged = false
    private var firstInputQueuedLogged = false
    private var inputQueued = 0
    private var inputDropped = 0
    private var outputBuffers = 0
    private var firstFdkPcmLogged = false
    private var fdkDecodeErrors = 0
    private var firstPcmLogged = false
    private val packetsReceived = AtomicInteger()
    private val packetsDropped = AtomicInteger()
    private val lastArrivalNs = AtomicLong()
    private val maxArrivalGapMs = AtomicLong()
    private val frameBytes = if (format.channels >= 2) 4 else 2
    private var totalWrittenFrames = 0L
    private var writtenFramesThisWindow = 0L
    private var writeErrorsThisWindow = 0
    private var lastWriteErrorCode: Int? = null
    private var zeroWritesThisWindow = 0
    private var partialWritesThisWindow = 0
    private var lastPlaybackHeadFrames: Long? = null
    private var maxWriteMs = 0L
    private var statsWindowStartNs = 0L
    private var statsLastUnderruns = 0
    private var bytesPerSecond = 0
    private val bufferProgress = AudioBufferProgress(if (format.channels >= 2) 4 else 2)
    private var underrunsAtPlaybackStart = 0
    private var lastPcmWriteNs = 0L
    private var rebufferCount = 0
    private var queueMaxAgeMs = 0L
    private var decoderInputWaitMs = 0L
    private var rtpSequenceGaps = 0L
    private var lastRtpSequence: Int? = null
    private val thread = Thread(::run, "carplay-audio").apply { isDaemon = true }

    fun start() {
        if (started) return
        started = true
        thread.start()
    }

    fun submit(rtp: ByteArray, sample: Int) {
        if (started) {
            packetsReceived.incrementAndGet()
            val now = System.nanoTime()
            val previous = lastArrivalNs.getAndSet(now)
            if (previous != 0L) maxArrivalGapMs.accumulateAndGet((now - previous) / 1_000_000L, ::maxOf)
        }
        val packet = AudioPacket(rtp, sample, System.nanoTime())
        if (rtp.size >= 4) {
            val sequence = ((rtp[2].toInt() and 0xff) shl 8) or (rtp[3].toInt() and 0xff)
            val previous = lastRtpSequence
            if (previous != null) {
                val delta = (sequence - previous) and 0xffff
                if (delta in 2..0x7fff) rtpSequenceGaps += delta - 1
            }
            lastRtpSequence = sequence
        }
        if (!started || !queue.offer(packet)) {
            if (started) packetsDropped.incrementAndGet()
            if (started && !droppedPacketsLogged) {
                droppedPacketsLogged = true
                Log.w(TAG, "audio queue full; dropping newest packets to bound latency")
                report("Audio: queue full audioType=${format.audioType}")
            }
        }
    }

    override fun close() {
        running = false
        thread.interrupt()
    }

    private fun run() {
        // Audio is small but latency-sensitive; keep it ahead of bulk/daemon work.
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        try {
            when (format.codec) {
                AudioCodecKind.AAC_LC -> configureAacDecoder()
                AudioCodecKind.OPUS -> configureOpusDecoder()
                AudioCodecKind.LPCM -> Unit
            }
            createTrack()
            requestAudioFocus()
            while (running) {
                queue.poll(AUDIO_POLL_MILLIS, TimeUnit.MILLISECONDS)?.let(::handle)
                // Output becomes ready asynchronously, including after the last packet of a burst.
                // Waiting for the next UDP packet can strand decoded sound for hundreds of ms.
                codec?.let(::drainCodec)
                maintainPlaybackBuffer()
                logStatsIfDue()
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } catch (error: Throwable) {
            // Throwable rather than Exception: API-level gaps on old ROMs surface as
            // NoSuchMethodError/NoClassDefFoundError and must be reported instead of
            // silently killing the worker thread.
            if (running) {
                Log.e(TAG, "audio renderer worker failed", error)
                report("Audio: renderer failed audioType=${format.audioType} error=${error.javaClass.simpleName}")
            }
        } finally {
            runCatching { logStatsIfDue(force = true) }
            release()
        }
    }

    /**
     * AAC on API 19/20 can decode with the bundled FDK-AAC library: vendor OMX AAC
     * components on old head units miss the profiles CarPlay uses (unsupported
     * object types can even crash natively). API 21+ keeps the MediaCodec path.
     *
     * API 19/20 always use bundled FDK-AAC here; newer Android versions use MediaCodec.
     */
    private fun configureAacDecoder() {
        if (USE_FDK_AAC && Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP && FdkAac.isAvailable()) {
            val asc = aacAudioSpecificConfig()
            fdkHandle = FdkAac.open(asc)
            if (fdkHandle != 0L) {
                Log.i(
                    TAG,
                    "audio AAC decoder=FDK rate=${format.sampleRate} channels=${format.channels} " +
                        "asc=${asc.toHexString()}",
                )
                report("Audio: AAC via bundled FDK decoder audioType=${format.audioType}")
                return
            }
            Log.w(TAG, "audio FDK decoder rejected the ASC; falling back to MediaCodec AAC")
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            report("Audio: AAC via platform MediaCodec decoder audioType=${format.audioType} (FDK disabled)")
        }
        configureCodec(MediaFormat.MIMETYPE_AUDIO_AAC)
    }

    private fun configureOpusDecoder() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            // The MIMETYPE_AUDIO_OPUS constant and platform Opus codecs both arrive
            // with API 21; older ROMs have nothing to hand these packets to.
            Log.w(TAG, "audio Opus needs an API 21+ decoder; stream stays silent audioType=${format.audioType}")
            report("Audio: Opus unavailable on API${Build.VERSION.SDK_INT} audioType=${format.audioType}")
            return
        }
        configureCodec(MediaFormat.MIMETYPE_AUDIO_OPUS)
    }

    private fun configureCodec(mime: String) {
        val mediaFormat = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, mime)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, format.sampleRate)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, format.channels)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_IS_ADTS, 1)
                setByteBuffer("csd-0", ByteBuffer.wrap(aacAudioSpecificConfig()))
            } else {
                setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
                setByteBuffer("csd-1", ByteBuffer.wrap(opusCodecDelay()))
                setByteBuffer("csd-2", ByteBuffer.wrap(opusSeekPreRoll()))
            }
        }
        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            Log.i(
                TAG,
                "audio AAC config rate=${format.sampleRate} channels=${format.channels} " +
                    "csd0=${aacAudioSpecificConfig().toHexString()}",
            )
        }
        codec = try {
            MediaCodec.createDecoderByType(mime).also {
                it.configure(mediaFormat, null, null, 0)
                it.start()
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                    // Cache the deprecated array form right after start(); the
                    // getInputBuffer()/getOutputBuffer() accessors need API 21.
                    codecInputBuffers = it.inputBuffers
                    codecOutputBuffers = it.outputBuffers
                }
                Log.i(TAG, "audio decoder configured mime=$mime name=${it.name}")
            }
        } catch (error: Throwable) {
            Log.e(TAG, "audio decoder configuration failed mime=$mime", error)
            null
        }
    }

    private fun createTrack() {
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val channelMask = if (format.channels >= 2) AndroidAudioFormat.CHANNEL_OUT_STEREO
        else AndroidAudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
        if (minBuffer <= 0) {
            Log.e(TAG, "AudioTrack buffer size unavailable rate=${format.sampleRate} channels=${format.channels}")
            return
        }
        val selection = mappedSelection()
        mappedChannel = selection.channel
        val streamOverride = channelOverride(selection.channel)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // AudioAttributes needs API 21; the legacy path below routes by stream type.
            trackAttributes = audioAttributesFor(selection, streamOverride)
        }
        val plan = MediaAudioBuffer.plan(selection.channel == AudioChannel.MEDIA,
            format.sampleRate, format.channels, minBuffer, mediaBufferMillis)
        bytesPerSecond = format.sampleRate * frameBytes
        val built: AudioTrack
        var routeLabel: String
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            // API 19/20: AudioTrack.Builder, AudioAttributes and AudioFormat.Builder all
            // arrived with API 21. Route through the legacy stream-type constructor; the
            // usage/attributes path is selected automatically on API 21+ devices.
            val legacyStreamType = channelOverride(selection.channel).takeIf { it != 0 }
                ?: selection.streamType
            routeLabel = "legacyStreamType=$legacyStreamType"
            built = LegacyAudioFallback.build(
                createLegacy = {
                    AudioTrack(legacyStreamType, format.sampleRate, channelMask, encoding,
                        plan.trackBufferBytes, AudioTrack.MODE_STREAM)
                },
                isInitialized = { it.state == AudioTrack.STATE_INITIALIZED },
                release = { it.release() },
                createFallback = {
                    routeLabel = "legacyStreamType=$legacyStreamType(fallback=music)"
                    Log.w(TAG, "streamType=$legacyStreamType rejected by this ROM; retrying with STREAM_MUSIC")
                    AudioTrack(AudioManager.STREAM_MUSIC, format.sampleRate, channelMask, encoding,
                        plan.trackBufferBytes, AudioTrack.MODE_STREAM)
                },
            )
        } else if (streamOverride == 0) {
            val attributes = audioAttributesFor(selection)
            routeLabel = "usage"
            built = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(pcmFormat(encoding, channelMask))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(plan.trackBufferBytes)
                .build()
        } else {
            val streamType = streamOverride
            routeLabel = "streamType=$streamType"
            built = LegacyAudioFallback.build(
                createLegacy = {
                    AudioTrack(streamType, format.sampleRate, channelMask, encoding,
                        plan.trackBufferBytes, AudioTrack.MODE_STREAM)
                },
                isInitialized = { it.state == AudioTrack.STATE_INITIALIZED },
                release = { it.release() },
                createFallback = {
                    routeLabel = "streamType=$streamType(fallback=usage)"
                    Log.w(TAG, "streamType=$streamType rejected by this ROM; falling back to usage-based track")
                    AudioTrack.Builder()
                        .setAudioAttributes(audioAttributesFor(selection))
                        .setAudioFormat(pcmFormat(encoding, channelMask))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(plan.trackBufferBytes)
                        .build()
                },
            )
        }
        track = built
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            trackAttributes = built.audioAttributes
        }
        val capacityBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            built.bufferSizeInFrames * frameBytes
        } else {
            // No bufferSizeInFrames before API 23; the requested size is the closest estimate.
            plan.trackBufferBytes
        }
        startThresholdBytes = MediaAudioBuffer.startBytesFor(plan.startBytes, capacityBytes, PREBUFFER_WRITE_CHUNK_BYTES)
        report("Audio: ready audioType=${format.audioType} codec=${format.codec} " +
            "rate=${format.sampleRate} channels=${format.channels} " +
            "route=$routeLabel " +
            "bufferMs=${capacityBytes * 1000L / bytesPerSecond} startMs=${startThresholdBytes * 1000L / bytesPerSecond}")
        Log.i(
            TAG,
            "audio track prepared type=${format.payloadType} audioType=${format.audioType} " +
                "codec=${format.codec} " +
                "rate=${format.sampleRate} channels=${format.channels} " +
                "route=$routeLabel " +
                "buffer=${capacityBytes * 1000L / bytesPerSecond}ms start=${startThresholdBytes * 1000L / bytesPerSecond}",
        )
        Log.i(
            TAG,
            "audio route type=${format.payloadType} audioType=${format.audioType} " +
                "mode=${if (advancedAudioChannelMapping) AudioChannelMappingMode.AUTOMOTIVE_BUS else AudioChannelMappingMode.MOBILE_COMPATIBLE} " +
                "channel=${selection.channel} usage=${usageFor(selection.channel)} " +
                "contentType=${contentTypeFor(selection.contentType)} " +
                "streamOverride=$streamOverride " +
                "focus=${if (audioFocusEnabled) "on" else "off"}",
        )
    }

    /** 0 uses usage-based routing; 1-0 attempt legacy stream types supported by the head unit. */
    private fun channelOverride(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> mediaChannel
        AudioChannel.NAVIGATION -> navigationChannel
        else -> 0
    }

    private fun audioAttributesFor(
        selection: AudioChannelSelection,
        streamOverride: Int,
    ): AudioAttributes {
        if (streamOverride in AudioManager.STREAM_SYSTEM..AudioManager.STREAM_ACCESSIBILITY) {
            // Android accepts only its defined legacy stream IDs here. BYD audio policy can
            // map these standard streams to vehicle outputs; arbitrary channel numbers are
            // not valid AudioAttributes legacy stream types.
            try {
                return AudioAttributes.Builder().setLegacyStreamType(streamOverride).build()
            } catch (error: Exception) {
                Log.w(TAG, "legacy audio stream $streamOverride rejected; keeping usage routing", error)
            }
        }
        return AudioAttributes.Builder()
            .setUsage(usageFor(selection.channel))
            .setContentType(contentTypeFor(selection.contentType))
            .build()
    }

    private fun mappedSelection(): AudioChannelSelection {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        return AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
        )
    }

    private fun audioAttributesFor(selection: AudioChannelSelection): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(usageFor(selection.channel))
            .setContentType(contentTypeFor(selection.contentType))
            .build()

    /**
     * Shares a sink-level focus request across all active non-navigation renderers.
     * Navigation guidance intentionally takes no focus: it overlays media without ducking it.
     */
    private fun requestAudioFocus() {
        val channel = mappedChannel ?: return
        val attributes = trackAttributes ?: return
        if (channel == AudioChannel.NAVIGATION) {
            Log.i(TAG, "audio focus skipped channel=NAVIGATION; overlays without ducking")
            return
        }
        track?.let { audioFocusCoordinator.acquire(it, channel, attributes) }
    }

    private fun abandonAudioFocus() {
        track?.let(audioFocusCoordinator::release)
    }

    private fun pcmFormat(encoding: Int, channelMask: Int) = AndroidAudioFormat.Builder()
        .setSampleRate(format.sampleRate)
        .setChannelMask(channelMask)
        .setEncoding(encoding)
        .build()

    private fun streamType(): Int {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        return AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
            navigationStreamType = navigationStreamType,
        ).streamType
    }

    private fun aacAudioSpecificConfig(): ByteArray {
        val frequencyIndex = MediaCodecSupport.aacFrequencyIndex(format.sampleRate)
        val value = (AAC_OBJECT_TYPE_LC shl 11) or
            (frequencyIndex shl 7) or
            (format.channels.coerceIn(1, 7) shl 3)
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    private fun usageFor(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> AudioAttributes.USAGE_MEDIA
        AudioChannel.PHONE -> AudioAttributes.USAGE_VOICE_COMMUNICATION
        AudioChannel.ASSISTANT -> AudioAttributes.USAGE_ASSISTANT
        AudioChannel.NAVIGATION -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
    }

    private fun contentTypeFor(contentType: AudioContentType): Int = when (contentType) {
        AudioContentType.MUSIC -> AudioAttributes.CONTENT_TYPE_MUSIC
        AudioContentType.SPEECH -> AudioAttributes.CONTENT_TYPE_SPEECH
    }

    /** Minimal OpusHead CSD for the mono 48 kHz stream CarPlay negotiates. */
    private fun opusHead(): ByteArray {
        val head = ByteArray(19)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(head, 0)
        head[8] = 1
        head[9] = format.channels.toByte()
        head[10] = 0x38
        head[11] = 0x01
        head[12] = format.sampleRate.toByte()
        head[13] = (format.sampleRate ushr 8).toByte()
        head[14] = (format.sampleRate ushr 16).toByte()
        head[15] = (format.sampleRate ushr 24).toByte()
        return head
    }

    private fun opusCodecDelay(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_CODEC_DELAY_NANOS)
            .array()

    private fun opusSeekPreRoll(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_SEEK_PRE_ROLL_NANOS)
            .array()

    private fun handle(packet: AudioPacket) {
        queueMaxAgeMs = maxOf(queueMaxAgeMs, (System.nanoTime() - packet.receivedNs) / 1_000_000L)
        val rtp = packet.rtp
        val timestampUs = sampleTimestampUs(packet.sample)
        when (format.codec) {
            AudioCodecKind.LPCM -> writePcm(byteSwapS16(rtp.copyOfRange(12, rtp.size)))
            AudioCodecKind.AAC_LC -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.isNotEmpty()) {
                    if (!firstAacPayloadLogged) {
                        firstAacPayloadLogged = true
                        Log.i(
                            TAG,
                            "audio AAC access unit bytes=${accessUnit.size} " +
                                "head=${accessUnit.copyOf(minOf(accessUnit.size, 16)).toHexString()}",
                        )
                    }
                    if (fdkHandle != 0L) {
                        // FDK consumes raw access units; only the MediaCodec path wants ADTS.
                        decodeFdk(accessUnit)
                    } else {
                        feedCodec(
                            MediaCodecSupport.adtsFrame(accessUnit, format.sampleRate, format.channels),
                            timestampUs,
                        )
                    }
                }
            }
            AudioCodecKind.OPUS -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.size < MIN_OPUS_PACKET_BYTES) {
                    if (!firstOpusShortPacketLogged) {
                        firstOpusShortPacketLogged = true
                        Log.i(
                            TAG,
                            "audio Opus skipping short packet bytes=${accessUnit.size} " +
                                "head=${accessUnit.toHexString()}",
                        )
                    }
                    return
                }
                feedCodec(accessUnit, timestampUs)
            }
        }
    }

    private fun sampleTimestampUs(sample: Int): Long =
        (sample.toLong() and 0xffff_ffffL) * 1_000_000L / format.sampleRate

    private fun feedCodec(payload: ByteArray, presentationTimeUs: Long) {
        val codec = codec ?: return
        val waitStartNs = System.nanoTime()
        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        decoderInputWaitMs = maxOf(decoderInputWaitMs, (System.nanoTime() - waitStartNs) / 1_000_000L)
        if (index < 0) {
            inputDropped++
            if (inputDropped == 1) {
                Log.w(
                    TAG,
                    "audio decoder input unavailable codec=${format.codec} " +
                        "queued=$inputQueued dropped=$inputDropped",
                )
            }
            return
        }
        val input = (
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                codec.getInputBuffer(index)
            } else {
                codecInputBuffers?.getOrNull(index)
            }
        ) ?: return
        input.clear()
        if (payload.size <= input.remaining()) {
            input.put(payload)
            codec.queueInputBuffer(index, 0, payload.size, presentationTimeUs, 0)
            inputQueued++
            if (!firstInputQueuedLogged) {
                firstInputQueuedLogged = true
                Log.i(
                    TAG,
                    "audio decoder first input codec=${format.codec} bytes=${payload.size} " +
                        "head=${payload.copyOf(minOf(payload.size, 16)).toHexString()}",
                )
            }
        } else {
            codec.queueInputBuffer(index, 0, 0, 0, 0)
            inputDropped++
        }
        drainCodec(codec)
    }

    private fun drainCodec(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                        // The output byte-buffer array is re-allocated when the format changes.
                        codecOutputBuffers = codec.outputBuffers
                    }
                    Unit
                }
                index >= 0 -> {
                    val size = info.size
                    if (size > 0) {
                        outputBuffers++
                        if (outputBuffers == 1 || outputBuffers % DECODED_BUFFER_LOG_INTERVAL == 0) {
                            Log.i(
                                TAG,
                                "audio decoder output codec=${format.codec} " +
                                    "buffers=$outputBuffers bytes=$size " +
                                    "queued=$inputQueued dropped=$inputDropped",
                            )
                        }
                    }
                    if (size > 0) {
                        val output = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            codec.getOutputBuffer(index)
                        } else {
                            codecOutputBuffers?.getOrNull(index)
                        }
                        if (output != null) {
                            if (size > pcm.size) pcm = ByteArray(size)
                            output.position(info.offset)
                            output.limit(info.offset + size)
                            output.get(pcm, 0, size)
                            writePcm(pcm, 0, size)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    /** Decodes one raw AAC access unit with the bundled FDK decoder and queues the PCM. */
    private fun decodeFdk(accessUnit: ByteArray) {
        val samples = FdkAac.decode(fdkHandle, accessUnit, fdkPcm)
        if (samples <= 0) {
            fdkDecodeErrors++
            if (fdkDecodeErrors <= 3) {
                Log.w(
                    TAG,
                    "audio FDK decode result=$samples bytes=${accessUnit.size} " +
                        "head=${accessUnit.copyOf(minOf(accessUnit.size, 16)).toHexString()}",
                )
            }
            return
        }
        val bytes = samples * 2
        if (bytes > fdkBytes.size) fdkBytes = ByteArray(bytes)
        var byteIndex = 0
        for (index in 0 until samples) {
            val value = fdkPcm[index].toInt()
            fdkBytes[byteIndex++] = (value and 0xff).toByte()
            fdkBytes[byteIndex++] = (value ushr 8 and 0xff).toByte()
        }
        outputBuffers++
        if (!firstFdkPcmLogged) {
            firstFdkPcmLogged = true
            Log.i(TAG, "audio FDK first PCM samples=$samples bytes=$bytes")
        } else if (outputBuffers % DECODED_BUFFER_LOG_INTERVAL == 0) {
            Log.i(
                TAG,
                "audio FDK decoded buffers=$outputBuffers errors=$fdkDecodeErrors " +
                    "queued=$inputQueued dropped=$inputDropped",
            )
        }
        writePcm(fdkBytes, 0, bytes)
    }

    private fun writePcm(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        val track = track ?: return
        if (!firstPcmLogged && length > 0) {
            firstPcmLogged = true
            val end = minOf(data.size, offset + minOf(length, 16))
            Log.i(
                TAG,
                "audio first PCM type=${format.payloadType} bytes=$length " +
                    "head=${data.copyOfRange(offset, end).toHexString()}",
            )
        }
        if (!fadeApplied) {
            applyFadeIn(data, offset, length)
            fadeApplied = true
        }
        var written = 0
        while (written < length && running) {
            val writeLength = if (playbackStarted) {
                length - written
            } else {
                minOf(length - written, PREBUFFER_WRITE_CHUNK_BYTES)
            }
            val writeStarted = System.nanoTime()
            val count = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                track.write(data, offset + written, writeLength, AudioTrack.WRITE_BLOCKING)
            } else {
                // API 19/20 only expose the blocking three-argument overload.
                track.write(data, offset + written, writeLength)
            }
            maxWriteMs = maxOf(maxWriteMs, (System.nanoTime() - writeStarted) / 1_000_000L)
            if (count < 0) {
                writeErrorsThisWindow++
                lastWriteErrorCode = count
                break
            }
            if (count == 0) {
                zeroWritesThisWindow++
                break
            }
            if (count < writeLength) partialWritesThisWindow++
            written += count
            val framesWritten = count / frameBytes
            totalWrittenFrames += framesWritten
            writtenFramesThisWindow += framesWritten
            bufferProgress.written(count)
            lastPcmWriteNs = System.nanoTime()
            if (!playbackStarted) {
                prebufferBytes += count
                if (prebufferBytes >= startThresholdBytes) {
                    startPlayback(track)
                    Log.i(TAG, "audio playback started type=${format.payloadType}")
                }
            }
        }
    }

    private fun startPlayback(track: AudioTrack) {
        underrunsAtPlaybackStart = trackUnderruns(track)
        track.play()
        playbackStarted = true
    }

    private fun maintainPlaybackBuffer() {
        val track = track ?: return
        if (bufferProgress.shouldRebuffer(mappedChannel == AudioChannel.MEDIA, playbackStarted,
                trackUnderruns(track) > underrunsAtPlaybackStart, queue.isEmpty(), track.playbackHeadPosition)) {
            // The hardware buffer has actually drained. Pause without flushing or discarding PCM,
            // then use the configured start threshold again when music resumes.
            track.pause()
            playbackStarted = false
            prebufferBytes = 0
            rebufferCount++
        }
        // A short final burst may never reach the start threshold. Play it after a bounded wait.
        if (!playbackStarted && prebufferBytes > 0 && queue.isEmpty() &&
            System.nanoTime() - lastPcmWriteNs >= BUFFER_TAIL_WAIT_NS) {
            startPlayback(track)
        }
    }

    // Persist counters even during packet starvation, and flush before disconnect releases the track.
    private fun logStatsIfDue(force: Boolean = false) {
        val now = System.nanoTime()
        if (statsWindowStartNs == 0L) statsWindowStartNs = now
        if (!force && now - statsWindowStartNs < STATS_WINDOW_NS) return
        val currentTrack = track
        val underruns = trackUnderruns(currentTrack)
        val lastRx = lastArrivalNs.get()
        val playbackHeadFrames = currentTrack?.playbackHeadPosition
            ?.toLong()?.and(0xffff_ffffL)
        val playbackAdvanceFrames = playbackHeadFrames?.let { current ->
            val previous = lastPlaybackHeadFrames
            lastPlaybackHeadFrames = current
            previous?.let { (current - it) and 0xffff_ffffL }
        }
        val queuedFrames = playbackHeadFrames?.let { (totalWrittenFrames - it).coerceAtLeast(0L) }
        val line = "audio stats audioType=${format.audioType} channel=$mappedChannel " +
            "routeType=${routedDeviceTypeOf(currentTrack)} codec=${format.codec} " +
            "trackState=${currentTrack?.state ?: -1} playState=${currentTrack?.playState ?: -1} " +
            "sampleRate=${sampleRateOf(currentTrack)} " +
            "trackBufferFrames=${bufferSizeFramesOf(currentTrack)} " +
            "rx=${packetsReceived.getAndSet(0)} " +
            "dropped=${packetsDropped.getAndSet(0)} underruns=+${underruns - statsLastUnderruns} queue=${queue.size} " +
            "queueOldestAgeMs=$queueMaxAgeMs queueMaxAgeMs=$queueMaxAgeMs rtpSequenceGaps=$rtpSequenceGaps " +
            "playing=$playbackStarted maxGapMs=${maxArrivalGapMs.getAndSet(0)} " +
            "sinceRxMs=${if (lastRx == 0L) -1 else (now - lastRx) / 1_000_000L} maxWriteMs=$maxWriteMs " +
            "decoderInputWaitMs=$decoderInputWaitMs " +
            "writtenFrames=$writtenFramesThisWindow totalWrittenFrames=$totalWrittenFrames " +
            "playbackHeadFrames=${playbackHeadFrames ?: -1} playbackAdvanceFrames=${playbackAdvanceFrames ?: -1} " +
            "estimatedQueuedFrames=${queuedFrames ?: -1} writeErrors=$writeErrorsThisWindow " +
            "lastWriteError=${lastWriteErrorCode ?: "none"} zeroWrites=$zeroWritesThisWindow " +
            "partialWrites=$partialWritesThisWindow " +
            "decoderDroppedTotal=$inputDropped outputBuffersTotal=$outputBuffers rebuffers=$rebufferCount ended=$force"
        Log.i(STATS_TAG, line)
        report(line)
        statsLastUnderruns = underruns
        maxWriteMs = 0L
        writtenFramesThisWindow = 0L
        writeErrorsThisWindow = 0
        lastWriteErrorCode = null
        zeroWritesThisWindow = 0
        partialWritesThisWindow = 0
        statsWindowStartNs = now
        queueMaxAgeMs = 0L
        rtpSequenceGaps = 0L
        decoderInputWaitMs = 0L
    }

    /** underrunCount only exists from API 24; older releases report zero. */
    private fun trackUnderruns(track: AudioTrack?): Int =
        if (track != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            track.underrunCount
        } else {
            0
        }

    private fun routedDeviceTypeOf(track: AudioTrack?): Int =
        if (track != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            track.routedDevice?.type ?: -1
        } else {
            -1
        }

    private fun sampleRateOf(track: AudioTrack?): Int =
        if (track != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            track.sampleRate
        } else {
            format.sampleRate
        }

    private fun bufferSizeFramesOf(track: AudioTrack?): Int =
        if (track != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            track.bufferSizeInFrames
        } else {
            -1
        }

    private fun applyFadeIn(data: ByteArray, offset: Int, length: Int) {
        val samples = (length - length % 2) / 2
        val fadeSamples = minOf(samples, maxOf(1, format.sampleRate / 100))
        for (index in 0 until fadeSamples) {
            val position = offset + index * 2
            val sample = (data[position].toInt() and 0xff) or (data[position + 1].toInt() shl 8)
            val scaled = (sample.toLong() * (index + 1) / fadeSamples).toInt()
            data[position] = scaled.toByte()
            data[position + 1] = (scaled shr 8).toByte()
        }
    }

    private fun byteSwapS16(source: ByteArray): ByteArray {
        for (index in 0 until source.size - 1 step 2) {
            val tmp = source[index]
            source[index] = source[index + 1]
            source[index + 1] = tmp
        }
        return source
    }

    @Synchronized
    private fun release() {
        abandonAudioFocus()
        val fdk = fdkHandle
        fdkHandle = 0L
        if (fdk != 0L) {
            try {
                FdkAac.close(fdk)
            } catch (_: Exception) {
                // Best effort.
            }
        }
        val codec = codec
        this.codec = null
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
        val track = track
        this.track = null
        if (track != null) {
            try {
                track.pause()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.flush()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        /** API 19 AAC 解码路径开关：false = 平台 MediaCodec（OMX）原始解码，true = 内置 FDK。 */
        const val USE_FDK_AAC = true
        const val AAC_OBJECT_TYPE_LC = 2
        const val MIN_OPUS_PACKET_BYTES = 4
        /** AAC-LC tops out at 1024 samples per channel; 8192 shorts leave ample headroom. */
        const val FDK_PCM_SHORTS = 8192
        const val OPUS_CODEC_DELAY_NANOS = 6_500_000L
        const val OPUS_SEEK_PRE_ROLL_NANOS = 80_000_000L
        const val INPUT_TIMEOUT_US = 10_000L
        const val AUDIO_POLL_MILLIS = 10L
        const val BUFFER_TAIL_WAIT_NS = 500_000_000L
        // Holds a burst after a Wi-Fi gap (~4 s of AAC) instead of dropping it.
        const val MAX_QUEUED_PACKETS = 192
        const val PREBUFFER_WRITE_CHUNK_BYTES = 2 * 1024
        const val STATS_TAG = "DiPlay-AudioStats"
        const val STATS_WINDOW_NS = 5_000_000_000L
        const val DECODED_BUFFER_LOG_INTERVAL = 50
    }
}
