package com.shilapi.xcertplay.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.shilapi.xcertplay.AirPlayPersistence

/** 开机自启：用户在验证壳里勾选后，重启自动拉起 MainActivity。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!AirPlayPersistence.loadAutoStartOnBoot(context)) return
        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        try {
            context.startActivity(launch)
            Log.i(TAG, "Boot auto-start launched MainActivity")
        } catch (error: RuntimeException) {
            Log.w(TAG, "Boot auto-start could not launch MainActivity", error)
        }
    }

    private companion object {
        const val TAG = "xcertplay-boot"
    }
}
