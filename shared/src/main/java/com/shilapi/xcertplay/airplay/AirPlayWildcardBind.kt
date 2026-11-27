package com.shilapi.xcertplay.airplay

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Wildcard bind helper for firmware whose IPv6 stack rejects the "::" wildcard with
 * "Can't bind to a link-local address without a scope id" (observed on the HSAE E43
 * head unit running Android 4.4).
 *
 * IPv6 wildcard support is probed once per process. On firmware without it every
 * wildcard bind silently uses the IPv4 wildcard instead; the phone's wireless CarPlay
 * traffic is IPv4 in those deployments anyway.
 */
internal object AirPlayWildcardBind {
    /** The wildcard address this process can actually bind: "::" or 0.0.0.0. */
    val address: InetAddress by lazy {
        try {
            val probe = DatagramSocket(null)
            try {
                probe.bind(InetSocketAddress(InetAddress.getByName("::"), 0))
                InetAddress.getByName("::")
            } finally {
                runCatching { probe.close() }
            }
        } catch (_: Exception) {
            InetAddress.getByName("0.0.0.0")
        }
    }

    /** A bound-to-wildcard UDP socket on an ephemeral port with SO_REUSEADDR set. */
    fun datagram(): DatagramSocket = DatagramSocket(null).apply {
        reuseAddress = true
        bind(InetSocketAddress(address, 0))
    }

    /** A bound-to-wildcard TCP server socket on an ephemeral port with SO_REUSEADDR set. */
    fun server(): ServerSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(address, 0))
    }
}
