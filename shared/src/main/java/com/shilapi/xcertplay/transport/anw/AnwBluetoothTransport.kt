package com.shilapi.xcertplay.transport.anw

import android.content.Context
import java.io.IOException

/**
 * Wireless-bootstrap orchestration over the HSAE factory Bluetooth stack (Anw SDK).
 *
 * Selection policy mirrors `CarPlayController.selectWirelessBluetoothDevice`: a saved address
 * wins and must still be paired, otherwise a name containing "iPhone" wins, otherwise a single
 * paired device is accepted. The connected-state refinement of the standard path is approximated
 * by logging; the factory API does not expose profile-level connection state cheaply.
 */
object AnwBluetoothTransport {

    /** Successful service probe and the paired list used for target selection. */
    data class Probe(
        val link: AnwPhoneLinkClient,
        val pairedDevices: List<AnwPhoneLinkClient.PairedDevice>,
    )

    /** Target chosen from the factory paired list. */
    data class WirelessTarget(
        val address: String,
        val name: String?,
        val localBluetoothMac: String?,
    )

    /**
     * Read-only probe used before the wireless run. Bind validates the service by reading
     * Bluetooth power; this reads the paired list once and passes it to target selection. A
     * failed list read returns null so the caller retains its standard-Bluetooth fallback.
     */
    fun probe(context: Context, onProgress: (String) -> Unit = {}): Probe? {
        val link = try {
            AnwPhoneLinkClient.bind(context)
        } catch (error: Exception) {
            onProgress("anw probe failed (${error.javaClass.simpleName}: ${error.message})")
            return null
        }
        if (link == null) {
            onProgress("anw probe: bind failed — factory Anw service not reachable")
            return null
        }
        return try {
            val paired = link.getPairedList()
            onProgress("anw probe: factory service ready paired=${paired.size}")
            Probe(link, paired)
        } catch (error: Exception) {
            onProgress(
                "anw probe failed after bind (${error.javaClass.simpleName}: ${error.message}); " +
                    "falling back to standard Bluetooth",
            )
            runCatching { link.close() }
            null
        }
    }

    /**
     * Picks the iPhone to bootstrap from, using the factory paired list. [preferredAddress] is
     * the last successfully connected phone (or the user's saved choice); when it no longer
     * matches a paired device the selection falls back to the automatic rules instead of
     * failing, so swapping phones never bricks the link.
     */
    fun selectTarget(
        probe: Probe,
        preferredAddress: String?,
        onProgress: (String) -> Unit = {},
    ): WirelessTarget {
        val localMac = try {
            probe.link.readLocalAddr()
        } catch (_: Throwable) {
            null
        }
        val paired = probe.pairedDevices
        if (paired.isEmpty()) {
            throw IOException(
                "车机蓝牙里还没有已配对的手机：请先在车机蓝牙设置里完成 iPhone 配对",
            )
        }
        preferredAddress?.let { selected ->
            val match = paired.firstOrNull { it.address.equals(selected, ignoreCase = true) }
            if (match != null) {
                onProgress("anw preferred target matched name=${match.name ?: "unknown"} address=${match.address}")
                return WirelessTarget(match.address, match.name, localMac)
            }
            // 上次连接的手机不在配对列表（换机/删配对）：降级自动选择，不死锁。
            onProgress(
                "anw preferred target ${selected} no longer paired; falling back to automatic selection",
            )
        }
        val iPhones = paired.filter { it.name?.contains("iPhone", ignoreCase = true) == true }
        val chosen = when {
            iPhones.size == 1 -> iPhones.single()
            iPhones.size > 1 -> throw IOException(
                "车机蓝牙里有多台已配对 iPhone：" +
                    iPhones.joinToString { "${it.name ?: "iPhone"} (${it.address})" } +
                    "。请在车机蓝牙设置里删除不用的那台后重试（已连过一次的手机会被自动优先）",
            )
            paired.size == 1 -> paired.single()
            else -> throw IOException(
                "车机蓝牙配对列表里无法确定唯一 iPhone：请只保留一台 iPhone 的配对后重试",
            )
        }
        onProgress("anw selected target name=${chosen.name ?: "unknown"} address=${chosen.address}")
        return WirelessTarget(chosen.address, chosen.name, localMac)
    }
}
