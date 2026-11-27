package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Looper
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Infrastructure ("same network") wireless CarPlay: the head unit stays in station mode and
 * shares an external Wi-Fi network with the iPhone. No SoftAP is created and Wi-Fi state is
 * never modified; this manager only reads the live STA connection (SSID, BSSID, IP, channel)
 * so the iAP2 0x5703/0x4301 payloads can point the iPhone at the already-joined network.
 *
 * The passphrase is read from the system's saved Wi-Fi configuration when the platform allows
 * it (plain text on API 19); otherwise the caller-supplied fallback from the UI is used.
 */
class SameNetworkManager(
    context: Context,
    /** Optional guard: fail early when the car is not on this SSID. Null = use whatever is joined. */
    private val expectedSsid: String?,
    /** UI fallback used when the saved Wi-Fi password cannot be read from the system. */
    private val passphraseFallback: String,
    /** UI fallback channel used when the live channel cannot be observed from scan results. */
    private val channelFallback: Int,
    private val onDiagnostic: (String) -> Unit = {},
) : WirelessHotspotManager {
    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        ?: throw IllegalStateException("WifiManager is unavailable")

    @Volatile
    private var closed = false

    init {
        require(expectedSsid == null || expectedSsid.isNotBlank()) {
            "expectedSsid must be null or not blank"
        }
        require('\u0000' !in (expectedSsid ?: "")) { "expectedSsid must not contain U+0000" }
        require('\u0000' !in passphraseFallback) {
            "passphraseFallback must not contain U+0000"
        }
        require(
            passphraseFallback.isEmpty() || passphraseFallback.length in 8..63,
        ) { "passphraseFallback must be empty or between 8 and 63 characters" }
        require(channelFallback in 0..196) { "channelFallback must be 0 or in 1..196" }
    }

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "SameNetworkManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }

        val deadlineNanos = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        var lastReason = "Wi-Fi is not connected to any network"
        while (true) {
            check(!closed) { "SameNetworkManager is closed" }
            val info = try {
                wifiManager.connectionInfo
            } catch (_: SecurityException) {
                null
            }
            val ssid = info?.let { unquote(it.ssid) }
            if (info != null && !ssid.isNullOrBlank() && ssid != UNKNOWN_SSID && info.ipAddress != 0) {
                if (expectedSsid != null && ssid != expectedSsid) {
                    throw IOException(
                        "Same-network mode expects SSID '$expectedSsid' but the head unit is " +
                            "connected to '$ssid'",
                    )
                }
                val station = findStationInterface(info.ipAddress)
                if (station != null) {
                    return buildInfo(info, ssid, station)
                }
                lastReason = "the station interface holding ${describeIp(info.ipAddress)} was not found"
            } else if (info != null && info.ipAddress == 0) {
                lastReason = "Wi-Fi '$ssid' has no IP address yet (still joining?)"
            }

            val remaining = deadlineNanos - System.nanoTime()
            if (remaining <= 0) {
                throw IOException(
                    "Timed out after ${timeoutMillis}ms in same-network mode: $lastReason. " +
                        "Connect the head unit to the same Wi-Fi network as the iPhone and retry.",
                )
            }
            sleep(minOf(remaining, POLL_NANOS))
        }
    }

    override fun close() {
        // Infrastructure mode: nothing is owned by this manager, so there is nothing to undo.
        closed = true
    }

    private fun buildInfo(
        info: WifiInfo,
        ssid: String,
        station: StationInterface,
    ): WirelessHotspotInfo {
        val saved = findSavedConfiguration(ssid)
        val passphrase = saved?.passphrase ?: passphraseFallback
        val security = saved?.security
            ?: if (passphrase.isEmpty()) Iap2WirelessSecurity.NONE else Iap2WirelessSecurity.WPA_WPA2
        if (security != Iap2WirelessSecurity.NONE && passphrase.isEmpty()) {
            throw IOException(
                "Same-network mode could not read the password of '$ssid' from the system " +
                    "and no fallback password was entered. Type the Wi-Fi password in the " +
                    "passphrase field and retry.",
            )
        }

        val scanFrequency = frequencyFromScanResult(info.bssid)
        val observedChannel = scanFrequency?.let(::wifiFrequencyMhzToChannel)
        val channelSource = when {
            observedChannel != null -> "observed"
            channelFallback > 0 -> "configured"
            else -> "unknown"
        }
        val channel = observedChannel ?: channelFallback
        val frequencyMHz = scanFrequency
            ?: channel.takeIf { it > 0 }?.let { wifiChannelToFrequencyMhz(it, channelToBand(it)) }

        val passphraseSource = when {
            saved?.passphrase != null -> "system"
            passphraseFallback.isNotEmpty() -> "ui"
            security == Iap2WirelessSecurity.NONE -> "open"
            else -> "unavailable"
        }
        onDiagnostic(
            "Same-network config ssid=$ssid security=$security " +
                "channelKnown=${channel > 0} channelSource=$channelSource " +
                "passphraseSource=$passphraseSource iface=${station.name} host=${station.address.hostAddress}",
        )

        return WirelessHotspotInfo(
            ssid = ssid,
            passphrase = passphrase,
            security = security,
            channel = channel,
            frequencyMHz = frequencyMHz,
            bssid = station.hardwareAddress,
            interfaceName = station.name,
            hostAddress = station.address,
            bandLabel = frequencyMHz?.let(::bandLabel) ?: "Auto",
            backend = WirelessHotspotBackend.SAME_NETWORK,
        )
    }

    /** Keep the security type even when Android masks the saved password. */
    private fun findSavedConfiguration(ssid: String): SavedNetwork? {
        val configurations = try {
            wifiManager.configuredNetworks
        } catch (_: SecurityException) {
            null
        } ?: return null
        val match = configurations.firstOrNull { unquote(it.SSID) == ssid } ?: return null
        val passphrase = unquote(match.preSharedKey)
            ?.takeIf { it.isNotEmpty() && !it.all { c -> c == '*' } }
        return SavedNetwork(passphrase, securityOf(match))
    }

    private fun securityOf(configuration: WifiConfiguration): Iap2WirelessSecurity {
        val keyManagement = configuration.allowedKeyManagement ?: return Iap2WirelessSecurity.NONE
        return when {
            keyManagement.get(WifiConfiguration.KeyMgmt.NONE) &&
                !keyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK) &&
                !keyManagement.get(WifiConfiguration.KeyMgmt.WPA2_PSK) &&
                !keyManagement.get(WifiConfiguration.KeyMgmt.SAE) ->
                Iap2WirelessSecurity.NONE
            else -> Iap2WirelessSecurity.WPA_WPA2
        }
    }

    /** Channel observation via the scan entry for the currently associated BSSID. */
    private fun frequencyFromScanResult(bssid: String?): Int? {
        if (bssid.isNullOrBlank() || bssid == "00:00:00:00:00:00" || bssid == "02:00:00:00:00:00") {
            return null
        }
        val results = try {
            wifiManager.scanResults
        } catch (_: SecurityException) {
            null
        } ?: return null
        val frequencies = results.asSequence()
            .filter { it.BSSID.equals(bssid, ignoreCase = true) && it.frequency > 0 }
            .map(ScanResult::frequency)
            .distinct()
            .toList()
        return frequencies.singleOrNull()
    }

    /**
     * The interface carrying the station connection. Prefer the interface whose IPv4 address
     * equals [WifiInfo.getIpAddress]; fall back to any up "wlan"-prefixed interface that is
     * neither P2P nor an AP.
     */
    private fun findStationInterface(expectedIpAddress: Int): StationInterface? {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (_: SocketException) {
            null
        } ?: return null
        val expected = inet4FromLittleEndianInt(expectedIpAddress)
        val candidates = Collections.list(interfaces)
            .asSequence()
            .filter { interfaceCandidate(it) }
            .mapNotNull { networkInterface ->
                stationAddress(networkInterface, expected)?.let { address ->
                    StationInterface(
                        name = networkInterface.name,
                        address = address,
                        hardwareAddress = runCatching { networkInterface.hardwareAddress }
                            .getOrNull()
                            ?.toMacAddressString()
                            ?.takeUnless { it == "02:00:00:00:00:00" || it == "00:00:00:00:00:00" },
                    )
                }
            }
            .toList()
        return candidates.firstOrNull { it.address == expected }
            ?: candidates.firstOrNull { it.address is Inet4Address }
            ?: candidates.firstOrNull()
    }

    private fun interfaceCandidate(networkInterface: NetworkInterface): Boolean = try {
        networkInterface.isUp &&
            !networkInterface.isLoopback &&
            networkInterface.name.startsWith("wlan") &&
            !networkInterface.name.contains("p2p", ignoreCase = true) &&
            !networkInterface.name.startsWith("ap")
    } catch (_: SocketException) {
        false
    }

    /** IPv4 first: the iPhone connects back over the LAN, so the routable address wins. */
    private fun stationAddress(
        networkInterface: NetworkInterface,
        expected: InetAddress?,
    ): InetAddress? {
        val addresses = Collections.list(networkInterface.inetAddresses)
        (addresses.firstOrNull { it == expected })?.let { return it }
        return addresses.firstOrNull {
            it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress &&
                !it.isAnyLocalAddress && !it.isMulticastAddress
        } ?: wirelessHostAddress(addresses, networkInterface.index)
    }

    private fun describeIp(littleEndianIp: Int): String {
        val address = inet4FromLittleEndianInt(littleEndianIp) ?: return "unknown"
        return address.hostAddress ?: "unknown"
    }

    private fun bandLabel(frequencyMHz: Int): String = when (frequencyMHz) {
        in 2400..2500 -> "2.4 GHz"
        in 5150..5895 -> "5 GHz"
        in 5925..7125 -> "6 GHz"
        else -> "Unknown band"
    }

    /** SoftApConfiguration band constant for a channel number, for frequency derivation. */
    private fun channelToBand(channel: Int): Int? = when (channel) {
        in 1..14 -> 1
        in 32..177 -> 2
        else -> null
    }

    private fun unquote(value: String?): String? {
        if (value == null) return null
        return if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            value.substring(1, value.length - 1)
        } else {
            value
        }
    }

    private fun ByteArray.toMacAddressString(): String =
        joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun sleep(nanos: Long) {
        try {
            TimeUnit.NANOSECONDS.sleep(nanos)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting for the station connection", interrupted)
        }
    }

    private class SavedNetwork(
        val passphrase: String?,
        val security: Iap2WirelessSecurity,
    )

    private class StationInterface(
        val name: String,
        val address: InetAddress,
        val hardwareAddress: String?,
    )

    private companion object {
        const val UNKNOWN_SSID = "<unknown ssid>"
        const val NANOS_PER_MILLISECOND = 1_000_000L
        val POLL_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(500)
    }
}

private fun inet4FromLittleEndianInt(value: Int): InetAddress? = try {
    InetAddress.getByAddress(
        byteArrayOf(
            (value and 0xff).toByte(),
            (value shr 8 and 0xff).toByte(),
            (value shr 16 and 0xff).toByte(),
            (value shr 24 and 0xff).toByte(),
        ),
    )
} catch (_: Exception) {
    null
}
