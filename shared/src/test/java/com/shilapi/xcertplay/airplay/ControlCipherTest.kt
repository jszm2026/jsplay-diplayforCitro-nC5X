package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ControlCipherTest {
    @Test
    fun framesMatchBouncyCastleWireBytesAndDecryptAcrossFragmentedReads() {
        val key = ByteArray(32) { (it * 11).toByte() }
        val plaintext = ByteArray(0x4000 + 37) { (it * 3).toByte() }
        val sender = ControlCipher(key, key)
        val wire = sender.encrypt(plaintext)
        val expected = expectedFrames(key, plaintext)
        assertArrayEquals(expected, wire)

        val receiver = ControlCipher(key, key)
        val split = wire.size / 3
        val first = receiver.decrypt(wire.copyOfRange(0, split))
        assertEquals(0, first.data.size)

        val second = receiver.decrypt(first.rest + wire.copyOfRange(split, wire.size))
        assertArrayEquals(plaintext, second.data)
        assertEquals(0, second.rest.size)
    }

    @Test
    fun emptyPayloadStillProducesOneAuthenticatedFrame() {
        val key = ByteArray(32) { it.toByte() }
        val cipher = ControlCipher(key, key)
        val frame = cipher.encrypt(ByteArray(0))
        assertArrayEquals(expectedFrames(key, ByteArray(0)), frame)
        assertArrayEquals(ByteArray(0), ControlCipher(key, key).decrypt(frame).data)
    }

    private fun expectedFrames(key: ByteArray, plaintext: ByteArray): ByteArray {
        val frames = ArrayList<ByteArray>()
        var offset = 0
        var counter = 0L
        do {
            val length = minOf(0x4000, plaintext.size - offset)
            val chunk = plaintext.copyOfRange(offset, offset + length)
            val header = byteArrayOf(length.toByte(), (length ushr 8).toByte())
            val sealed = AirPlayCrypto.chachaSeal(
                key,
                AirPlayCrypto.nonce64(counter++),
                chunk,
                header,
            )
            frames += header + sealed
            offset += 0x4000
        } while (offset < plaintext.size)
        return frames.fold(ByteArray(0)) { output, frame -> output + frame }
    }
}
