package com.shilapi.xcertplay.app

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.multidex.MultiDex
import java.io.File

/** API 19 必须手动安装 multidex；首次启动时把发布包内置的 offline-mfi 凭据释放到私有目录。 */
class CarPlayApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        MultiDex.install(this)
        // noBackupFilesDir 是 API 21+，与 CarPlayController.startMfi 的回退逻辑保持一致：
        // API 19/20 用 filesDir，其余用 noBackupFilesDir。
        val baseDir = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            noBackupFilesDir
        } else {
            filesDir
        }
        provisionBundledMfi(baseDir)
    }

    /**
     * 本地认证由宿主应用负责凭据供给（LocalMfiAuthenticationClient）。
     * 发布包将 identity.pk8 与 certificate.p7b 放在 assets/offline-mfi/，
     * 首次启动时释放到应用私有目录，使 startMfi 的 LocalOffline 后端直接可用，
     * 无需 adb push。源码仓库不包含凭据文件，由构建/发布流程按需注入。
     *
     * 已有目录且文件齐全时不覆盖，保留手工替换凭据的能力。
     */
    private fun provisionBundledMfi(baseDir: File?) {
        if (baseDir == null) return
        val target = File(baseDir, "offline-mfi")
        val key = File(target, "identity.pk8")
        val cert = File(target, "certificate.p7b")
        if (key.isFile && key.length() > 0 && cert.isFile && cert.length() > 0) {
            Log.i(TAG, "offline-mfi credentials already provisioned")
            return
        }
        try {
            if (!target.isDirectory) target.mkdirs()
            copyAsset("offline-mfi/identity.pk8", key)
            copyAsset("offline-mfi/certificate.p7b", cert)
            Log.i(TAG, "offline-mfi credentials provisioned from bundled assets")
        } catch (error: Exception) {
            Log.w(TAG, "Could not provision bundled offline-mfi credentials", error)
        }
    }

    private fun copyAsset(assetPath: String, dest: File) {
        assets.open(assetPath).use { input ->
            dest.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-app"
    }
}
