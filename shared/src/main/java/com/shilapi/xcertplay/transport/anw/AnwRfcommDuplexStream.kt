package com.shilapi.xcertplay.transport.anw

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A bounded [BlockingDuplexByteStream] over an RFCOMM socket owned by the HSAE factory
 * Bluetooth stack (Anw SDK), mirroring [com.shilapi.xcertplay.transport.BluetoothRfcommDuplexStream].
 *
 * The factory API has no blocking read; the service pushes data through the registered
 * `SocketDataIND` callback into a bounded FIFO that [recv] drains. Connect completion is
 * asynchronous, so [connect] polls transaction 228 until the intended address reports connected
 * state 1. A low-frequency status watchdog marks the peer gone while [recv] waits.
 *
 * The data callback is registered before the socket is created so no early frame is lost; while
 * the socket index is still unset any data is accepted, because this client owns the only socket
 * in the factory subsystem. [close] disconnects the owned socket, unregisters the callback,
 * deinitializes the subsystem when this stream initialized it, and unbinds the service — on every
 * exit path, including failures. The iAP2 stack above is unchanged.
 */
class AnwRfcommDuplexStream private constructor(
    private val link: AnwPhoneLinkClient,
    private val ownsSocketSubsystem: Boolean,
) : BlockingDuplexByteStream {

    private val lock = Object()
    private val sendLock = Object()
    private val pending = ArrayDeque<ByteArray>()
    private var pendingBytes = 0
    private var peerEnded = false
    private var closed = false
    private var failure: IOException? = null

    @Volatile
    private var socketIndex: Int = INDEX_UNSET

    init {
        link.registerSocketDataCallback { index, data ->
            val own = socketIndex
            if (own == INDEX_UNSET || index == own) offer(data)
        }
    }

    override fun send(data: ByteArray) {
        synchronized(sendLock) {
            synchronized(lock) {
                failure?.let { throw it }
                if (closed) throw IOException("Anw RFCOMM stream is closed")
            }
            var offset = 0
            while (offset < data.size) {
                val length = minOf(data.size - offset, WRITE_CHUNK_BYTES)
                val written = try {
                    link.socketWrite(socketIndex, data, offset, length)
                } catch (remote: android.os.RemoteException) {
                    val io = IOException("Anw socket write failed", remote)
                    fail(io)
                    throw io
                }
                if (written <= 0) {
                    val io = IOException("Anw socket write returned $written for $length bytes")
                    fail(io)
                    throw io
                }
                offset += written
            }
        }
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }

        val deadlineNanos = deadlineAfter(timeoutMillis)
        var nextWatchdogNanos = System.nanoTime() + WATCHDOG_INTERVAL_NANOS
        synchronized(lock) {
            while (true) {
                takePendingLocked(maxBytes)?.let { return it }
                failure?.let { throw it }
                if (peerEnded || closed) return EMPTY

                val now = System.nanoTime()
                val remainingNanos = deadlineNanos - now
                if (remainingNanos <= 0) return null

                // Poll the connection state while idle: the factory stack reports peer loss only
                // through transaction 228, not through a callback.
                if (now >= nextWatchdogNanos) {
                    nextWatchdogNanos = now + WATCHDOG_INTERVAL_NANOS
                    if (!socketStillConnected()) {
                        peerEnded = true
                        return EMPTY
                    }
                }

                try {
                    val waitNanos = minOf(remainingNanos, nextWatchdogNanos - now)
                    if (waitNanos > 0) {
                        lock.wait(
                            waitNanos / NANOS_PER_MILLISECOND,
                            (waitNanos % NANOS_PER_MILLISECOND).toInt(),
                        )
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
    }

    override fun close() {
        val firstClose = synchronized(lock) {
            if (closed) {
                false
            } else {
                closed = true
                lock.notifyAll()
                true
            }
        }
        if (!firstClose) return

        // Teardown in dependency order: socket, callback, subsystem, service binding. The factory
        // socket subsystem was unused before this stream, so deinitialize only what we own.
        val index = socketIndex
        if (index != INDEX_UNSET) runCatching { link.socketDisconnect(index) }
        runCatching { link.unregisterSocketDataCallback() }
        if (ownsSocketSubsystem) runCatching { link.socketDenit() }
        runCatching { link.close() }
    }

    // ---------------------------------------------------------------- data path

    private fun offer(data: ByteArray) {
        if (data.isEmpty()) return
        synchronized(lock) {
            if (closed || failure != null) return
            while (pendingBytes >= MAX_PENDING_BYTES) {
                // Bound the queue; drop the oldest chunk rather than block a binder thread.
                pending.pollFirst()?.let { pendingBytes -= it.size } ?: break
            }
            pending.addLast(data)
            pendingBytes += data.size
            lock.notifyAll()
        }
    }

    private fun takePendingLocked(maxBytes: Int): ByteArray? {
        val chunk = pending.pollFirst() ?: return null
        pendingBytes -= chunk.size
        if (chunk.size <= maxBytes) return chunk

        val head = chunk.copyOf(maxBytes)
        val tail = chunk.copyOfRange(maxBytes, chunk.size)
        pending.addFirst(tail)
        pendingBytes += tail.size
        return head
    }

    private fun socketStillConnected(): Boolean {
        val index = socketIndex
        if (index == INDEX_UNSET) return false
        return try {
            val info = link.socketGetDeviceInfo(index)
            info != null && info.isConnected
        } catch (error: android.os.RemoteException) {
            fail(IOException("Anw socket state read failed", error))
            false
        } catch (error: IOException) {
            false
        }
    }

    private fun fail(io: IOException) {
        synchronized(lock) {
            if (failure == null) failure = io
            lock.notifyAll()
        }
    }

    private fun deadlineAfter(timeoutMillis: Long): Long {
        val now = System.nanoTime()
        val delta = timeoutMillis * NANOS_PER_MILLISECOND
        return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
    }

    /** Asynchronous connect: request, then wait for the service's CONNECT_STATUS broadcast
     * (transaction 228's native nConnectStatus uses a vendor encoding that is not 1 on this
     * firmware, so polling alone can never observe "connected"). */
    private fun connectSocket(context: Context, address: String, onProgress: (String) -> Unit) {
        val index = link.socketConnect(address, iap2UuidGuidBytes())
        if (index < 0) throw IOException("Anw socket connect rejected code=$index")
        socketIndex = index
        onProgress("anw socket connect requested index=$index address=$address")

        // AnwSdkService broadcasts Profile/Value/Index/Address on every socket state change;
        // Value 1 = connected, 0 = disconnected, -1 linklost, -2 page timeout.
        val connected = AtomicBoolean(false)
        val settled = CountDownLatch(1)
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                intent ?: return
                if (intent.getIntExtra("Index", -1) != index) return
                val remote = intent.getStringExtra("Address") ?: return
                if (!remote.equals(address, ignoreCase = true)) return
                when (intent.getIntExtra("Value", -1)) {
                    1 -> {
                        connected.set(true)
                        settled.countDown()
                    }
                    0, -1, -2 -> settled.countDown()
                }
            }
        }
        val appContext = context.applicationContext
        appContext.registerReceiver(
            receiver as android.content.BroadcastReceiver,
            IntentFilter(CONNECT_STATUS_ACTION),
        )
        try {
            val deadline = System.nanoTime() + CONNECT_TIMEOUT_NANOS
            while (true) {
                if (settled.await(CONNECT_POLL_MILLIS, TimeUnit.MILLISECONDS)) break
                // Fallback path for firmware without the broadcast.
                val info = link.socketGetDeviceInfo(index)
                if (info != null && info.isConnected &&
                    info.address.equals(address, ignoreCase = true)
                ) {
                    connected.set(true)
                    break
                }
                if (System.nanoTime() >= deadline) {
                    throw IOException(
                        "Anw socket did not connect to $address " +
                            "(status=${info?.connectStatus ?: "none"} addr=${info?.address ?: "none"})",
                    )
                }
            }
            if (!connected.get()) {
                throw IOException("Anw socket to $address disconnected before iAP2 started")
            }
            onProgress("anw socket connected index=$index address=$address")
        } finally {
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    companion object {
        /**
         * Binds the factory service, initializes its (unused) socket subsystem, registers the data
         * callback, connects an RFCOMM socket to [address] for the iAP2 UUID and waits for the
         * asynchronous connect to complete. An already-probed [existingLink] can be transferred
         * to this stream; ownership is always consumed and closed by this method/stream.
         */
        fun connect(
            context: Context,
            address: String,
            onProgress: (String) -> Unit = {},
            existingLink: AnwPhoneLinkClient? = null,
        ): AnwRfcommDuplexStream {
            val link = existingLink ?: AnwPhoneLinkClient.bind(context)
                ?: throw IOException("The HSAE Anw Bluetooth service is unavailable")
            var subsystemOwned = false
            var stream: AnwRfcommDuplexStream? = null
            try {
                // bind() already checks Bluetooth power to validate the factory service.
                subsystemOwned = !link.getSocketInitStatus()
                if (subsystemOwned) {
                    val init = link.socketInit()
                    // AnwSdkService returns 1 on success (and when already initialized),
                    // -1 on failure — see its ANWBT_SocketInit source.
                    if (init < 0) throw IOException("Anw socket init failed code=$init")
                    onProgress("anw socket subsystem initialized")
                } else {
                    onProgress("anw socket subsystem already initialized")
                }
                stream = AnwRfcommDuplexStream(link, subsystemOwned)
                stream.connectSocket(context, address, onProgress)
                return stream
            } catch (error: Throwable) {
                stream?.close()
                if (stream == null && subsystemOwned) runCatching { link.socketDenit() }
                runCatching { link.close() }
                throw error
            }
        }

        /**
         * DiPlay's iAP2 UUID `00000000-deca-fade-deca-deafdecacafe` in the byte order the factory
         * API expects: GUID order, i.e. byte groups 0-3, 4-5 and 6-7 reversed relative to the
         * network order produced by `UUID.fromString`.
         */
        fun iap2UuidGuidBytes(): ByteArray {
            val network = byteArrayOf(
                0x00, 0x00, 0x00, 0x00,
                0xde.toByte(), 0xca.toByte(),
                0xfa.toByte(), 0xde.toByte(),
                0xde.toByte(), 0xca.toByte(),
                0xde.toByte(), 0xaf.toByte(),
                0xde.toByte(), 0xca.toByte(), 0xca.toByte(), 0xfe.toByte(),
            )
            return network.copyOf().also { guid ->
                guid.reverse(0, 4)
                guid.reverse(4, 6)
                guid.reverse(6, 8)
            }
        }

        private const val INDEX_UNSET = -1
        private const val CONNECT_TIMEOUT_NANOS = 15_000_000_000L
        private const val CONNECT_POLL_MILLIS = 200L

        /** AnwSdkService socket state broadcast (MangerConstant.MSG_ACTION_CONNECT_STATUS). */
        private const val CONNECT_STATUS_ACTION = "android.bluetooth.anw.action.CONNECT_STATUS"
        private const val WATCHDOG_INTERVAL_NANOS = 2_000_000_000L
        private const val WRITE_CHUNK_BYTES = 0x800
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val MAX_PENDING_BYTES = 65_536
        private val EMPTY = ByteArray(0)
    }
}
