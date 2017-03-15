LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_STATIC_JAVA_LIBRARIES := mockito-target \
                               legacy-android-test \
                               compatibility-device-util \
                               android-support-test

LOCAL_JAVA_LIBRARIES := android.test.runner telephony-common

LOCAL_SRC_FILES := $(call all-java-files-under,src)

LOCAL_PACKAGE_NAME := TelephonyProviderTests
LOCAL_COMPATIBILITY_SUITE := device-tests
LOCAL_CERTIFICATE := platform

LOCAL_INSTRUMENTATION_FOR := TelephonyProvider

include $(BUILD_PACKAGE)
