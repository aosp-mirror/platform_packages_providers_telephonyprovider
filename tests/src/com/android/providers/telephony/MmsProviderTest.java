/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.AppOpsManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.UserManager;
import android.provider.Telephony;
import android.telephony.TelephonyManager;
import android.test.mock.MockContentResolver;
import android.util.Log;

import junit.framework.TestCase;

import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class MmsProviderTest extends TestCase {
    private static final String TAG = "MmsProviderTest";

    @Mock private Context mContext;
    private MockContentResolver mContentResolver;
    private MmsProviderTestable mMmsProviderTestable;
    @Mock private PackageManager mPackageManager;

    private int notifyChangeCount;
    private UserManager mUserManager;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);
        mMmsProviderTestable = new MmsProviderTestable();
        mUserManager = mock(UserManager.class);

        // setup mocks
        when(mContext.getSystemService(eq(Context.APP_OPS_SERVICE)))
                .thenReturn(mock(AppOpsManager.class));
        when(mContext.getSystemService(eq(Context.TELEPHONY_SERVICE)))
                .thenReturn(mock(TelephonyManager.class));
        when(mContext.getSystemService(eq(Context.USER_SERVICE)))
                .thenReturn(mUserManager);

        when(mContext.checkCallingOrSelfPermission(anyString()))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mContext.getUserId()).thenReturn(0);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);

        /**
         * This is used to give the MmsProviderTest a mocked context which takes a
         * SmsProvider and attaches it to the ContentResolver with telephony authority.
         * The mocked context also gives WRITE_APN_SETTINGS permissions
         */
        mContentResolver = new MockContentResolver() {
            @Override
            public void notifyChange(Uri uri, ContentObserver observer, boolean syncToNetwork,
                    int userHandle) {
                notifyChangeCount++;
            }
        };
        when(mContext.getContentResolver()).thenReturn(mContentResolver);

        // Add authority="mms" to given mmsProvider
        ProviderInfo providerInfo = new ProviderInfo();
        providerInfo.authority = "mms";

        // Add context to given mmsProvider
        mMmsProviderTestable.attachInfoForTesting(mContext, providerInfo);
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
        final ContentValues values = new ContentValues();
        values.put(Telephony.Mms.READ, 1);
        values.put(Telephony.Mms.SEEN, 1);
        values.put(Telephony.Mms.SUBSCRIPTION_ID, 1);
        values.put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_ALL);
        values.put(Telephony.Mms.TEXT_ONLY, 1);
        values.put(Telephony.Mms.THREAD_ID, 1);

        Uri expected = Uri.parse("content://mms/1");
        Uri actual = mContentResolver.insert(Telephony.Mms.CONTENT_URI, values);

        assertEquals(expected, actual);
        assertEquals(1, notifyChangeCount);
    }

    @Test
    public void testInsertUsingManagedProfile() {
        when(mUserManager.isManagedProfile(anyInt())).thenReturn(true);

        try {
            assertNull(mContentResolver.insert(Telephony.Mms.CONTENT_URI, null));
        } catch (Exception e) {
            Log.d(TAG, "Error inserting mms: " + e);
        }
    }

    @Test
    public void testQueryUsingManagedProfile() {
        when(mUserManager.isManagedProfile(anyInt())).thenReturn(true);

        try (Cursor cursor = mContentResolver.query(Telephony.Mms.CONTENT_URI,
                null, null, null, null)) {
            assertEquals(0, cursor.getCount());
        } catch (Exception e) {
            Log.d(TAG, "Exception in getting count: " + e);
        }
    }

    @Test
    public void testUpdateUsingManagedProfile() {
        when(mUserManager.isManagedProfile(anyInt())).thenReturn(true);

        try {
            assertEquals(0, mContentResolver.update(Telephony.Mms.CONTENT_URI, null, null, null));
        } catch (Exception e) {
            Log.d(TAG, "Exception in updating mms: " + e);
        }
    }

    @Test
    public void testDeleteUsingManagedProfile() {
        when(mUserManager.isManagedProfile(anyInt())).thenReturn(true);

        try {
            assertEquals(0, mContentResolver.delete(Telephony.Mms.CONTENT_URI, null, null));
        } catch (Exception e) {
            Log.d(TAG, "Exception in deleting mms: " + e);
        }
    }
}
