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

import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.res.Resources;
import android.database.Cursor;
import android.provider.Telephony;
import android.test.mock.MockContentResolver;
import android.test.mock.MockContext;
import android.text.TextUtils;
import android.util.Log;

import junit.framework.TestCase;

import org.junit.Test;

public class SatelliteProviderTest extends TestCase {
    private static final String TAG = "SatelliteProviderTest";

    private MockContextWithProvider mContext;
    private MockContentResolver mContentResolver;
    private SatelliteProviderTestable mSatelliteProviderTestable;

    /**
     * This is used to give the SatelliteProviderTest a mocked context which takes a
     * SatelliteProvider and attaches it to the ContentResolver.
     */
    private class MockContextWithProvider extends MockContext {
        private final MockContentResolver mResolver;

        public MockContextWithProvider(SatelliteProvider satelliteProvider) {
            mResolver = new MockContentResolver();

            ProviderInfo providerInfo = new ProviderInfo();
            providerInfo.authority = Telephony.SatelliteDatagrams.PROVIDER_NAME;

            // Add context to given satelliteProvider
            satelliteProvider.attachInfoForTesting(this, providerInfo);
            Log.d(TAG, "MockContextWithProvider: satelliteProvider.getContext(): "
                    + satelliteProvider.getContext());

            // Add given satelliteProvider to mResolver, so that mResolver can send queries
            // to the provider.
            mResolver.addProvider(Telephony.SatelliteDatagrams.PROVIDER_NAME, satelliteProvider);
            Log.d(TAG, "MockContextWithProvider: Add satelliteProvider to mResolver");
        }

        @Override
        public Object getSystemService(String name) {
            Log.d(TAG, "getSystemService: returning null");
            return null;
        }

        @Override
        public Resources getResources() {
            Log.d(TAG, "getResources: returning null");
            return null;
        }

        @Override
        public MockContentResolver getContentResolver() {
            return mResolver;
        }

        // Gives permission to write to the apn table within the MockContext
        @Override
        public int checkCallingOrSelfPermission(String permission) {
            if (TextUtils.equals(permission, "android.permission.WRITE_APN_SETTINGS")) {
                Log.d(TAG, "checkCallingOrSelfPermission: permission=" + permission
                        + ", returning PackageManager.PERMISSION_GRANTED");
                return PackageManager.PERMISSION_GRANTED;
            } else {
                Log.d(TAG, "checkCallingOrSelfPermission: permission=" + permission
                        + ", returning PackageManager.PERMISSION_DENIED");
                return PackageManager.PERMISSION_DENIED;
            }
        }
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mSatelliteProviderTestable = new SatelliteProviderTestable();
        mContext = new MockContextWithProvider(mSatelliteProviderTestable);
        mContentResolver = (MockContentResolver) mContext.getContentResolver();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        mSatelliteProviderTestable.closeDatabase();
    }

    @Test
    public void testInsertDatagram() {
        long testDatagramId = 1;
        String testDatagram = "testInsertDatagram";
        ContentValues contentValues = new ContentValues();
        contentValues.put(Telephony.SatelliteDatagrams.COLUMN_UNIQUE_KEY_DATAGRAM_ID,
                testDatagramId);
        contentValues.put(Telephony.SatelliteDatagrams.COLUMN_DATAGRAM, testDatagram.getBytes());

        try {
            mContentResolver.insert(Telephony.SatelliteDatagrams.CONTENT_URI, contentValues);
        } catch (Exception e){
            Log.d(TAG, "Error inserting satellite datagram:" + e);
        }

        String whereClause = (Telephony.SatelliteDatagrams.COLUMN_UNIQUE_KEY_DATAGRAM_ID
                + "=" +testDatagramId);
        try (Cursor cursor = mContentResolver.query(Telephony.SatelliteDatagrams.CONTENT_URI,
                null, whereClause, null, null)) {
            cursor.moveToFirst();
            byte[] datagram = cursor.getBlob(0);
            assertEquals(testDatagram, new String(datagram));
        } catch (Exception e) {
            Log.d(TAG, "Exception in getting count:" + e);
        }
    }

    @Test
    public void testDeleteDatagram() {
        long testDatagramId = 10;
        String testDatagram = "testDeleteDatagram";
        ContentValues contentValues = new ContentValues();
        contentValues.put(Telephony.SatelliteDatagrams.COLUMN_UNIQUE_KEY_DATAGRAM_ID,
                testDatagramId);
        contentValues.put(Telephony.SatelliteDatagrams.COLUMN_DATAGRAM, testDatagram.getBytes());

        try {
            mContentResolver.insert(Telephony.SatelliteDatagrams.CONTENT_URI, contentValues);
        } catch (Exception e){
            Log.d(TAG, "Error inserting satellite datagram:" + e);
        }

        int numRowsDeleted = -1;
        String whereClause = (Telephony.SatelliteDatagrams.COLUMN_UNIQUE_KEY_DATAGRAM_ID
                + "=" +testDatagramId);
        try {
            numRowsDeleted = mContentResolver.delete(Telephony.SatelliteDatagrams.CONTENT_URI,
                    whereClause, null);
        } catch (Exception e) {
            Log.d(TAG, "Error deleting values:" + e);
        }
        assertEquals(1, numRowsDeleted);

        try (Cursor cursor = mContentResolver.query(Telephony.SatelliteDatagrams.CONTENT_URI,
                null, whereClause, null, null)) {
            assertNotNull(cursor);
            assertEquals(0, cursor.getCount());
        } catch (Exception e) {
            Log.d(TAG, "Exception in getting count:" + e);
        }
    }
}
