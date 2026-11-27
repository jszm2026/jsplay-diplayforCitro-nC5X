package com.shilapi.xcertplay.media

import android.view.Surface
import com.shilapi.xcertplay.airplay.VideoCodec
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal sealed interface VideoJob {
    data class Config(val codec: VideoCodec, val codecData: ByteArray) : VideoJob
    data class Frame(
        val nalus: ByteArray,
        val receivedNs: Long = System.nanoTime(),
        val codec: VideoCodec? = null,
    ) : VideoJob
    data class SurfaceChanged(val surface: Surface?) : VideoJob
    data object Resync : VideoJob
}

/** Do not resume dependent pictures after losing a reference frame. */
internal class VideoReferenceChain {
    var needsKeyFrame = true
        private set

    fun reset() { needsKeyFrame = true }

    fun accepts(bytes: ByteArray, codec: VideoCodec): Boolean =
        !needsKeyFrame || MediaCodecSupport.isRandomAccess(bytes, codec)

    fun onQueued() { needsKeyFrame = false }
}

internal data class VideoQueueStats(
    val frames: Int,
    val bytes: Long,
    val oldestAgeMs: Long,
    val highWater: Int,
    val droppedFrames: Long,
    val overflowEvents: Long,
)

/**
 * Bounded video queue with reference-safe pressure recovery.
 *
 * A pressure event never keeps an arbitrary P/B picture after removing an older picture.
 * It keeps a recent random-access frame and its complete suffix, or emits Resync and waits
 * for a future IDR. Control jobs remain in their original order.
 */
internal class VideoDecodeQueue(
    private val maxFrames: Int = DEFAULT_MAX_FRAMES,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
    private val maxFrameAgeNs: Long = DEFAULT_MAX_FRAME_AGE_NS,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val jobs = ArrayDeque<VideoJob>()
    private var queuedFrames = 0
    private var queuedBytes = 0L
    private var highWater = 0
    private var droppedFrames = 0L
    private var overflowEvents = 0L

    init {
        require(maxFrames > 0) { "maxFrames must be positive" }
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(maxFrameAgeNs > 0) { "maxFrameAgeNs must be positive" }
    }

    fun offer(job: VideoJob) = lock.withLock {
        when (job) {
            is VideoJob.Resync -> {
                discardFramesLocked()
                appendResyncLocked()
            }
            is VideoJob.Frame -> {
                if (job.nalus.size > maxBytes) {
                    overflowEvents++
                    discardFramesLocked()
                    appendResyncLocked()
                } else {
                    jobs.addLast(job)
                    queuedFrames++
                    queuedBytes += job.nalus.size
                    highWater = maxOf(highWater, queuedFrames)
                    val nowNs = nanoTime()
                    if (hasPressureLocked(nowNs)) compactForRecoveryLocked(nowNs)
                }
            }
            else -> jobs.addLast(job)
        }
        notEmpty.signal()
    }

    @Throws(InterruptedException::class)
    fun poll(timeoutMillis: Long): VideoJob? = lock.withLock {
        var remainingNs = TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceAtLeast(0))
        while (jobs.isEmpty() && remainingNs > 0) {
            remainingNs = notEmpty.awaitNanos(remainingNs)
        }
        if (jobs.isNotEmpty()) {
            val nowNs = nanoTime()
            if (hasPressureLocked(nowNs)) compactForRecoveryLocked(nowNs)
        }
        val job = jobs.pollFirst() ?: return@withLock null
        if (job is VideoJob.Frame) {
            queuedFrames--
            queuedBytes -= job.nalus.size
        }
        job
    }

    fun discardFrames() = lock.withLock { discardFramesLocked() }

    fun snapshot(): VideoQueueStats = lock.withLock { snapshotLocked(resetCounters = false) }

    fun snapshotAndResetCounters(): VideoQueueStats = lock.withLock {
        snapshotLocked(resetCounters = true)
    }

    private fun snapshotLocked(resetCounters: Boolean): VideoQueueStats {
        val oldestNs = oldestReceivedNsLocked()
        val ageMs = if (oldestNs == null) 0L else {
            ((nanoTime() - oldestNs).coerceAtLeast(0L)) / 1_000_000L
        }
        val result = VideoQueueStats(
            frames = queuedFrames,
            bytes = queuedBytes,
            oldestAgeMs = ageMs,
            highWater = highWater,
            droppedFrames = droppedFrames,
            overflowEvents = overflowEvents,
        )
        if (resetCounters) {
            highWater = queuedFrames
            droppedFrames = 0L
            overflowEvents = 0L
        }
        return result
    }

    private fun hasPressureLocked(nowNs: Long): Boolean {
        val oldestNs = oldestReceivedNsLocked() ?: return false
        return queuedFrames > maxFrames || queuedBytes > maxBytes ||
            nowNs - oldestNs > maxFrameAgeNs
    }

    private fun oldestReceivedNsLocked(): Long? {
        var oldest: Long? = null
        for (job in jobs) {
            if (job is VideoJob.Frame && (oldest == null || job.receivedNs < oldest)) {
                oldest = job.receivedNs
            }
        }
        return oldest
    }

    private fun compactForRecoveryLocked(nowNs: Long) {
        if (!hasPressureLocked(nowNs)) return
        overflowEvents++
        val frames = ArrayList<VideoJob.Frame>(queuedFrames)
        for (job in jobs) if (job is VideoJob.Frame) frames += job

        // Pick the newest IDR whose complete suffix fits all three limits.
        var anchor = -1
        var suffixBytes = 0L
        var suffixCount = 0
        for (index in frames.indices.reversed()) {
            val frame = frames[index]
            suffixCount++
            suffixBytes += frame.nalus.size
            val recent = nowNs - frame.receivedNs <= maxFrameAgeNs
            if (recent && frame.codec != null && MediaCodecSupport.isRandomAccess(frame.nalus, frame.codec) &&
                suffixCount <= maxFrames && suffixBytes <= maxBytes
            ) {
                anchor = index
                break
            }
        }

        val rebuilt = ArrayDeque<VideoJob>()
        var frameIndex = 0
        var insertedResync = false
        var newFrames = 0
        var newBytes = 0L
        for (job in jobs) {
            when (job) {
                is VideoJob.Frame -> {
                    val keep = anchor >= 0 && frameIndex >= anchor
                    if (keep) {
                        if (!insertedResync) {
                            rebuilt.addLast(VideoJob.Resync)
                            insertedResync = true
                        }
                        rebuilt.addLast(job)
                        newFrames++
                        newBytes += job.nalus.size
                    } else {
                        droppedFrames++
                    }
                    frameIndex++
                }
                is VideoJob.Resync -> Unit
                else -> rebuilt.addLast(job)
            }
        }
        if (!insertedResync) rebuilt.addLast(VideoJob.Resync)
        jobs.clear()
        jobs.addAll(rebuilt)
        queuedFrames = newFrames
        queuedBytes = newBytes
        highWater = maxOf(highWater, queuedFrames)
    }

    private fun appendResyncLocked() {
        val retained = ArrayDeque<VideoJob>()
        for (job in jobs) if (job !is VideoJob.Resync) retained.addLast(job)
        jobs.clear()
        jobs.addAll(retained)
        jobs.addLast(VideoJob.Resync)
    }

    private fun discardFramesLocked() {
        val retained = ArrayDeque<VideoJob>()
        for (job in jobs) {
            if (job is VideoJob.Frame) droppedFrames++
            else if (job !is VideoJob.Resync) retained.addLast(job)
        }
        jobs.clear()
        jobs.addAll(retained)
        queuedFrames = 0
        queuedBytes = 0L
    }

    companion object {
        const val DEFAULT_MAX_FRAMES = 6
        const val DEFAULT_MAX_BYTES = 4 * 1024 * 1024
        const val DEFAULT_MAX_FRAME_AGE_NS = 160_000_000L
    }
}

/** Drain output while waiting for input: full output buffers can otherwise starve input forever. */
internal object VideoInputPump {
    fun acquire(
        running: () -> Boolean,
        drain: () -> Unit,
        dequeue: () -> Int,
        nanoTime: () -> Long = System::nanoTime,
        timeoutNs: Long = TimeUnit.MILLISECONDS.toNanos(80),
        expired: () -> Boolean = { false },
        onRetry: () -> Unit = {},
    ): Int {
        val start = nanoTime()
        while (running()) {
            if (expired()) break
            drain()
            if (expired()) break
            val index = dequeue()
            if (index >= 0) return index
            onRetry()
            if (expired() || nanoTime() - start >= timeoutNs) break
        }
        return -1
    }
}
