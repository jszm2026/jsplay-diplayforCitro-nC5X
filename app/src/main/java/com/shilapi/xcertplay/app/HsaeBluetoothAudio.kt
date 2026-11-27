package com.shilapi.xcertplay.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * HSAE 工厂蓝牙栈的 A2DP（媒体音频）通道控制：断开/恢复与 iPhone 之间的蓝牙音乐通道。
 *
 * 背景：这批车机没有标准 Android 蓝牙栈，蓝牙由 com.hsae.bluetoothservice 驱动，
 * 对外发布 `com.hsae.bluetoothsdk.IBluetoothManager` binder。iPhone 与车机蓝牙的
 * A2DP 一旦连通，CarPlay 会话就把媒体音频交给蓝牙（SBC/AAC，~256kbps）而不建立
 * WiFi 音频流——想要 CarPlay 音频（48kHz AAC-LC 高音质），只需断开 A2DP 而保留
 * HFP（免提通话、Siri 语音、CarPlay 无线握手的承载）完全不受影响。
 *
 * Transaction codes 读自车机 /system/framework/bluetoothsdk.jar 反汇编
 * （IAnwPhoneLink 同款流程，非稳定公共 ABI，换固件需重读）：
 *   connectA2dp(String address) = 19
 *   disConnectA2dp()            = 23
 */
object HsaeBluetoothAudio {

    private const val DESCRIPTOR = "com.hsae.bluetoothsdk.IBluetoothManager"
    private const val TRANSACTION_CONNECT_A2DP = 19
    private const val TRANSACTION_DISCONNECT_A2DP = 23
    private const val SERVICE_PACKAGE = "com.hsae.bluetoothservice"
    private const val SERVICE_CLASS = "com.hsae.bluetoothservice.service.BluetoothService"

    /** 结果：[ok]=binder 调用是否成功（含远端无异常）；[detail] 给日志的说明。 */
    data class Result(val ok: Boolean, val detail: String)

    /**
     * 后台线程执行：断开 A2DP（媒体音频）。HFP/HSP（电话）不受影响。
     * 车机蓝牙服务不可达时返回失败但绝不抛异常——音频路由保持现状（蓝牙出声）。
     */
    fun disconnectA2dp(context: Context, timeoutMillis: Long = 4_000L): Result =
        transact(context, TRANSACTION_DISCONNECT_A2DP) { /* no arguments */ }

    /**
     * 后台线程执行：向指定蓝牙地址重连 A2DP（切回蓝牙音频模式时恢复媒体音乐通道）。
     */
    fun connectA2dp(context: Context, address: String, timeoutMillis: Long = 6_000L): Result =
        transact(context, TRANSACTION_CONNECT_A2DP, timeoutMillis) { it.writeString(address) }

    /** 统一的 bind → transact → readException → unbind 流程。 */
    private fun transact(
        context: Context,
        code: Int,
        timeoutMillis: Long = 4_000L,
        writeArgs: (Parcel) -> Unit = {},
    ): Result {
        val appContext = context.applicationContext
        val latch = CountDownLatch(1)
        var binder: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binder = service
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                binder = null
            }
        }
        val intent = Intent().setComponent(ComponentName(SERVICE_PACKAGE, SERVICE_CLASS))
        val bound = try {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (error: Exception) {
            return Result(false, "bind HSAE bluetooth service failed: ${error.javaClass.simpleName}")
        }
        if (!bound) {
            return Result(false, "HSAE bluetooth service not bindable (component missing)")
        }
        try {
            if (!latch.await(timeoutMillis, TimeUnit.MILLISECONDS) || binder == null) {
                return Result(false, "HSAE bluetooth service connect timeout")
            }
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                writeArgs(data)
                val sent = binder!!.transact(code, data, reply, 0)
                if (!sent) {
                    return Result(false, "transact code=$code rejected")
                }
                reply.readException()
                return Result(true, "transact code=$code ok")
            } finally {
                data.recycle()
                reply.recycle()
            }
        } catch (error: Exception) {
            return Result(false, "transact code=$code failed: ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            runCatching { appContext.unbindService(connection) }
        }
    }
}
