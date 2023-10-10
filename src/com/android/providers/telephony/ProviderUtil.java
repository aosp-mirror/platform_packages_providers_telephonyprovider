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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Telephony;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.emergency.EmergencyNumber;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.SmsApplication;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Helpers
 */
public class ProviderUtil {
    private final static String TAG = "SmsProvider";

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

    /**
     * Notify the default SMS app of an SMS/MMS provider change if the change is being made
     * by a package other than the default SMS app itself.
     *
     * @param uri The uri the provider change applies to
     * @param callingPackage The package name of the provider caller
     * @param Context
     */
    public static void notifyIfNotDefaultSmsApp(final Uri uri, final String callingPackage,
            final Context context) {
        if (TextUtils.equals(callingPackage, Telephony.Sms.getDefaultSmsPackage(context))) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.d(TAG, "notifyIfNotDefaultSmsApp - called from default sms app");
            }
            return;
        }
        // Direct the intent to only the default SMS app, and only if the SMS app has a receiver
        // for the intent.
        ComponentName componentName =
                SmsApplication.getDefaultExternalTelephonyProviderChangedApplication(context, true);
        if (componentName == null) {
            return;     // the default sms app doesn't have a receiver for this intent
        }

        final Intent intent =
                new Intent(Telephony.Sms.Intents.ACTION_EXTERNAL_PROVIDER_CHANGE);
        intent.setFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.setComponent(componentName);
        if (uri != null) {
            intent.setData(uri);
        }
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.d(TAG, "notifyIfNotDefaultSmsApp - called from " + callingPackage + ", notifying");
        }
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.sendBroadcast(intent);
    }

    public static Context getCredentialEncryptedContext(Context context) {
        if (context.isCredentialProtectedStorage()) {
            return context;
        }
        return context.createCredentialProtectedStorageContext();
    }

    public static Context getDeviceEncryptedContext(Context context) {
        if (context.isDeviceProtectedStorage()) {
            return context;
        }
        return context.createDeviceProtectedStorageContext();
    }

    /**
     * Get subscriptions associated with the user in the format of a selection string.
     * @param context context
     * @param userHandle caller user handle.
     * @return subscriptions associated with the user in the format of a selection string
     * or {@code null} if user is not associated with any subscription.
     */
    @Nullable
    public static String getSelectionBySubIds(Context context, @NonNull UserHandle userHandle) {
        List<SubscriptionInfo> associatedSubscriptionsList = new ArrayList<>();
        SubscriptionManager subManager = context.getSystemService(SubscriptionManager.class);
        if (subManager != null) {
            // Get list of subscriptions associated with this user.
            associatedSubscriptionsList = subManager
                    .getSubscriptionInfoListAssociatedWithUser(userHandle);
        }

        UserManager userManager = context.getSystemService(UserManager.class);
        if ((userManager != null) && (!userManager.isManagedProfile(userHandle.getIdentifier()))) {
            // SMS/MMS restored from another device have sub_id=-1.
            // To query/update/delete those messages, sub_id=-1 should be in the selection string.
            SubscriptionInfo invalidSubInfo = new SubscriptionInfo.Builder()
                    .setId(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                    .build();
            associatedSubscriptionsList.add(invalidSubInfo);
        }

        if (associatedSubscriptionsList.isEmpty()) {
            return null;
        }

        // Converts [1,2,3,4,-1] to "'1','2','3','4','-1'" so that it can be appended to
        // selection string
        String subIdListStr = associatedSubscriptionsList.stream()
                .map(subInfo -> ("'" + subInfo.getSubscriptionId() + "'"))
                .collect(Collectors.joining(","));
        String selectionBySubId = (Telephony.Sms.SUBSCRIPTION_ID + " IN (" + subIdListStr + ")");
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.d(TAG, "getSelectionBySubIds: " + selectionBySubId);
        }
        return selectionBySubId;
    }

    /**
     * Get emergency number list in the format of a selection string.
     * @param context context
     * @return emergency number list in the format of a selection string
     * or {@code null} if emergency number list is empty.
     */
    @Nullable
    public static String getSelectionByEmergencyNumbers(@NonNull Context context) {
        // Get emergency number list to add it to selection string.
        TelephonyManager tm = context.getSystemService(TelephonyManager.class);
        Map<Integer, List<EmergencyNumber>> emergencyNumberList = null;
        try {
            if (tm != null) {
                emergencyNumberList = tm.getEmergencyNumberList();
            }
        } catch (Exception e) {
            Log.e(TAG, "Cannot get emergency number list: " + e);
        }

        String selectionByEmergencyNumber = null;
        if (emergencyNumberList != null && !emergencyNumberList.isEmpty()) {
            String emergencyNumberListStr = "";
            for (Map.Entry<Integer, List<EmergencyNumber>> entry : emergencyNumberList.entrySet()) {
                if (!emergencyNumberListStr.isEmpty() && !entry.getValue().isEmpty()) {
                    emergencyNumberListStr += ',';
                }

                emergencyNumberListStr += entry.getValue().stream()
                        .map(emergencyNumber -> ("'" + emergencyNumber.getNumber() + "'"))
                        .collect(Collectors.joining(","));
            }
            selectionByEmergencyNumber = Telephony.Sms.ADDRESS +
                    " IN (" + emergencyNumberListStr + ")";
        }
        return selectionByEmergencyNumber;
    }
}
