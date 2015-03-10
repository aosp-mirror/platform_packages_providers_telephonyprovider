/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.telephony;

import android.content.ContentValues;
import android.content.Context;
import android.os.Process;
import android.provider.Telephony;

import com.android.internal.telephony.SmsApplication;

/**
 * Helpers
 */
public class ProviderUtil {

    /**
     * Check if a caller of the provider has restricted access,
     * i.e. being non-system, non-phone, non-default SMS app
     *
     * @param context the context to use
     * @param packageName the caller package name
     * @param uid the caller uid
     * @return true if the caller is not system, or phone or default sms app, false otherwise
     */
    public static boolean isAccessRestricted(Context context, String packageName, int uid) {
        return (uid != Process.SYSTEM_UID
                && uid != Process.PHONE_UID
                && !SmsApplication.isDefaultSmsApplication(context, packageName));
    }

    /**
     * Whether should set CREATOR for an insertion
     *
     * @param values The content of the message
     * @param uid The caller UID of the insertion
     * @return true if we should set CREATOR, false otherwise
     */
    public static boolean shouldSetCreator(ContentValues values, int uid) {
        return (uid != Process.SYSTEM_UID && uid != Process.PHONE_UID) ||
                (!values.containsKey(Telephony.Sms.CREATOR) &&
                        !values.containsKey(Telephony.Mms.CREATOR));
    }

    /**
     * Whether should remove CREATOR for an update
     *
     * @param values The content of the message
     * @param uid The caller UID of the update
     * @return true if we should remove CREATOR, false otherwise
     */
    public static boolean shouldRemoveCreator(ContentValues values, int uid) {
        return (uid != Process.SYSTEM_UID && uid != Process.PHONE_UID) &&
                (values.containsKey(Telephony.Sms.CREATOR) ||
                        values.containsKey(Telephony.Mms.CREATOR));
    }
}
