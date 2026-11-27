LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := xcertplay_i2c
LOCAL_SRC_FILES := linux_i2c_jni.c
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := local_hotspot_radio
LOCAL_SRC_FILES := local_hotspot_radio.c
LOCAL_CFLAGS := -Wall -Wextra -Werror
include $(BUILD_SHARED_LIBRARY)

# Bundled Fraunhofer FDK-AAC decoder (vendored under jni/fdk-aac, decoder
# libraries only). Android 4.4 head units ship vendor OMX AAC components that
# lack reliable AAC support; the API-19 audio renderer decodes CarPlay AAC
# in software through this module instead. See FdkAac.kt for the JNI facade.
FDK_ROOT := $(LOCAL_PATH)/fdk-aac
FDK_INCLUDES := \
    $(FDK_ROOT)/libAACdec/include \
    $(FDK_ROOT)/libAACdec/src \
    $(FDK_ROOT)/libArithCoding/include \
    $(FDK_ROOT)/libArithCoding/src \
    $(FDK_ROOT)/libDRCdec/include \
    $(FDK_ROOT)/libDRCdec/src \
    $(FDK_ROOT)/libMpegTPDec/include \
    $(FDK_ROOT)/libMpegTPDec/src \
    $(FDK_ROOT)/libPCMutils/include \
    $(FDK_ROOT)/libPCMutils/src \
    $(FDK_ROOT)/libSBRdec/include \
    $(FDK_ROOT)/libSBRdec/src \
    $(FDK_ROOT)/libSACdec/include \
    $(FDK_ROOT)/libSACdec/src \
    $(FDK_ROOT)/libFDK/include \
    $(FDK_ROOT)/libFDK/src \
    $(FDK_ROOT)/libSYS/include \
    $(FDK_ROOT)/libSYS/src

FDK_SOURCES := \
    $(wildcard $(FDK_ROOT)/libAACdec/src/*.cpp) \
    $(wildcard $(FDK_ROOT)/libArithCoding/src/*.cpp) \
    $(wildcard $(FDK_ROOT)/libDRCdec/src/*.cpp) \
    $(wildcard $(FDK_ROOT)/libMpegTPDec/src/*.cpp) \
    $(wildcard $(FDK_ROOT)/libPCMutils/src/*.cpp) \
    $(wildcard $(FDK_ROOT)/libSBRdec/src/*.cpp) \
    $(wildcard $(FDK_ROOT)/libSACdec/src/*.cpp) \
    $(wildcard $(FDK_ROOT)/libFDK/src/*.cpp) \
    $(wildcard $(FDK_ROOT)/libSYS/src/*.cpp)

include $(CLEAR_VARS)
LOCAL_MODULE := fdk_aac
LOCAL_SRC_FILES := fdk_aac_jni.cpp $(FDK_SOURCES)
LOCAL_C_INCLUDES := $(FDK_INCLUDES)
LOCAL_CPPFLAGS := -O2 -fno-exceptions -fno-rtti -Wno-unused-parameter -Wno-sign-compare \
    -DSUPPRESS_BUILD_DATE_INFO
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
