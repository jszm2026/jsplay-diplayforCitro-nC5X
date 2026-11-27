package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayIapTunnelStreamTest {
    @Test
    fun inputConnectionCloseKeepsEventChannelAvailableForOutboundIap() {
        val sent = mutableListOf<ByteArray>()
        val session = testSession()
        val bridge = AirPlayIapTunnelStream(
            session = session,
            tunnel = IapTunnel(ByteArray(32)),
            sendCommand = { data, _ ->
                sent += data.copyOf()
                true
            },
        )

        try {
            val port = bridge.listen()
            Socket(InetAddress.getByName("127.0.0.1"), port).use { }

            val payload = byteArrayOf(1, 2, 3)
            bridge.send(payload)

            assertTrue(sent.size == 1)
            assertArrayEquals(payload, sent.single())
        } finally {
            bridge.close()
            session.close()
        }
    }

    @Test
    fun ipv6ListenerAcceptsIpv6Loopback() {
        val bridge = AirPlayIapTunnelStream(
            session = testSession(),
            tunnel = IapTunnel(
                readKey = ByteArray(32),
                bindAddress = InetAddress.getByName("::"),
            ),
            sendCommand = { _, _ -> true },
        )

        try {
            val port = bridge.listen()
            Socket(InetAddress.getByName("::1"), port).use { }
        } finally {
            bridge.close()
        }
    }

    @Test
    fun wirelessTunnelPeerCloseDoesNotEndAirPlaySession() {
        val sessionEnded = AtomicBoolean(false)
        val session = testSession(object : AirPlaySessionListener {
            override fun onSessionEnded(session: AirPlaySession) {
                sessionEnded.set(true)
            }
        })
        val bridge = AirPlayIapTunnelStream(
            session = session,
            tunnel = IapTunnel(ByteArray(32)),
            closeSessionOnTunnelFailure = false,
            sendCommand = { _, _ -> true },
        )

        try {
            val port = bridge.listen()
            Socket(InetAddress.getByName("127.0.0.1"), port).use { }
            runCatching { bridge.recv(32, 1_000) }

            assertTrue("Auxiliary tunnel closure must not close AirPlay", !sessionEnded.get())
        } finally {
            bridge.close()
            session.close()
        }
        assertTrue("Explicit session cleanup should still notify its listener", sessionEnded.get())
    }

    private fun testSession(listener: AirPlaySessionListener = object : AirPlaySessionListener {}): AirPlaySession = AirPlaySession(
        socket = Socket(),
        config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0",
            main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
        ),
        identity = AirPlayIdentity.generate(),
        pairings = PairingStore(),
        mfi = null,
        listener = listener,
        media = object : AirPlayMediaHandler {},
    )

}
