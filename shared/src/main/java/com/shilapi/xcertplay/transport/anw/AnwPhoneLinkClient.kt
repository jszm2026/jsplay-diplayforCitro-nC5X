package com.shilapi.xcertplay.transport.anw

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Raw binder client for the HSAE factory Bluetooth stack service (Anw SDK).
 *
 * The HSAE D531-family head units ship without a standard Android Bluetooth stack. The Bluetooth
 * chip hangs off a private UART and is driven by `com.anwsdk.service/.AnwSdkService`, which
 * publishes an `IAnwPhoneLink` binder interface. This class speaks that interface directly so the
 * iAP2 stack can run its RFCOMM bootstrap through the factory stack, exactly like the standard
 * `BluetoothSocket` path would.
 *
 * Transaction codes and parcel layouts were read from the decompiled
 * `com.anwsdk.service.IAnwPhoneLink` of an HSAE D531MC platform build (E43 / V90E00, 2022,
 * Android 4.4.3) and match the codes independently reported for HSAE CN202SR (Android 8.0):
 *
 *   GetBTPowerStatus               = 3
 *   ReadLocalAddr                  = 7
 *   GetPairedList                  = 16   (reply: result, count, NAMES first, ADDRESSES second, cod;
 *                                           result 1 = success, -1 = failure — AnwSdkService convention)
 *   SocketInit                     = 222
 *   SocketListen                   = 223
 *   SocketDenit                    = 224
 *   SocketConnect                  = 225
 *   SocketDisconnect               = 226
 *   SocketWrite                    = 227
 *   SocketGetDeviceInfobyIndexEx   = 228
 *   RegistrySocketDataCallback     = 231
 *   UnRegistrySocketDataCallback   = 232
 *   GetSocketInitStatus            = 233
 *
 * The data callback (`IAnwSocketDataCallBack`) delivers `SocketDataIND(index, data, length)`
 * on transaction 1.
 *
 * These numbers come from specific firmware builds; they are not a stable public ABI. If a
 * future HSAE build reorders them, re-read the service APK before shipping.
 */
class AnwPhoneLinkClient private constructor(
    private val binder: IBinder,
    private val connection: ServiceConnection,
    private val context: Context,
) : Closeable {

    /** One entry of the factory paired-device list. */
    data class PairedDevice(val name: String?, val address: String, val cod: Int)

    /** Socket connection status snapshot from transaction 228. */
    data class SocketInfo(val address: String?, val channel: Int, val connectStatus: Int) {
        val isConnected: Boolean get() = connectStatus == CONNECT_STATUS_CONNECTED
    }

    /** Receiver for socket data; called on a binder thread, must not block. */
    fun interface SocketDataListener {
        fun onSocketData(index: Int, data: ByteArray)
    }

    private val transactLock = ReentrantLock(true)
    private var closed = false
    private var callbackRegistered = false

    /** Binder stub the service pushes socket data into. */
    private val dataCallback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                TRANSACTION_SOCKET_DATA_IND -> {
                    data.enforceInterface(CALLBACK_DESCRIPTOR)
                    val index = data.readInt()
                    val payload = data.createByteArray()
                    data.readInt() // DataLength; payload already carries its own length
                    reply?.writeNoException()
                    payload?.let { listener?.onSocketData(index, it) }
                    return true
                }

                INTERFACE_TRANSACTION -> {
                    reply?.writeString(CALLBACK_DESCRIPTOR)
                    return true
                }

                else -> return super.onTransact(code, data, reply, flags)
            }
        }
    }

    @Volatile
    private var listener: SocketDataListener? = null

    // ---------------------------------------------------------------- lifecycle

    /**
     * Binds to `com.anwsdk.service.AnwPhoneLink`. Returns `null` when the factory service is not
     * present on this firmware, so callers can fall back to the standard Bluetooth path.
     */
    companion object {
        fun bind(context: Context, timeoutMillis: Long = BIND_TIMEOUT_MILLIS): AnwPhoneLinkClient? {
            val appContext = context.applicationContext ?: context
            val intent = Intent(SERVICE_ACTION).setPackage(SERVICE_PACKAGE)
            val latch = java.util.concurrent.CountDownLatch(1)
            val ref = java.util.concurrent.atomic.AtomicReference<IBinder?>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    ref.set(service)
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    // The factory service died; the binder will throw DeadObjectException on use.
                }
            }
            val bound = try {
                appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } catch (error: SecurityException) {
                false
            }
            if (!bound) return null
            try {
                if (!latch.await(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    appContext.unbindService(connection)
                    return null
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                appContext.unbindService(connection)
                return null
            }
            val service = ref.get() ?: run {
                appContext.unbindService(connection)
                return null
            }
            // Read-only probe: a wrong descriptor fails here instead of at the first data call.
            return try {
                val client = AnwPhoneLinkClient(service, connection, appContext)
                client.getBtPowerStatus()
                client
            } catch (error: RemoteException) {
                appContext.unbindService(connection)
                null
            }
        }

        private const val SERVICE_PACKAGE = "com.anwsdk.service"
        private const val SERVICE_ACTION = "com.anwsdk.service.AnwPhoneLink"
        private const val DESCRIPTOR = "com.anwsdk.service.IAnwPhoneLink"
        private const val CALLBACK_DESCRIPTOR = "com.anwsdk.service.IAnwSocketDataCallBack"
        private const val PAIR_MODE_CALLBACK_DESCRIPTOR = "com.anwsdk.service.IAnwSetPairModeCallBack"

        private const val TRANSACTION_GET_BT_POWER_STATUS = 3
        private const val TRANSACTION_READ_LOCAL_ADDR = 7
        private const val TRANSACTION_GET_PAIRED_LIST = 16

        private const val TRANSACTION_SOCKET_INIT = 222
        private const val TRANSACTION_SOCKET_DENIT = 224
        private const val TRANSACTION_SOCKET_CONNECT = 225
        private const val TRANSACTION_SOCKET_DISCONNECT = 226
        private const val TRANSACTION_SOCKET_WRITE = 227
        private const val TRANSACTION_SOCKET_GET_DEVICE_INFO = 228
        private const val TRANSACTION_REGISTRY_SOCKET_DATA_CALLBACK = 231
        private const val TRANSACTION_UN_REGISTRY_SOCKET_DATA_CALLBACK = 232
        private const val TRANSACTION_GET_SOCKET_INIT_STATUS = 233

        private const val TRANSACTION_SOCKET_DATA_IND = 1
        private const val INTERFACE_TRANSACTION = 0x5f4e5446 // 'TRAN' little-endian marker

        private const val TRANSACTION_SET_PAIR_MODE = 248

        private const val SOCKET_TYPE_RFCOMM = 1
        private const val CHANNEL_ANY = 0
        private const val UUID_BYTES = 16
        private const val CONNECT_STATUS_CONNECTED = 1
        private const val BIND_TIMEOUT_MILLIS = 10_000L
    }

    override fun close() {
        transactLock.withLock {
            if (closed) return
            closed = true
            if (callbackRegistered) {
                callbackRegistered = false
                runCatching {
                    transactVoid(TRANSACTION_UN_REGISTRY_SOCKET_DATA_CALLBACK) { it.writeStrongBinder(dataCallback) }
                }
            }
        }
        runCatching { context.unbindService(connection) }
    }

    // ---------------------------------------------------------------- read-only queries

    /** Transaction 3. 1 means powered on. */
    fun getBtPowerStatus(): Int = transactInt(TRANSACTION_GET_BT_POWER_STATUS)

    /** Transaction 7. Local Bluetooth MAC as `XX:XX:XX:XX:XX:XX`. */
    fun readLocalAddr(): String? = transactLock.withLock {
        checkOpen()
        Parcel.obtain().use2 { data, reply ->
            data.writeInterfaceToken(DESCRIPTOR)
            binder.transact(TRANSACTION_READ_LOCAL_ADDR, data, reply, 0)
            reply.readException()
            reply.readString()
        }
    }

    /**
     * Transaction 16. The reply carries count, then NAMES first, ADDRESSES second, then device
     * classes; an early reversed-array read produces garbage pairings, so keep this order.
     */
    fun getPairedList(maxEntries: Int = 32): List<PairedDevice> {
        require(maxEntries > 0) { "maxEntries must be positive" }
        return transactLock.withLock {
            checkOpen()
            val count = IntArray(1)
            val names = arrayOfNulls<String>(maxEntries)
            val addresses = arrayOfNulls<String>(maxEntries)
            val cods = IntArray(maxEntries)
            Parcel.obtain().use2 { data, reply ->
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeInt(count.size)
                data.writeInt(names.size)
                data.writeInt(addresses.size)
                data.writeInt(cods.size)
                binder.transact(TRANSACTION_GET_PAIRED_LIST, data, reply, 0)
                reply.readException()
                val result = reply.readInt()
                // AnwSdkService returns 1 on success and -1 on failure (see its
                // ANWBT_DeviceInquiry), NOT the C-style 0-for-success this file used to assume.
                if (result < 0) return@use2 emptyList<PairedDevice>()
                reply.readIntArray(count)
                reply.readStringArray(names)
                reply.readStringArray(addresses)
                reply.readIntArray(cods)
            }
            val n = count[0].coerceIn(0, maxEntries)
            (0 until n).mapNotNull { i ->
                val address = addresses[i] ?: return@mapNotNull null
                PairedDevice(names[i], address, cods[i])
            }
        }
    }

    // ---------------------------------------------------------------- socket subsystem

    /**
     * Transaction 248. Puts the factory stack into (mode=1) or out of (mode=0) pairing mode so
     * the iPhone can (re)pair from its Bluetooth settings. The service only calls the native
     * setter when the callback binder is non-null; its status pushes are ignored here.
     */
    fun setPairMode(mode: Int): Int = transactLock.withLock {
        checkOpen()
        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                when (code) {
                    INTERFACE_TRANSACTION -> {
                        reply?.writeString(PAIR_MODE_CALLBACK_DESCRIPTOR)
                        return true
                    }
                    else -> {
                        // Status notification: consume silently so the service's reply stays valid.
                        reply?.writeNoException()
                        return true
                    }
                }
            }
        }
        Parcel.obtain().use2 { data, reply ->
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(mode)
            data.writeStrongBinder(callback)
            binder.transact(TRANSACTION_SET_PAIR_MODE, data, reply, 0)
            reply.readException()
            reply.readInt()
        }
    }

    /** Transaction 222. Initializes the factory socket subsystem. */
    fun socketInit(): Int = transactInt(TRANSACTION_SOCKET_INIT)

    /** Transaction 224. Deinitializes the factory socket subsystem. */
    fun socketDenit(): Int = transactInt(TRANSACTION_SOCKET_DENIT)

    /** Transaction 233. */
    fun getSocketInitStatus(): Boolean = transactInt(TRANSACTION_GET_SOCKET_INIT_STATUS) != 0

    /**
     * Transaction 225. Connects an RFCOMM socket to [address] for [uuid]. A non-negative return
     * with a valid index only means the request was accepted; completion is asynchronous and must
     * be confirmed with [socketGetDeviceInfo] (target address and connected state 1).
     *
     * [uuid] uses GUID byte order: reverse byte groups 0-3, 4-5 and 6-7 relative to the network
     * order used by `UUID.fromString`.
     */
    fun socketConnect(
        address: String,
        uuid: ByteArray,
        channel: Int = CHANNEL_ANY,
        mtu: Long = 0L,
        flag: Long = 0L,
    ): Int {
        require(uuid.size == UUID_BYTES) { "uuid must be $UUID_BYTES bytes" }
        return transactLock.withLock {
            checkOpen()
            val index = IntArray(1)
            Parcel.obtain().use2 { data, reply ->
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeInt(SOCKET_TYPE_RFCOMM)
                data.writeString(address)
                data.writeByteArray(uuid)
                data.writeInt(channel)
                data.writeLong(mtu)
                data.writeLong(flag)
                data.writeInt(index.size)
                binder.transact(TRANSACTION_SOCKET_CONNECT, data, reply, 0)
                reply.readException()
                val result = reply.readInt()
                reply.readIntArray(index)
                if (result < 0) result else index[0]
            }
        }
    }

    /** Transaction 226. */
    fun socketDisconnect(index: Int): Int = transactInt(TRANSACTION_SOCKET_DISCONNECT) { it.writeInt(index) }

    /**
     * Transaction 227. Returns the actual number of bytes written; a zero-byte write is not a
     * success and callers must retry or fail.
     */
    fun socketWrite(index: Int, data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) { "bad range" }
        if (length == 0) return 0
        return transactLock.withLock {
            checkOpen()
            val written = IntArray(1)
            val slice = if (offset == 0 && length == data.size) data else data.copyOfRange(offset, offset + length)
            Parcel.obtain().use2 { parcel, reply ->
                parcel.writeInterfaceToken(DESCRIPTOR)
                parcel.writeInt(index)
                parcel.writeByteArray(slice)
                parcel.writeInt(length)
                parcel.writeInt(written.size)
                binder.transact(TRANSACTION_SOCKET_WRITE, parcel, reply, 0)
                reply.readException()
                reply.readInt()
                reply.readIntArray(written)
            }
            written[0]
        }
    }

    /** Transaction 228. Connection status for the socket at [index]. */
    fun socketGetDeviceInfo(index: Int): SocketInfo? = transactLock.withLock {
        checkOpen()
        Parcel.obtain().use2 { data, reply ->
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(index)
            data.writeInt(1)
            binder.transact(TRANSACTION_SOCKET_GET_DEVICE_INFO, data, reply, 0)
            reply.readException()
            val result = reply.readInt()
            if (result < 0) return@use2 null
            readOneSocketInfo(reply)
        }
    }

    /** Transaction 231. Register before [socketConnect] so no early frame is lost. */
    fun registerSocketDataCallback(target: SocketDataListener) {
        transactLock.withLock {
            checkOpen()
            listener = target
            if (callbackRegistered) return
            Parcel.obtain().use2 { data, reply ->
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeStrongBinder(dataCallback)
                binder.transact(TRANSACTION_REGISTRY_SOCKET_DATA_CALLBACK, data, reply, 0)
                reply.readException()
            }
            callbackRegistered = true
        }
    }

    /** Transaction 232. */
    fun unregisterSocketDataCallback() {
        transactLock.withLock {
            if (!callbackRegistered) return
            callbackRegistered = false
            Parcel.obtain().use2 { data, reply ->
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeStrongBinder(dataCallback)
                binder.transact(TRANSACTION_UN_REGISTRY_SOCKET_DATA_CALLBACK, data, reply, 0)
                reply.readException()
            }
        }
    }

    // ---------------------------------------------------------------- internals

    private fun checkOpen() {
        if (closed) throw IOException("Anw Bluetooth service client is closed")
    }

    private fun transactInt(code: Int, write: (Parcel) -> Unit = {}): Int = transactLock.withLock {
        checkOpen()
        Parcel.obtain().use2 { data, reply ->
            data.writeInterfaceToken(DESCRIPTOR)
            write(data)
            binder.transact(code, data, reply, 0)
            reply.readException()
            reply.readInt()
        }
    }

    private fun transactVoid(code: Int, write: (Parcel) -> Unit) {
        transactLock.withLock {
            checkOpen()
            Parcel.obtain().use2 { data, reply ->
                data.writeInterfaceToken(DESCRIPTOR)
                write(data)
                binder.transact(code, data, reply, 0)
                reply.readException()
            }
        }
    }

    /** Reads one `SocketConnectInfo`: address, uuid16, channel, connectStatus. */
    private fun readOneSocketInfo(reply: Parcel): SocketInfo? {
        val present = reply.readInt()
        if (present == 0) return null
        val address = reply.readString()
        val uuidLength = reply.readInt()
        if (uuidLength > 0) reply.createByteArray()
        val channel = reply.readInt()
        val connectStatus = reply.readInt()
        return SocketInfo(address, channel, connectStatus)
    }

    /** obtain()/recycle() pair around a two-parcel call. */
    private inline fun <T> Parcel.use2(block: (Parcel, Parcel) -> T): T {
        val reply = Parcel.obtain()
        try {
            return block(this, reply)
        } finally {
            reply.recycle()
            recycle()
        }
    }
}
