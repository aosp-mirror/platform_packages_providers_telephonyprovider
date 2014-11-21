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
import android.content.pm.PackageManager;
import android.os.Process;
import android.provider.Telephony;
import android.text.TextUtils;

/**
 * Helpers
 */
public class ProviderUtil {

    /**
     * Get space separated package names associated with a UID
     *
     * @param context The context to use
     * @param uid The UID to look up
     * @return The space separated list of package names for UID
     */
    public static String getPackageNamesByUid(Context context, int uid) {
        final PackageManager pm = context.getPackageManager();
        final String[] packageNames = pm.getPackagesForUid(uid);
        if (packageNames != null) {
            final StringBuilder sb = new StringBuilder();
            for (String name : packageNames) {
                if (!TextUtils.isEmpty(name)) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(name);
                }
            }
            return sb.toString();
        }
        return null;
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
