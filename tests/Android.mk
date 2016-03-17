LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_STATIC_JAVA_LIBRARIES := mockito-target

LOCAL_JAVA_LIBRARIES := android.test.runner telephony-common

LOCAL_SRC_FILES := $(call all-java-files-under,src)

LOCAL_PACKAGE_NAME := TelephonyProviderTests
LOCAL_CERTIFICATE := platform

LOCAL_INSTRUMENTATION_FOR := TelephonyProvider

include $(BUILD_PACKAGE)
