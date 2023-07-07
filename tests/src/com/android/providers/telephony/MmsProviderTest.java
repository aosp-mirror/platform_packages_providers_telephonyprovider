/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.AppOpsManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.Uri;
import android.provider.Telephony;
import android.telephony.TelephonyManager;
import android.test.mock.MockContentResolver;
import android.util.Log;

import junit.framework.TestCase;

import org.junit.Test;

public class MmsProviderTest extends TestCase {
    private static final String TAG = "MmsProviderTest";

    private MockContentResolver mContentResolver;
    private MmsProviderTestable mMmsProviderTestable;

    private int notifyChangeCount;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mMmsProviderTestable = new MmsProviderTestable();

        // setup mocks
        Context context = mock(Context.class);
        PackageManager packageManager = mock(PackageManager.class);
        Resources resources = mock(Resources.class);
        when(context.getSystemService(eq(Context.APP_OPS_SERVICE)))
                .thenReturn(mock(AppOpsManager.class));
        when(context.getSystemService(eq(Context.TELEPHONY_SERVICE)))
                .thenReturn(mock(TelephonyManager.class));

        when(context.checkCallingOrSelfPermission(anyString()))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        when(context.getUserId()).thenReturn(0);
        when(context.getPackageManager()).thenReturn(packageManager);
        when(context.getResources()).thenReturn(resources);
        when(resources.getString(anyInt())).thenReturn("");

        /**
         * This is used to give the MmsProviderTest a mocked context which takes a
         * MmsProvider and attaches it to the ContentResolver with telephony authority.
         * The mocked context also gives WRITE_APN_SETTINGS permissions
         */
        mContentResolver = new MockContentResolver() {
            @Override
            public void notifyChange(Uri uri, ContentObserver observer, boolean syncToNetwork,
                    int userHandle) {
                notifyChangeCount++;
            }
        };
        when(context.getContentResolver()).thenReturn(mContentResolver);

        // Add authority="mms" to given mmsProvider
        ProviderInfo providerInfo = new ProviderInfo();
        providerInfo.authority = "mms";

        // Add context to given mmsProvider
        mMmsProviderTestable.attachInfoForTesting(context, providerInfo);
        Log.d(TAG, "MockContextWithProvider: mmsProvider.getContext(): "
                + mMmsProviderTestable.getContext());

        // Add given MmsProvider to mResolver with authority="mms" so that
        // mResolver can send queries to mMmsProvider
        mContentResolver.addProvider("mms", mMmsProviderTestable);
        Log.d(TAG, "MockContextWithProvider: Add MmsProvider to mResolver");
        notifyChangeCount = 0;
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        mMmsProviderTestable.closeDatabase();
    }

    @Test
    public void testInsertMms() {
        final ContentValues values = getTestContentValues();

        Uri expected = Uri.parse("content://mms/1");
        Uri actual = mContentResolver.insert(Telephony.Mms.CONTENT_URI, values);

        assertEquals(expected, actual);
        assertEquals(1, notifyChangeCount);
    }

    @Test
    public void testInsertMmsWithoutNotify() {

        MmsProvider.ProviderUtilWrapper providerUtilWrapper =
                mock(MmsProvider.ProviderUtilWrapper.class);
        when(providerUtilWrapper.isAccessRestricted(
                any(Context.class), anyString(), anyInt())).thenReturn(false);
        mMmsProviderTestable.setProviderUtilWrapper(providerUtilWrapper);

        final ContentValues values = getTestContentValues();
        values.put(TelephonyBackupAgent.NOTIFY, false);

        Uri expected = Uri.parse("content://mms/1");
        Uri actual = mContentResolver.insert(Telephony.Mms.CONTENT_URI, values);

        assertEquals(expected, actual);
        assertEquals(0, notifyChangeCount);
    }

    private ContentValues getTestContentValues() {
        final ContentValues values = new ContentValues();
        values.put(Telephony.Mms.READ, 1);
        values.put(Telephony.Mms.SEEN, 1);
        values.put(Telephony.Mms.SUBSCRIPTION_ID, 1);
        values.put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_ALL);
        values.put(Telephony.Mms.TEXT_ONLY, 1);
        values.put(Telephony.Mms.THREAD_ID, 1);
        return values;
    }
}
