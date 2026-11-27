package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ScreenCodecTest {
    @Test
    fun reusedFrameDecryptorMatchesIndependentChaChaOpensAcrossCounters() {
        val key = ByteArray(32) { (it * 7).toByte() }
        val header = ByteArray(128) { (it * 3).toByte() }
        val decryptor = ScreenCodec.FrameDecryptor(key)

        for (counter in 1L..4L) {
            val plaintext = ByteArray(48 * 1024) { (it + counter).toByte() }
            val sealed = AirPlayCrypto.chachaSeal(
                key,
                AirPlayCrypto.nonce64(counter),
                plaintext,
                header,
            )

            assertArrayEquals(
                AirPlayCrypto.chachaOpen(key, AirPlayCrypto.nonce64(counter), sealed, header),
                decryptor.decrypt(counter, header, sealed, sealed.size),
            )
        }
    }

    @Test
    fun validLengthPrefixesBecomeAnnexBInPlace() {
        val first = byteArrayOf(0x40, 0x01)
        val second = byteArrayOf(0x42, 0x01, 0x02)
        val payload =
            byteArrayOf(0, 0, 0, first.size.toByte()) + first +
                byteArrayOf(0, 0, 0, second.size.toByte()) + second

        val converted = ScreenCodec.lengthPrefixedToAnnexB(payload)

        assertSame(payload, converted)
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1) + first +
                byteArrayOf(0, 0, 0, 1) + second,
            converted,
        )
    }

    @Test
    fun malformedLengthsLeavePayloadUntouched() {
        val payload = byteArrayOf(0, 0, 0, 5, 0x40, 0x01)
        val original = payload.copyOf()

        assertSame(payload, ScreenCodec.lengthPrefixedToAnnexB(payload))
        assertArrayEquals(original, payload)
    }
}
