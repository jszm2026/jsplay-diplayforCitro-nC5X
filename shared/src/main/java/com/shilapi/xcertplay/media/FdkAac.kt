package com.shilapi.xcertplay.media

/**
 * Small JNI facade for the bundled FDK-AAC decoder.
 *
 * Android 4.4 head units ship vendor OMX AAC components that do not implement
 * the profiles CarPlay negotiates (and some crash natively on unsupported
 * object types), so [AudioRenderer][AndroidMediaSink] decodes AAC in software
 * through this module on API 19/20. The decoder is configured once with a raw
 * AudioSpecificConfig ([TT_MP4_RAW] transport) and then fed one access unit
 * per [decode] call; PCM comes back as interleaved 16-bit little-endian shorts.
 */
internal object FdkAac {
    private var loaded = false

    init {
        loaded = runCatching {
            System.loadLibrary("fdk_aac")
            true
        }.getOrDefault(false)
    }

    fun isAvailable(): Boolean = loaded

    /** Returns a native decoder handle, or 0 when the config was rejected. */
    fun open(config: ByteArray): Long = if (loaded) nativeOpen(config) else 0L

    /**
     * Decodes one access unit into [output]. Returns the number of written
     * 16-bit samples (interleaved), or a negative error/skip code.
     */
    fun decode(handle: Long, input: ByteArray, output: ShortArray): Int =
        if (loaded && handle != 0L) nativeDecode(handle, input, output) else -1

    fun close(handle: Long) {
        if (loaded && handle != 0L) nativeClose(handle)
    }

    private external fun nativeOpen(config: ByteArray): Long
    private external fun nativeDecode(handle: Long, input: ByteArray, output: ShortArray): Int
    private external fun nativeClose(handle: Long)
}
