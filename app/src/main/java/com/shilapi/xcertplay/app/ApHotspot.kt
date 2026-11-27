package com.shilapi.xcertplay.app

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.text.TextUtils

/**
 * API 19 反射控制车机自带热点。
 *
 * 多路兼容实现移植自 c5xlauncher 在 E43（HSAE D531MC / Android 4.4.3）上实测可用的
 * setCarWifiApEnabledSafely：先关 STA、先写配置再开 AP、setWifiApEnabled 双签名、
 * 状态判定兼容厂商魔改值。
 */
object ApHotspot {
    private const val WIFI_AP_STATE_DISABLING = 10
    private const val WIFI_AP_STATE_DISABLED = 11
    private const val WIFI_AP_STATE_ENABLING = 12
    private const val WIFI_AP_STATE_ENABLED = 13
    private const val WIFI_AP_STATE_FAILED = 14

    const val STATE_ENABLED = WIFI_AP_STATE_ENABLED

    data class ApConfig(val ssid: String, val passphrase: String)

    private fun wifi(context: Context): WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    /** 当前热点状态码；读不到返回 null。 */
    fun apState(context: Context): Int? {
        val manager = wifi(context) ?: return null
        runCatching {
            WifiManager::class.java.getMethod("isWifiApEnabled").invoke(manager) as? Boolean
        }.onSuccess { enabled ->
            if (enabled != null) {
                return if (enabled) WIFI_AP_STATE_ENABLED else WIFI_AP_STATE_DISABLED
            }
        }
        return runCatching {
            WifiManager::class.java.getMethod("getWifiApState").invoke(manager) as Int
        }.getOrNull()
    }

    fun isApEnabled(context: Context): Boolean {
        val state = apState(context) ?: return false
        // Android 4.x 常量 13；部分厂商魔改固件直接返回 3。
        return state == WIFI_AP_STATE_ENABLED || state == 3
    }

    /** STA（普通 Wi-Fi 连网）是否开启。 */
    fun isStaEnabled(context: Context): Boolean {
        val manager = wifi(context) ?: return false
        return runCatching { manager.isWifiEnabled }.getOrDefault(false)
    }

    /** 关闭 STA。单射频设备上 STA 与热点互斥，开热点前必须先关。 */
    fun disableSta(context: Context): Boolean {
        val manager = wifi(context) ?: return false
        return runCatching { manager.setWifiEnabled(false) }.fold({ true }, { false })
    }

    /** 读取系统当前保存的热点配置（SSID/密码）。 */
    fun readConfig(context: Context): ApConfig? {
        val manager = wifi(context) ?: return null
        return runCatching {
            val config = WifiManager::class.java.getMethod("getWifiApConfiguration")
                .invoke(manager) as? WifiConfiguration ?: return null
            val ssid = unquote(config.SSID) ?: return null
            if (ssid.isBlank()) return null
            val passphrase = unquote(config.preSharedKey)
                ?.takeUnless { it.isEmpty() || it.all { char -> char == '*' } }
                .orEmpty()
            ApConfig(ssid, passphrase)
        }.getOrNull()
    }

    /** 写入热点配置（WPA/WPA2 兼容，全套加密位先清后设——launcher 同款）。
     *
     * SSID/密码不带引号：c5xlauncher 在 E43 上实测通过的写法（HSAE 固件按原值接收）；
     * AOSP 规范的带引号格式在这台固件上未验证，不冒这个险。 */
    fun writeConfig(context: Context, ssid: String, passphrase: String): Boolean {
        val manager = wifi(context) ?: return false
        return try {
            val config = WifiConfiguration().apply {
                SSID = if (TextUtils.isEmpty(ssid)) "JSZM-WIFI" else ssid
                preSharedKey = if (TextUtils.isEmpty(passphrase)) "88888888" else passphrase
                hiddenSSID = false
                status = WifiConfiguration.Status.ENABLED
                allowedAuthAlgorithms.clear()
                allowedProtocols.clear()
                allowedKeyManagement.clear()
                allowedPairwiseCiphers.clear()
                allowedGroupCiphers.clear()
                allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
                allowedProtocols.set(WifiConfiguration.Protocol.RSN)
                allowedProtocols.set(WifiConfiguration.Protocol.WPA)
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP)
                allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP)
                allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP)
                allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP)
            }
            WifiManager::class.java
                .getMethod("setWifiApConfiguration", WifiConfiguration::class.java)
                .invoke(manager, config)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 开启热点（launcher 同款多路兼容：关 STA → 写配置 → 双签名 setWifiApEnabled → 兜底停用旧 AP）。
     */
    fun setApEnabled(context: Context, enable: Boolean): Boolean {
        val manager = wifi(context) ?: return false
        // 老安卓开热点前通常需要关闭普通 WiFi，否则 setWifiApEnabled 可能失败。
        if (enable) {
            try {
                if (manager.isWifiEnabled) {
                    manager.setWifiEnabled(false)
                    try {
                        Thread.sleep(180)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            } catch (_: Throwable) {
            }
        }
        if (!enable) {
            try {
                val setAp = WifiManager::class.java.getMethod(
                    "setWifiApEnabled", WifiConfiguration::class.java, Boolean::class.javaPrimitiveType,
                )
                val result = setAp.invoke(manager, null, false)
                if (result is Boolean) return result || !isApEnabled(context)
                if (!isApEnabled(context)) return true
            } catch (_: Throwable) {
            }
            try {
                val setAp = WifiManager::class.java.getMethod(
                    "setWifiApEnabled", Boolean::class.javaPrimitiveType,
                )
                val result = setAp.invoke(manager, false)
                if (result is Boolean) return result || !isApEnabled(context)
                if (!isApEnabled(context)) return true
            } catch (_: Throwable) {
            }
            for (name in arrayOf("stopSoftAp", "stopWifiAp", "disableWifiAp")) {
                try {
                    val m = WifiManager::class.java.getMethod(name)
                    val result = m.invoke(manager)
                    if (result is Boolean) {
                        if (result || !isApEnabled(context)) return true
                    } else if (!isApEnabled(context)) {
                        return true
                    }
                } catch (_: Throwable) {
                }
            }
            return !isApEnabled(context)
        }
        // enable：先写配置再开（部分 HSAE 固件只认其中一路）
        try {
            val setConfig = WifiManager::class.java.getMethod(
                "setWifiApConfiguration", WifiConfiguration::class.java,
            )
            setConfig.invoke(manager, readApConfigForEnable(context))
        } catch (_: Throwable) {
        }
        try {
            val setAp = WifiManager::class.java.getMethod(
                "setWifiApEnabled", WifiConfiguration::class.java, Boolean::class.javaPrimitiveType,
            )
            val result = setAp.invoke(manager, readApConfigForEnable(context), true)
            if (result is Boolean) return result || isApEnabled(context)
            return isApEnabled(context)
        } catch (_: Throwable) {
        }
        try {
            val setAp = WifiManager::class.java.getMethod(
                "setWifiApEnabled", Boolean::class.javaPrimitiveType,
            )
            val result = setAp.invoke(manager, true)
            if (result is Boolean) return result || isApEnabled(context)
            return isApEnabled(context)
        } catch (_: Throwable) {
        }
        return isApEnabled(context)
    }

    /** 开 AP 用系统现有配置（不覆盖）；读不到时返回 null 由系统沿用默认。 */
    private fun readApConfigForEnable(context: Context): WifiConfiguration? = try {
        WifiManager::class.java.getMethod("getWifiApConfiguration")
            .invoke(wifi(context)) as? WifiConfiguration
    } catch (_: Throwable) {
        null
    }

    /** 轮询等待热点到达目标状态；返回最终是否处于目标态。 */
    fun awaitState(context: Context, target: Int, timeoutMillis: Long): Boolean {
        val deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L
        while (true) {
            if (isApEnabled(context)) return true
            if (System.nanoTime() >= deadlineNanos) return isApEnabled(context)
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return isApEnabled(context)
            }
        }
    }

    private fun unquote(value: String?): String? {
        if (value == null) return null
        return if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            value.substring(1, value.length - 1)
        } else {
            value
        }
    }
}
