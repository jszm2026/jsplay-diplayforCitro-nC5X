package com.shilapi.xcertplay.airplay

import android.util.Log
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.Provider
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class VideoCodec { H264, H265 }

/**
 * Receives one CarPlay screen stream on a TCP data port.
 *
 * Each message is a 128-byte AirPlayScreenHeader followed by a body: a clear VideoConfig
 * (avcC/hvcC) or a ChaCha20-Poly1305 sealed VideoFrame. The key is the DataStream output key
 * and the per-frame nonce is an 8-byte little-endian counter.
 */
class ScreenStream(private val key: ByteArray, private val onDiagnostic: (String) -> Unit = {}) : Closeable {
    interface Listener {
        fun onCodec(codec: VideoCodec) {}
        fun onConfig(codecData: ByteArray) {}
        fun onFrame(naluBytes: ByteArray) {}
        fun onFrameDropped() {}
        fun onClosed(cause: Throwable?) {}
    }

    private val closed = AtomicBoolean(false)
    private val frameCounter = AtomicLong(0)
    private val firstFrameLogged = AtomicBoolean(false)
    private val frameDecryptor = ScreenCodec.FrameDecryptor(key)
    private data class PendingMessage(
        val header: ByteArray,
        val body: ByteArray,
        val bodySize: Int,
        val counter: Long,
        val enqueuedNs: Long,
    )

    // Keep socket reads independent from decryption and decoder queue submission. A short queue
    // absorbs Wi-Fi bursts without allowing stale frames to build an unbounded latency backlog.
    private val processingQueue = ArrayBlockingQueue<PendingMessage>(4)
    private val bodyBufferPool = ArrayBlockingQueue<ByteArray>(PROCESSING_QUEUE_CAPACITY + 1).apply {
        repeat(PROCESSING_QUEUE_CAPACITY + 1) { offer(ByteArray(INITIAL_BODY_BUFFER_BYTES)) }
    }
    // acquireBodyBuffer is confined to the socket reader; reuse this scratch array instead of
    // allocating a temporary list for every message while returning undersized pool entries.
    private val undersizedBodyBuffers = arrayOfNulls<ByteArray>(PROCESSING_QUEUE_CAPACITY + 1)
    private val frameDiscontinuityPending = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var thread: Thread? = null
    private var processingThread: Thread? = null
    @Volatile private var receiveStats: StreamReceiveStats? = null
    @Volatile private var listener: Listener = object : Listener {}

    fun listen(listener: Listener): Int {
        this.listener = listener
        val bound = AirPlayWildcardBind.server()
        // accept 前预设：ServerSocket 的缓冲会作为 accepted socket 的初始值，
        // 避免 accept 后窗口协商的过渡期（与 configureVideoSocket 的显式设置双保险）。
        runCatching { bound.receiveBufferSize = VIDEO_RECEIVE_BUFFER_BYTES }
        server = bound
        processingThread = Thread(::processLoop, "airplay-screen-process").apply {
            isDaemon = true
            start()
        }
        thread = Thread({ accept(bound) }, "airplay-screen").apply { isDaemon = true; start() }
        return bound.localPort
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        safeClose(socket)
        safeClose(server)
        thread?.interrupt()
        processingThread?.interrupt()
    }

    private fun accept(bound: ServerSocket) {
        // 视频接收线程提级到 DISPLAY（与解码线程同级）：默认优先级下，WiFi 突发到达时
        // 会被系统进程（mediaserver GC 等）抢占出 200ms+ 的处理尖峰（实测 processMaxUs=209ms），
        // 帧龄被拉长直接吞掉滑动流畅度。提级后接收-解密-入队这条关键路径不再被饿。
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY) }
        try {
            val accepted = bound.accept()
            socket = accepted
            configureVideoSocket(accepted)
            run(accepted)
        } catch (error: Exception) {
            if (!closed.get()) listener.onClosed(error)
        }
    }

    /**
     * 视频流 TCP 参数：接收缓冲扩到 2MB——掉帧根因修复。
     *
     * 2.4G WiFi 一次可突发 2MB（实测 128 包挤在一起到），而默认接收缓冲只有 ~128KB：
     * 突发溢出→内核丢包→TCP RTO 重传（实测 readMaxMs=921/1066ms 正是 RTO 量级）→
     * 队头阻塞，iPhone 端拥塞控制收缩发送窗口，帧率被压到 16-22fps。
     * 2MB 缓冲把突发完整吸收进内核，read 不再等重传；advertised window 也足够大，
     * 不成为 iPhone 发送速率的瓶颈。音频流同款做法（AudioStream 512KB）。
     */
    private fun configureVideoSocket(accepted: Socket) {
        val original = runCatching { accepted.receiveBufferSize }.getOrDefault(0)
        if (original < VIDEO_RECEIVE_BUFFER_BYTES) {
            runCatching { accepted.receiveBufferSize = VIDEO_RECEIVE_BUFFER_BYTES }
        }
        runCatching { accepted.tcpNoDelay = true }
        onDiagnostic(
            "Video TCP receive buffer original=$original requested=$VIDEO_RECEIVE_BUFFER_BYTES " +
                "actual=${runCatching { accepted.receiveBufferSize }.getOrDefault(0)}",
        )
    }

    private fun run(sock: Socket) {
        var failure: Throwable? = null
        val tcpCounters = TcpCounterWindow()
        val stats = StreamReceiveStats("video", onDiagnostic, windowDetails = {
            "tcpDeviceDelta=[${tcpCounters.nextDelta()}]"
        })
        receiveStats = stats
        onDiagnostic("Video decryptor=${frameDecryptor.backendName}")
        onDiagnostic("TCP device-wide counters baseline=[${tcpCounters.baseline()}]")
        // 接收缓冲复用：每帧原本新建 128B header + ~40KB body，30fps 下 ≈1.2MB/s 的短命垃圾，
        // 弱双核上 GC_CONCURRENT 停顿直接打进帧龄与触摸→帧延迟。body 解密输出仍需新分配
        // （VideoJob.Frame 异步持有，生命周期跨线程），垃圾量减半。
        val header = ByteArray(HEADER_LEN)
        try {
            val input = sock.getInputStream()
            while (!closed.get()) {
                stats.reading()
                if (!readFully(input, header, HEADER_LEN, stats::firstByte)) break
                val bodySize = readU32Le(header, 0)
                if (bodySize > MAX_BODY) break
                val bodyBuffer = acquireBodyBuffer(bodySize)
                if (!readFully(input, bodyBuffer, bodySize)) {
                    recycleBodyBuffer(bodyBuffer)
                    break
                }
                val opcode = header[OPCODE_OFFSET].toInt() and 0xff
                val kind = when {
                    opcode == OP_VIDEO_FRAME && bodySize > 0 -> "frame"
                    opcode == OP_VIDEO_CONFIG -> "config"
                    bodySize == 0 -> "empty"
                    else -> "other"
                }
                stats.received(HEADER_LEN + bodySize, kind = kind, bodySize = bodySize)
                if (bodySize == 0 || (opcode != OP_VIDEO_FRAME && opcode != OP_VIDEO_CONFIG)) {
                    // Empty keepalive/control messages carry no picture data. Avoid copying them
                    // or putting them on the decrypt/decoder handoff queue.
                    recycleBodyBuffer(bodyBuffer)
                    stats.processed()
                    continue
                }
                val counter = if (opcode == OP_VIDEO_FRAME && bodySize >= ScreenCodec.TAG_SIZE) {
                    frameCounter.getAndIncrement()
                } else {
                    -1L
                }
                enqueue(PendingMessage(
                    header.copyOf(), bodyBuffer, bodySize, counter, System.nanoTime(),
                ))
                stats.processed()
            }
        } catch (error: Exception) {
            failure = error
        } finally {
            stats.flush(ended = true)
            receiveStats = null
            if (socket === sock) socket = null
            safeClose(sock)
            if (!closed.get()) listener.onClosed(failure)
        }
    }

    private fun enqueue(message: PendingMessage) {
        if (processingQueue.offer(message)) return

        // Preserve a codec config if one is queued, but replace the oldest frame so latency stays
        // bounded after a radio burst. The decoder's existing reference-chain recovery handles
        // the resulting frame discontinuity.
        val dropped = processingQueue.poll()
        if (dropped != null) {
            if (dropped.isVideoFrame()) {
                frameDiscontinuityPending.set(true)
                receiveStats?.queueDropped()
                onDiagnostic("Video processing queue full; dropped pending frame")
                recycleBodyBuffer(dropped.body)
            } else {
                // Keep codec config in front of later frames if it was the oldest message.
                processingQueue.offer(dropped)
            }
        } else {
            recycleBodyBuffer(message.body)
            return
        }
        if (!processingQueue.offer(message)) {
            if (message.isVideoFrame()) {
                frameDiscontinuityPending.set(true)
                receiveStats?.queueDropped()
                onDiagnostic("Video processing queue full; dropped incoming frame")
            }
            recycleBodyBuffer(message.body)
        }
    }

    private fun acquireBodyBuffer(requiredBytes: Int): ByteArray {
        var selected: ByteArray? = null
        var undersizedCount = 0
        repeat(bodyBufferPool.size) {
            val candidate = bodyBufferPool.poll() ?: return@repeat
            if (selected == null && candidate.size >= requiredBytes) {
                selected = candidate
            } else {
                undersizedBodyBuffers[undersizedCount++] = candidate
            }
        }
        for (index in 0 until undersizedCount) {
            val candidate = undersizedBodyBuffers[index]
            if (candidate != null) bodyBufferPool.offer(candidate)
            undersizedBodyBuffers[index] = null
        }
        return selected ?: ByteArray(maxOf(requiredBytes, INITIAL_BODY_BUFFER_BYTES))
    }

    private fun recycleBodyBuffer(buffer: ByteArray) {
        if (buffer.size <= MAX_POOLED_BODY_BUFFER_BYTES) bodyBufferPool.offer(buffer)
    }

    private fun PendingMessage.isVideoFrame(): Boolean =
        (header[OPCODE_OFFSET].toInt() and 0xff) == OP_VIDEO_FRAME && bodySize > 0

    private fun processLoop() {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY) }
        try {
            while (!closed.get()) {
                val message = processingQueue.take()
                try {
                    processMessage(message)
                } finally {
                    recycleBodyBuffer(message.body)
                }
            }
        } catch (_: InterruptedException) {
            // Stream shut down.
        } catch (error: Exception) {
            if (!closed.get()) {
                Log.e(TAG, "video processing failed", error)
                listener.onClosed(error)
            }
        }
    }

    private fun processMessage(message: PendingMessage) {
        receiveStats?.queued(message.enqueuedNs)
        val header = message.header
        val body = message.body
        val bodySize = message.bodySize
        when (header[OPCODE_OFFSET].toInt() and 0xff) {
            OP_VIDEO_FRAME -> {
                if (frameDiscontinuityPending.compareAndSet(true, false)) listener.onFrameDropped()
                val payload = if (bodySize >= ScreenCodec.TAG_SIZE) {
                    frameDecryptor.decrypt(message.counter, header, body, bodySize)
                } else {
                    body.copyOf(bodySize)
                }
                if (firstFrameLogged.compareAndSet(false, true)) {
                    Log.i(
                        TAG,
                        "video first decrypted frame sealed=$bodySize plain=${payload.size} " +
                        "head=${payload.hexPrefix(16)}",
                    )
                }
                listener.onFrame(ScreenCodec.lengthPrefixedToAnnexB(payload))
            }
            OP_VIDEO_CONFIG -> {
                val configPayload = body.copyOf(bodySize)
                val (codec, codecData) = ScreenCodec.detectConfig(configPayload)
                Log.i(TAG, "video codec config codec=$codec body=$bodySize data=${codecData.size}")
                listener.onCodec(codec)
                listener.onConfig(codecData)
            }
        }
    }

    private fun readFully(
        input: InputStream,
        output: ByteArray,
        length: Int,
        onFirstByte: (() -> Unit)? = null,
    ): Boolean {
        var offset = 0
        while (offset < length) {
            val read = input.read(output, offset, length - offset)
            if (read < 0) return false
            if (read > 0) onFirstByte?.invoke()
            offset += read
        }
        return true
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val HEADER_LEN = 128
        const val OPCODE_OFFSET = 4
        const val OP_VIDEO_FRAME = 0
        const val OP_VIDEO_CONFIG = 1
        const val MAX_BODY = 8 * 1024 * 1024
        const val PROCESSING_QUEUE_CAPACITY = 4
        const val INITIAL_BODY_BUFFER_BYTES = 64 * 1024
        const val MAX_POOLED_BODY_BUFFER_BYTES = 512 * 1024
        /** 2.4G WiFi 突发可达 2MB：接收缓冲必须大于突发量，否则内核丢包触发 TCP RTO 重传。 */
        const val VIDEO_RECEIVE_BUFFER_BYTES = 2 * 1024 * 1024
    }
}

private fun ByteArray.hexPrefix(length: Int): String =
    take(length).joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Extracts the avcC/hvcC codec-data record from a VideoConfig payload. */
object ScreenCodec {
    /** Confined to the screen receive thread; reuse the AEAD engine and expanded key per stream. */
    class FrameDecryptor(key: ByteArray) {
        private val keyBytes = key.copyOf()
        private data class NativeBackend(val cipher: Cipher, val key: SecretKeySpec)

        private val nativeBackend = runCatching {
            val conscrypt = Class.forName("org.conscrypt.Conscrypt")
            val provider = conscrypt.getMethod("newProvider").invoke(null) as Provider
            NativeBackend(
                Cipher.getInstance(CONSCRYPT_TRANSFORMATION, provider),
                SecretKeySpec(keyBytes, CHACHA_KEY_ALGORITHM),
            )
        }.getOrNull()
        private val fallbackCipher = if (nativeBackend == null) ChaCha20Poly1305() else null
        private val fallbackKey = if (fallbackCipher != null) KeyParameter(keyBytes) else null

        val backendName: String = if (nativeBackend != null) "Conscrypt/BoringSSL" else "BouncyCastle"

        fun decrypt(counter: Long, header: ByteArray, body: ByteArray, bodySize: Int): ByteArray {
            require(bodySize in TAG_SIZE..body.size) { "Invalid sealed frame length $bodySize" }
            val nonce = AirPlayCrypto.nonce64(counter)
            nativeBackend?.let { backend ->
                val cipher = backend.cipher
                cipher.init(Cipher.DECRYPT_MODE, backend.key, IvParameterSpec(nonce))
                cipher.updateAAD(header)
                return cipher.doFinal(body, 0, bodySize)
            }
            val cipher = requireNotNull(fallbackCipher)
            cipher.init(false, AEADParameters(requireNotNull(fallbackKey), MAC_BITS, nonce, header))
            val output = ByteArray(cipher.getOutputSize(bodySize))
            val processed = cipher.processBytes(body, 0, bodySize, output, 0)
            val finalized = cipher.doFinal(output, processed)
            val outputLength = processed + finalized
            return if (outputLength == output.size) output else output.copyOf(outputLength)
        }
    }

    fun decryptFrame(key: ByteArray, counter: Long, header: ByteArray, body: ByteArray): ByteArray =
        if (body.size < TAG_SIZE) body
        else AirPlayCrypto.chachaOpen(key, AirPlayCrypto.nonce64(counter), body, header)

    /** Length-aware variant for a recycled receive buffer larger than the payload. */
    fun decryptFrame(
        key: ByteArray,
        counter: Long,
        header: ByteArray,
        body: ByteArray,
        bodySize: Int,
    ): ByteArray =
        if (bodySize < TAG_SIZE) body.copyOf(bodySize)
        else AirPlayCrypto.chachaOpen(key, AirPlayCrypto.nonce64(counter), body, bodySize, header)

    /**
     * Replaces each four-byte NAL length with an Annex B start code in place.
     *
     * The payload is left untouched unless every length-prefixed NAL is valid, so malformed
     * input keeps its original bytes for the normal decoder error path.
     */
    fun lengthPrefixedToAnnexB(payload: ByteArray): ByteArray {
        if (payload.size < 4 || payload.startsWithStartCode()) return payload

        var offset = 0
        while (offset + 4 <= payload.size) {
            val length = readU32Be(payload, offset)
            offset += 4
            if (length <= 0 || offset + length > payload.size) return payload
            offset += length
        }
        if (offset != payload.size) return payload

        offset = 0
        while (offset + 4 <= payload.size) {
            val length = readU32Be(payload, offset)
            payload[offset] = 0
            payload[offset + 1] = 0
            payload[offset + 2] = 0
            payload[offset + 3] = 1
            offset += 4 + length
        }
        return payload
    }

    fun detectConfig(payload: ByteArray): Pair<VideoCodec, ByteArray> {
        for (index in 4..payload.size - 4) {
            val fourcc = String(payload, index, 4, Charsets.US_ASCII)
            when (fourcc) {
                "hvcC" -> return VideoCodec.H265 to payload.copyOfRange(index + 4, payload.size)
                "avcC" -> return VideoCodec.H264 to payload.copyOfRange(index + 4, payload.size)
            }
        }
        return if (looksLikeAvcC(payload)) VideoCodec.H264 to payload else VideoCodec.H265 to payload
    }

    private fun looksLikeAvcC(payload: ByteArray): Boolean {
        if (payload.size < 9) return false
        if ((payload[5].toInt() and 0x1f) < 1) return false
        val spsLength = readU16Be(payload, 6)
        if (8 + spsLength > payload.size) return false
        return (payload[8].toInt() and 0x1f) == 7
    }

    private fun readU16Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    const val TAG_SIZE = 16
    private const val MAC_BITS = 128
    private const val CHACHA_KEY_ALGORITHM = "ChaCha20"
    private const val CONSCRYPT_TRANSFORMATION = "ChaCha20/Poly1305/NoPadding"
}

private fun ByteArray.startsWithStartCode(): Boolean =
    size >= 4 &&
        this[0] == 0.toByte() &&
        this[1] == 0.toByte() &&
        this[2] == 0.toByte() &&
        this[3] == 1.toByte()

private fun readU32Be(source: ByteArray, offset: Int): Int =
    ((source[offset].toInt() and 0xff) shl 24) or
        ((source[offset + 1].toInt() and 0xff) shl 16) or
        ((source[offset + 2].toInt() and 0xff) shl 8) or
        (source[offset + 3].toInt() and 0xff)

private fun readU32Le(source: ByteArray, offset: Int): Int =
    (source[offset].toInt() and 0xff) or
        ((source[offset + 1].toInt() and 0xff) shl 8) or
        ((source[offset + 2].toInt() and 0xff) shl 16) or
        ((source[offset + 3].toInt() and 0xff) shl 24)
