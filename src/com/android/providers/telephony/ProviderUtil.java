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
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
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
import com.android.internal.telephony.TelephonyPermissions;
import com.android.internal.telephony.flags.Flags;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Helpers
 */
public class ProviderUtil {
    private final static String TAG = "SmsProvider";
    private static final String TELEPHONY_PROVIDER_PACKAGE = "com.android.providers.telephony";

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
        return (!TelephonyPermissions.isSystemOrPhone(uid)
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
        return (!TelephonyPermissions.isSystemOrPhone(uid))
                || (!values.containsKey(Telephony.Sms.CREATOR)
                        && !values.containsKey(Telephony.Mms.CREATOR));
    }

    /**
     * Whether should remove CREATOR for an update
     *
     * @param values The content of the message
     * @param uid The caller UID of the update
     * @return true if we should remove CREATOR, false otherwise
     */
    public static boolean shouldRemoveCreator(ContentValues values, int uid) {
        return (!TelephonyPermissions.isSystemOrPhone(uid))
                && (values.containsKey(Telephony.Sms.CREATOR)
                        || values.containsKey(Telephony.Mms.CREATOR));
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
    public static String getSelectionBySubIds(Context context,
            @NonNull final UserHandle userHandle) {
        List<SubscriptionInfo> associatedSubscriptionsList = new ArrayList<>();
        SubscriptionManager subManager = context.getSystemService(SubscriptionManager.class);
        UserManager userManager = context.getSystemService(UserManager.class);

        if (Flags.workProfileApiSplit()) {
            if (subManager != null) {
                // Get list of subscriptions accessible to this user.
                associatedSubscriptionsList = subManager
                        .getSubscriptionInfoListAssociatedWithUser(userHandle);

                if ((userManager != null)
                        && userManager.isManagedProfile(userHandle.getIdentifier())) {
                    // Work profile caller can only see subscriptions explicitly associated with it.
                    associatedSubscriptionsList = associatedSubscriptionsList.stream()
                            .filter(info -> userHandle.equals(subManager
                                            .getSubscriptionUserHandle(info.getSubscriptionId())))
                            .collect(Collectors.toList());
                } else {
                    // SMS/MMS restored from another device have sub_id=-1.
                    // To query/update/delete those messages, sub_id=-1 should be in the selection
                    // string.
                    SubscriptionInfo invalidSubInfo = new SubscriptionInfo.Builder()
                            .setId(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                            .build();
                    associatedSubscriptionsList.add(invalidSubInfo);
                }
            }
        } else {
            if (subManager != null) {
                // Get list of subscriptions associated with this user.
                associatedSubscriptionsList = subManager
                        .getSubscriptionInfoListAssociatedWithUser(userHandle);
            }

            if ((userManager != null)
                    && (!userManager.isManagedProfile(userHandle.getIdentifier()))) {
                // SMS/MMS restored from another device have sub_id=-1.
                // To query/update/delete those messages, sub_id=-1 should be in the selection
                // string.
                SubscriptionInfo invalidSubInfo = new SubscriptionInfo.Builder()
                        .setId(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                        .build();
                associatedSubscriptionsList.add(invalidSubInfo);
            }
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
            Log.e(TAG, "Cannot get emergency number list", e);
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

    /**
     * Check sub is either default value(for backup restore) or is accessible by the caller profile.
     * @param ctx Context
     * @param subId The sub Id associated with the entry
     * @param callerUserHandle The user handle of the caller profile
     * @return {@code true} if allow the caller to insert an entry that's associated with this sub.
     */
    public static boolean allowInteractingWithEntryOfSubscription(Context ctx,
            int subId, UserHandle callerUserHandle) {
        return TelephonyPermissions
                .checkSubscriptionAssociatedWithUser(ctx, subId, callerUserHandle)
                // INVALID_SUBSCRIPTION_ID represents backup restore.
                || subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    /**
     * Log all running processes of the telephony provider package.
     */
    public static void logRunningTelephonyProviderProcesses(@NonNull Context context) {
        ActivityManager am = context.getSystemService(ActivityManager.class);
        if (am == null) {
            Log.d(TAG, "logRunningTelephonyProviderProcesses: ActivityManager service is not"
                    + " available");
            return;
        }

        List<ActivityManager.RunningAppProcessInfo> processInfos = am.getRunningAppProcesses();
        if (processInfos == null) {
            Log.d(TAG, "logRunningTelephonyProviderProcesses: processInfos is null");
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (ActivityManager.RunningAppProcessInfo processInfo : processInfos) {
            if (Arrays.asList(processInfo.pkgList).contains(TELEPHONY_PROVIDER_PACKAGE)
                    || UserHandle.isSameApp(processInfo.uid, Process.PHONE_UID)) {
                sb.append("{ProcessName=");
                sb.append(processInfo.processName);
                sb.append(";PID=");
                sb.append(processInfo.pid);
                sb.append(";UID=");
                sb.append(processInfo.uid);
                sb.append(";pkgList=");
                for (String pkg : processInfo.pkgList) {
                    sb.append(pkg + ";");
                }
                sb.append("}");
            }
        }
        Log.d(TAG, "RunningTelephonyProviderProcesses:" + sb.toString());
    }
}
