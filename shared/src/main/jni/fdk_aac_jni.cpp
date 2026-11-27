#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "aacdecoder_lib.h"

struct FdkDecoder {
    HANDLE_AACDECODER handle;
};

static FdkDecoder *decoder_of(jlong value) {
    return reinterpret_cast<FdkDecoder *>(static_cast<intptr_t>(value));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_shilapi_xcertplay_media_FdkAac_nativeOpen(
        JNIEnv *env, jobject thiz, jbyteArray config) {
    (void) thiz;
    if (config == NULL) return 0;
    const jsize config_size = env->GetArrayLength(config);
    if (config_size <= 0 || config_size > 64) return 0;

    FdkDecoder *decoder = static_cast<FdkDecoder *>(calloc(1, sizeof(FdkDecoder)));
    if (decoder == NULL) return 0;
    decoder->handle = aacDecoder_Open(TT_MP4_RAW, 1);
    if (decoder->handle == NULL) {
        free(decoder);
        return 0;
    }

    jbyte config_bytes[64];
    env->GetByteArrayRegion(config, 0, config_size, config_bytes);
    UCHAR *config_ptr = reinterpret_cast<UCHAR *>(config_bytes);
    UINT config_length = static_cast<UINT>(config_size);
    const AAC_DECODER_ERROR error =
            aacDecoder_ConfigRaw(decoder->handle, &config_ptr, &config_length);
    if (error != AAC_DEC_OK) {
        aacDecoder_Close(decoder->handle);
        free(decoder);
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(decoder));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_media_FdkAac_nativeDecode(
        JNIEnv *env, jobject thiz, jlong value, jbyteArray input, jshortArray output) {
    (void) thiz;
    FdkDecoder *decoder = decoder_of(value);
    if (decoder == NULL || decoder->handle == NULL || input == NULL || output == NULL) return -1;
    const jsize input_size = env->GetArrayLength(input);
    const jsize output_size = env->GetArrayLength(output);
    if (input_size <= 0 || output_size <= 0) return -2;

    jbyte input_bytes[8192];
    if (input_size > static_cast<jsize>(sizeof(input_bytes))) return -3;
    env->GetByteArrayRegion(input, 0, input_size, input_bytes);
    UCHAR *input_ptr = reinterpret_cast<UCHAR *>(input_bytes);
    UINT input_length = static_cast<UINT>(input_size);
    UINT valid_bytes = input_length;
    UCHAR *input_buffers[1] = {input_ptr};
    const AAC_DECODER_ERROR fill_error =
            aacDecoder_Fill(decoder->handle, input_buffers, &input_length, &valid_bytes);
    if (fill_error != AAC_DEC_OK && fill_error != AAC_DEC_NOT_ENOUGH_BITS) {
        return -100 - static_cast<jint>(fill_error);
    }

    // FDK reports INT_PCM as signed 16-bit samples. The output array is kept
    // caller-owned so no per-frame native allocation is needed.
    jshort *pcm = env->GetShortArrayElements(output, NULL);
    if (pcm == NULL) return -4;
    const AAC_DECODER_ERROR decode_error = aacDecoder_DecodeFrame(
            decoder->handle, reinterpret_cast<INT_PCM *>(pcm), static_cast<INT>(output_size), 0);
    const bool output_valid = IS_OUTPUT_VALID(decode_error);
    env->ReleaseShortArrayElements(output, pcm, output_valid ? 0 : JNI_ABORT);
    if (!output_valid) return -200 - static_cast<jint>(decode_error);

    const CStreamInfo *info = aacDecoder_GetStreamInfo(decoder->handle);
    if (info == NULL || info->numChannels <= 0 || info->frameSize <= 0) return 0;
    const jint samples = static_cast<jint>(info->frameSize * info->numChannels);
    return samples <= output_size ? samples : output_size;
}

extern "C" JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_media_FdkAac_nativeClose(
        JNIEnv *env, jobject thiz, jlong value) {
    (void) env;
    (void) thiz;
    FdkDecoder *decoder = decoder_of(value);
    if (decoder == NULL) return;
    if (decoder->handle != NULL) aacDecoder_Close(decoder->handle);
    free(decoder);
}
