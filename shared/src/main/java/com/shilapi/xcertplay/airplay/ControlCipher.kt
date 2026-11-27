package com.shilapi.xcertplay.airplay

import java.security.Provider
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

/**
 * ChaCha20-Poly1305 framing for the CarPlay control channel.
 *
 * Each frame is [2-byte little-endian ciphertext length][ciphertext][16-byte tag]; the length
 * header is the AEAD associated data and each direction uses an 8-byte little-endian nonce
 * counter plus its own key.
 */
class ControlCipher(private val readKey: ByteArray, private val writeKey: ByteArray) {
    data class Decrypted(val data: ByteArray, val rest: ByteArray)

    private val readCipher = FrameAead(readKey)
    private val writeCipher = FrameAead(writeKey)
    private var readCounter = 0L
    private var writeCounter = 0L

    fun decrypt(buffer: ByteArray): Decrypted {
        val output = ArrayList<ByteArray>()
        var offset = 0
        while (buffer.size - offset >= HEADER_SIZE) {
            val length = readU16Le(buffer, offset)
            val frameEnd = offset + HEADER_SIZE + length + TAG_SIZE
            if (buffer.size < frameEnd) break
            output.add(
                readCipher.open(
                    readCounter,
                    buffer,
                    offset,
                    offset + HEADER_SIZE,
                    length + TAG_SIZE,
                ),
            )
            readCounter++
            offset = frameEnd
        }
        return Decrypted(concatBytes(*output.toTypedArray()), buffer.copyOfRange(offset, buffer.size))
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        val output = ArrayList<ByteArray>()
        var offset = 0
        do {
            val length = minOf(MAX_PAYLOAD, plaintext.size - offset)
            val header = byteArrayOf(length.toByte(), (length ushr 8).toByte())
            val ciphertextAndTag = writeCipher.seal(writeCounter, plaintext, offset, length, header)
            output.add(header)
            output.add(ciphertextAndTag)
            writeCounter++
            offset += length
        } while (offset < plaintext.size)
        return concatBytes(*output.toTypedArray())
    }

    private fun readU16Le(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private class FrameAead(key: ByteArray) {
        private val keyCopy = key.copyOf()
        private val nativeCipher = runCatching {
            val conscrypt = Class.forName("org.conscrypt.Conscrypt")
            val provider = conscrypt.getMethod("newProvider").invoke(null) as Provider
            Cipher.getInstance(TRANSFORMATION, provider)
        }.getOrNull()
        private val nativeKey = nativeCipher?.let { SecretKeySpec(keyCopy, KEY_ALGORITHM) }
        private val fallbackCipher = if (nativeCipher == null) ChaCha20Poly1305() else null
        private val fallbackKey = if (fallbackCipher != null) KeyParameter(keyCopy) else null

        fun seal(counter: Long, plaintext: ByteArray, aad: ByteArray): ByteArray =
            seal(counter, plaintext, 0, plaintext.size, aad)

        fun seal(counter: Long, plaintext: ByteArray, offset: Int, length: Int, aad: ByteArray): ByteArray {
            val nonce = AirPlayCrypto.nonce64(counter)
            nativeCipher?.let { cipher ->
                cipher.init(Cipher.ENCRYPT_MODE, requireNotNull(nativeKey), IvParameterSpec(nonce))
                cipher.updateAAD(aad)
                return cipher.doFinal(plaintext, offset, length)
            }
            val cipher = requireNotNull(fallbackCipher)
            cipher.init(true, AEADParameters(requireNotNull(fallbackKey), MAC_BITS, nonce, aad))
            val output = ByteArray(cipher.getOutputSize(length))
            val written = cipher.processBytes(plaintext, offset, length, output, 0)
            cipher.doFinal(output, written)
            return output
        }

        fun open(counter: Long, input: ByteArray, aadOffset: Int, inputOffset: Int, length: Int): ByteArray {
            val nonce = AirPlayCrypto.nonce64(counter)
            val aadLength = HEADER_SIZE
            nativeCipher?.let { cipher ->
                cipher.init(Cipher.DECRYPT_MODE, requireNotNull(nativeKey), IvParameterSpec(nonce))
                cipher.updateAAD(input, aadOffset, aadLength)
                return cipher.doFinal(input, inputOffset, length)
            }
            val aad = input.copyOfRange(aadOffset, aadOffset + aadLength)
            val cipher = requireNotNull(fallbackCipher)
            cipher.init(false, AEADParameters(requireNotNull(fallbackKey), MAC_BITS, nonce, aad))
            val output = ByteArray(cipher.getOutputSize(length))
            val written = cipher.processBytes(input, inputOffset, length, output, 0)
            val finalized = cipher.doFinal(output, written)
            val outputLength = written + finalized
            return if (outputLength == output.size) output else output.copyOf(outputLength)
        }
    }

    private companion object {
        const val HEADER_SIZE = 2
        const val TAG_SIZE = 16
        const val MAX_PAYLOAD = 0x4000
        const val MAC_BITS = 128
        const val KEY_ALGORITHM = "ChaCha20"
        const val TRANSFORMATION = "ChaCha20/Poly1305/NoPadding"
    }
}
