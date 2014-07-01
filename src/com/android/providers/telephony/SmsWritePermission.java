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

import android.os.Binder;
import android.os.Process;
import android.provider.Telephony;
import android.util.Log;

/**
 * Write permission enforcement for SmsProvider and MmsProvider
 */
public class SmsWritePermission {
    public static final String TAG = "SmsWritePermission";

    public static void enforce() {
        if (!Telephony.NEW_API) {
            // TODO(ywen): Temporarily disable this so not to break existing apps
            return;
        }
        final long uid = Binder.getCallingUid();
        Log.d(TAG, "Calling UID " + uid);
        if (uid != Process.SYSTEM_UID && uid != Process.PHONE_UID) {
            throw new SecurityException("Only system or phone can access");
        }
    }
}
