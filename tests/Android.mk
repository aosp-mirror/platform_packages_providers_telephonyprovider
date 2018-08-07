LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_STATIC_JAVA_LIBRARIES := mockito-target \
                               compatibility-device-util \
                               android-support-test

LOCAL_JAVA_LIBRARIES := \
    android.test.runner \
    telephony-common \
    android.test.base \
    android.test.mock \


LOCAL_SRC_FILES := $(call all-java-files-under,src)

LOCAL_PACKAGE_NAME := TelephonyProviderTests
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_COMPATIBILITY_SUITE := device-tests
LOCAL_CERTIFICATE := platform

LOCAL_INSTRUMENTATION_FOR := TelephonyProvider

include $(BUILD_PACKAGE)
