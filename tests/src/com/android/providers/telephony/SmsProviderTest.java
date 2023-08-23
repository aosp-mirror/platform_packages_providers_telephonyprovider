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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.AppOpsManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.os.UserManager;
import android.provider.Telephony;
import android.telephony.TelephonyManager;
import android.test.mock.MockContentResolver;
import android.test.mock.MockContext;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

import junit.framework.TestCase;

import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

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
    private static final String TAG = "SmsProviderTest";

    @Mock private Context mContext;
    private MockContentResolver mContentResolver;
    private SmsProviderTestable mSmsProviderTestable;
    @Mock private PackageManager mPackageManager;
    @Mock private Resources mMockResources;
    @Mock private UserManager mUserManager;

    private int notifyChangeCount;

    private final String mFakePdu = "123abc";
    private final String mFakeAddress = "FakeAddress";
    private final String mFakeOriginatingAddr = "FakeDisplayAddress";
    private final String mFakeMessageBody = "FakeMessageBody";
    private final int mFakeRefNumber = 123;
    private final int mFakeSequence = 1;
    private final int mFakeCount = 1;
    private final int mFakePort = 1 << 19;
    private final long mDate = 0;

    private final Uri mRawUri =
            Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, "raw");
    private final Uri mRawUriPermanentDelete =
            Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, "raw/permanentDelete");

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);
        mSmsProviderTestable = new SmsProviderTestable();

        when(mContext.getSystemService(eq(Context.APP_OPS_SERVICE)))
                .thenReturn(mock(AppOpsManager.class));
        when(mContext.getSystemService(eq(Context.TELEPHONY_SERVICE)))
                .thenReturn(mock(TelephonyManager.class));
        when(mContext.getSystemService(eq(Context.USER_SERVICE))).thenReturn(mUserManager);

        when(mContext.checkCallingOrSelfPermission(anyString()))
                .thenReturn(PackageManager.PERMISSION_GRANTED);

        when(mMockResources
                        .getString(eq(com.android.internal.R.string.config_systemBluetoothStack)))
                .thenReturn("com.android.bluetooth");
        when(mContext.getResources()).thenReturn(mMockResources);
        when(mContext.getUserId()).thenReturn(0);

        when(mContext.getPackageManager()).thenReturn(mPackageManager);

        /**
         * This is used to give the SmsProviderTest a mocked context which takes a
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

        // Add authority="sms" to given smsProvider
        ProviderInfo providerInfo = new ProviderInfo();
        providerInfo.authority = "sms";

        // Add context to given smsProvider
        mSmsProviderTestable.attachInfoForTesting(mContext, providerInfo);
        Log.d(TAG, "MockContextWithProvider: smsProvider.getContext(): "
                + mSmsProviderTestable.getContext());

        // Add given SmsProvider to mResolver with authority="sms" so that
        // mResolver can send queries to mSmsProvider
        mContentResolver.addProvider("sms", mSmsProviderTestable);
        Log.d(TAG, "MockContextWithProvider: Add SmsProvider to mResolver");
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

    @Test
    @SmallTest
    public void testRawTableInsert() {
        // insert test contentValues
        assertEquals(Uri.parse("content://sms/raw/1"),
                mContentResolver.insert(mRawUri, getFakeRawValue()));

        // Query and confirm contents.
        Cursor cursor = mContentResolver.query(mRawUri, null, null, null, null);
        assertEquals(1, cursor.getCount());
        cursor.moveToNext();
        assertEquals(mFakePdu, cursor.getString(cursor.getColumnIndex("pdu")));
        assertEquals(mFakeAddress, cursor.getString(cursor.getColumnIndex("address")));
        assertEquals(mFakeOriginatingAddr,
                cursor.getString(cursor.getColumnIndex("display_originating_addr")));
        assertEquals(mFakeMessageBody, cursor.getString(cursor.getColumnIndex("message_body")));
        assertEquals(mDate, cursor.getInt(cursor.getColumnIndex("date")));
        assertEquals(mFakePort, cursor.getInt(cursor.getColumnIndex("destination_port")));
        assertEquals(mFakeRefNumber, cursor.getInt(cursor.getColumnIndex("reference_number")));
        assertEquals(mFakeSequence, cursor.getInt(cursor.getColumnIndex("sequence")));
        assertEquals(mFakeCount, cursor.getInt(cursor.getColumnIndex("count")));
        assertEquals(0, cursor.getInt(cursor.getColumnIndex("deleted")));

        // Insert another two.
        assertEquals(Uri.parse("content://sms/raw/2"),
                mContentResolver.insert(mRawUri, getFakeRawValue()));
        assertEquals(Uri.parse("content://sms/raw/3"),
                mContentResolver.insert(mRawUri, getFakeRawValue()));

        cursor.close();
    }

    @Test
    @SmallTest
    public void testRawTableDelete() throws Exception {
        assertEquals(Uri.parse("content://sms/raw/1"),
                mContentResolver.insert(mRawUri, getFakeRawValue()));

        // Mark as deleted.
        String where = "reference_number=?";
        String[] whereArgs = {Integer.toString(mFakeRefNumber)};
        assertEquals(1, mContentResolver.delete(mRawUri, where, whereArgs));

        // The row should still be in table, with column "deleted" to be 1.
        Cursor cursor = mSmsProviderTestable.mDeOpenHelper.getReadableDatabase().query(
                "raw", null, null, null, null, null, null);
        assertEquals(1, cursor.getCount());
        cursor.moveToNext();
        assertEquals(1, cursor.getInt(cursor.getColumnIndex("deleted")));
        cursor.close();

        // The deleted row should be purged.
        cursor = mContentResolver.query(mRawUri, null, null, null, null);
        assertEquals(0, cursor.getCount());

        // Permanent delete all rows.
        assertEquals(Uri.parse("content://sms/raw/1"),
                mContentResolver.insert(mRawUri, getFakeRawValue()));
        assertEquals(Uri.parse("content://sms/raw/2"),
                mContentResolver.insert(mRawUri, getFakeRawValue()));
        assertEquals(2, mContentResolver.delete(mRawUriPermanentDelete, null, null));
        cursor = mSmsProviderTestable.mDeOpenHelper.getReadableDatabase().query(
                "raw", null, null, null, null, null, null);
        assertEquals(0, cursor.getCount());
        cursor.close();
    }

    @Test
    public void testInsertUsingManagedProfile() {
        when(mUserManager.isManagedProfile(anyInt())).thenReturn(true);

        try {
            assertNull(mContentResolver.insert(Telephony.Sms.CONTENT_URI, null));
        } catch (Exception e) {
            Log.d(TAG, "Error inserting sms: " + e);
        }
    }

    @Test
    public void testQueryUsingManagedProfile() {
        when(mUserManager.isManagedProfile(anyInt())).thenReturn(true);

        try (Cursor cursor = mContentResolver.query(Telephony.Sms.CONTENT_URI,
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
            assertEquals(0, mContentResolver.update(Telephony.Sms.CONTENT_URI, null, null, null));
        } catch (Exception e) {
            Log.d(TAG, "Exception in updating sms: " + e);
        }
    }

    @Test
    public void testDeleteUsingManagedProfile() {
        when(mUserManager.isManagedProfile(anyInt())).thenReturn(true);

        try {
            assertEquals(0, mContentResolver.delete(Telephony.Sms.CONTENT_URI, null, null));
        } catch (Exception e) {
            Log.d(TAG, "Exception in deleting sms: " + e);
        }
    }

    private ContentValues getFakeRawValue() {
        ContentValues values = new ContentValues();
        values.put("pdu", mFakePdu);
        values.put("date", mDate);
        values.put("destination_port", mFakePort);
        values.put("address", mFakeAddress);
        values.put("display_originating_addr", mFakeOriginatingAddr);
        values.put("reference_number", mFakeRefNumber);
        values.put("sequence", mFakeSequence);
        values.put("count", mFakeCount);
        values.put("message_body", mFakeMessageBody);

        return values;
    }
}
