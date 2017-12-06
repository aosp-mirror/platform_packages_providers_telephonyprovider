/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.providers.telephony;

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
import android.test.mock.MockContext;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import junit.framework.TestCase;

import org.junit.Test;
import org.mockito.Mockito;


/**
 * Tests for testing CRUD operations of SmsProvider.
 * Uses a MockContentResolver to test insert
 * Uses SmsProviderTestable to set up in-memory database
 *
 * Build, install and run the tests by running the commands below:
 *     runtest --path <dir or file>
 *     runtest --path <dir or file> --test-method <testMethodName>
 *     e.g.)
 *         runtest --path tests/src/com/android/providers/telephony/SmsProviderTest.java \
 *                 --test-method testInsertUri
 */
public class SmsProviderTest extends TestCase {
    private static final String TAG = "TelephonyProviderTest";

    private MockContextWithProvider mContext;
    private MockContentResolver mContentResolver;
    private SmsProviderTestable mSmsProviderTestable;

    private int notifyChangeCount;


    /**
     * This is used to give the SmsProviderTest a mocked context which takes a
     * SmsProvider and attaches it to the ContentResolver with telephony authority.
     * The mocked context also gives WRITE_APN_SETTINGS permissions
     */
    private class MockContextWithProvider extends MockContext {
        private final MockContentResolver mResolver;

        public MockContextWithProvider(SmsProvider smsProvider) {
            mResolver = new MockContentResolver() {
                @Override
                public void notifyChange(Uri uri, ContentObserver observer, boolean syncToNetwork,
                        int userHandle) {
                    notifyChangeCount++;
                }
            };

            // Add authority="sms" to given smsProvider
            ProviderInfo providerInfo = new ProviderInfo();
            providerInfo.authority = "sms";

            // Add context to given smsProvider
            smsProvider.attachInfoForTesting(this, providerInfo);
            Log.d(TAG, "MockContextWithProvider: smsProvider.getContext(): "
                    + smsProvider.getContext());

            // Add given SmsProvider to mResolver with authority="sms" so that
            // mResolver can send queries to mSmsProvider
            mResolver.addProvider("sms", smsProvider);
            Log.d(TAG, "MockContextWithProvider: Add SmsProvider to mResolver");
        }

        @Override
        public Object getSystemService(String name) {
            Log.d(TAG, "getSystemService: returning null");
            switch (name) {
                case Context.APP_OPS_SERVICE:
                    return Mockito.mock(AppOpsManager.class);
                case Context.TELEPHONY_SERVICE:
                    return Mockito.mock(TelephonyManager.class);
                default:
                    return null;
            }
        }

        @Override
        public Resources getResources() {
            Log.d(TAG, "getResources: returning null");
            return null;
        }

        @Override
        public int getUserId() {
            return 0;
        }

        @Override
        public MockContentResolver getContentResolver() {
            return mResolver;
        }

        @Override
        public int checkCallingOrSelfPermission(String permission) {
            return PackageManager.PERMISSION_GRANTED;
        }
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mSmsProviderTestable = new SmsProviderTestable();
        mContext = new MockContextWithProvider(mSmsProviderTestable);
        mContentResolver = mContext.getContentResolver();
        notifyChangeCount = 0;
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        mSmsProviderTestable.closeDatabase();
    }

    @Test
    @SmallTest
    public void testInsertUri() {
        // insert test contentValues
        final ContentValues values = new ContentValues();
        values.put(Telephony.Sms.SUBSCRIPTION_ID, 1);
        values.put(Telephony.Sms.ADDRESS, "12345");
        values.put(Telephony.Sms.BODY, "test");
        values.put(Telephony.Sms.DATE, System.currentTimeMillis()); // milliseconds
        values.put(Telephony.Sms.SEEN, 1);
        values.put(Telephony.Sms.READ, 1);
        values.put(Telephony.Sms.THREAD_ID, 1);

        // test for sms table
        Log.d(TAG, "testInsertSmsTable Inserting contentValues: " + values);
        assertEquals(Uri.parse("content://sms/1"),
                mContentResolver.insert(Uri.parse("content://sms"), values));
        assertEquals(Uri.parse("content://sms/2"),
                mContentResolver.insert(Uri.parse("content://sms"), values));

        // test for attachments table
        values.clear();
        values.put("sms_id", 1);
        values.put("content_url", "test");
        values.put("offset", 0);
        Log.d(TAG, "testInsertAttachmentTable Inserting contentValues: " + values);
        assertEquals(Uri.parse("content://sms/attachments/1"),
                mContentResolver.insert(Uri.parse("content://sms/attachments"), values));
    }
}