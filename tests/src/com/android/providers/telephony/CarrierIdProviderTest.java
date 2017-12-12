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

import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.SQLException;
import android.net.Uri;
import android.os.Handler;
import android.provider.Telephony.CarrierIdentification;
import android.test.mock.MockContentResolver;
import android.test.mock.MockContext;
import android.util.Log;

import junit.framework.TestCase;

import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for testing CRUD operations of CarrierIdProvider.
 * Uses CarrierIdProviderTestable to set up in-memory database
 *
 * Build, install and run the tests by running the commands below:
 *     runtest --path <dir or file>
 *     runtest --path <dir or file> --test-method <testMethodName>
 *     e.g.)
 *         runtest --path tests/src/com/android/providers/telephony/CarrierIdProviderTest.java \
 *                 --test-method testInsertCarrierInfo
 */
public class CarrierIdProviderTest extends TestCase {

    private static final String TAG = CarrierIdProviderTest.class.getSimpleName();

    private static final String dummy_mccmnc = "MCCMNC_DUMMY";
    private static final String dummy_gid1 = "GID1_DUMMY";
    private static final String dummy_gid2 = "GID2_DUMMY";
    private static final String dummy_plmn = "PLMN_DUMMY";
    private static final String dummy_imsi_prefix = "IMSI_PREFIX_DUMMY";
    private static final String dummy_spn = "SPN_DUMMY";
    private static final String dummy_apn = "APN_DUMMY";
    private static final String dummy_iccid_prefix = "ICCID_PREFIX_DUMMY";
    private static final String dummy_name = "NAME_DUMMY";
    private static final int dummy_cid = 0;

    private MockContextWithProvider mContext;
    private MockContentResolver mContentResolver;
    private CarrierIdProviderTestable mCarrierIdProviderTestable;
    private FakeContentObserver mContentObserver;

    private class FakeContentResolver extends MockContentResolver {
        @Override
        public void notifyChange(Uri uri, ContentObserver observer, boolean syncToNetwork) {
            super.notifyChange(uri, observer, syncToNetwork);
            Log.d(TAG, "onChanged(uri=" + uri + ")" + observer);
            mContentObserver.dispatchChange(false, uri);
        }
    }

    private class FakeContentObserver extends ContentObserver {
        private boolean changed = false;
        private FakeContentObserver(Handler handler) {
            super(handler);
        }
        @Override
        public void onChange(boolean selfChange) {
            changed = true;
        }
    }

    /**
     * This is used to give the CarrierIdProviderTest a mocked context which takes a
     * CarrierIdProvider and attaches it to the ContentResolver.
     */
    private class MockContextWithProvider extends MockContext {
        private final MockContentResolver mResolver;

        public MockContextWithProvider(CarrierIdProvider carrierIdProvider) {
            mResolver = new FakeContentResolver();

            ProviderInfo providerInfo = new ProviderInfo();
            providerInfo.authority = CarrierIdProvider.AUTHORITY;

            // Add context to given carrierIdProvider
            carrierIdProvider.attachInfoForTesting(this, providerInfo);
            Log.d(TAG, "MockContextWithProvider: carrierIdProvider.getContext(): "
                    + carrierIdProvider.getContext());

            // Add given carrierIdProvider to mResolver, so that mResolver can send queries
            // to the provider.
            mResolver.addProvider(CarrierIdProvider.AUTHORITY, carrierIdProvider);
            Log.d(TAG, "MockContextWithProvider: Add carrierIdProvider to mResolver");
        }

        @Override
        public Object getSystemService(String name) {
            Log.d(TAG, "getSystemService: returning null");
            return null;
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
        mCarrierIdProviderTestable = new CarrierIdProviderTestable();
        mContext = new MockContextWithProvider(mCarrierIdProviderTestable);
        mContentResolver = mContext.getContentResolver();
        mContentObserver = new FakeContentObserver(null);
    }

    @Override
    protected void tearDown() throws Exception {
        mCarrierIdProviderTestable.closeDatabase();
        super.tearDown();
    }

    /**
     * Test inserting values in carrier identification table.
     */
    @Test
    public void testInsertCarrierInfo() {
        try {
            mContentResolver.insert(CarrierIdentification.CONTENT_URI,
                    createCarrierInfoInternal());
            Cursor countCursor = mContentResolver.query(CarrierIdentification.CONTENT_URI,
                    new String[]{"count(*) AS count"},
                    null,
                    null,
                    null);
            countCursor.moveToFirst();
            assertEquals(1, countCursor.getInt(0));
            assertTrue(mContentObserver.changed);
        } catch (Exception e) {
            Log.d(TAG, "Error inserting carrier info:" + e);
        }
    }

    /**
     * Test invalid insertion of duplicate info
     */
    @Test
    public void testDuplicateInsertionCarrierInfo() {
        try {
            //insert same row twice to break uniqueness constraint
            ContentValues contentValues = createCarrierInfoInternal();
            mContentResolver.insert(CarrierIdentification.CONTENT_URI, contentValues);
            mContentResolver.insert(CarrierIdentification.CONTENT_URI, contentValues);
            Assert.fail("should throw an exception for duplicate carrier info");
        } catch (Exception e) {
            Log.d(TAG, "Error inserting carrier info:" + e);
        }
    }

    /**
     * Test invalid insertion of null mccmnc
     */
    @Test
    public void testInvalidInsertionCarrierInfo() {
        try {
            //insert a row with null mnccmnc to break not null constraint
            ContentValues contentValues = new ContentValues();
            contentValues.put(CarrierIdentification.GID1, dummy_gid1);
            mContentResolver.insert(CarrierIdentification.CONTENT_URI, contentValues);
            Assert.fail("should throw an exception for null mccmnc");
        } catch (Exception e) {
            Log.d(TAG, "Error inserting carrier info:" + e);
            assertTrue(e instanceof SQLException);
            assertFalse(mContentObserver.changed);
        }
    }

    /**
     * Test delete.
     */
    @Test
    public void testDeleteCarrierInfo() {
        try {
            mContentResolver.insert(CarrierIdentification.CONTENT_URI, createCarrierInfoInternal());
        } catch (Exception e) {
            Log.d(TAG, "Error inserting carrier info:" + e);
        }
        int numRowsDeleted = -1;
        try {
            String whereClause = CarrierIdentification.MCCMNC + "=?";
            String[] whereArgs = new String[] { dummy_mccmnc };
            numRowsDeleted = mContentResolver.delete(CarrierIdentification.CONTENT_URI, whereClause,
                    whereArgs);
        } catch (Exception e) {
            Log.d(TAG, "Error deleting values:" + e);
        }
        assertEquals(1, numRowsDeleted);
        assertTrue(mContentObserver.changed);
    }

    /**
     * Test update & query.
     */
    @Test
    public void testUpdateCarrierInfo() {
        int cid = -1;
        ContentValues contentValues = createCarrierInfoInternal();

        try {
            mContentResolver.insert(CarrierIdentification.CONTENT_URI, contentValues);
        } catch (Exception e) {
            Log.d(TAG, "Error inserting carrierInfo:" + e);
        }

        try {
            contentValues.put(CarrierIdentification.CID, 1);
            mContentResolver.update(CarrierIdentification.CONTENT_URI, contentValues,
                    CarrierIdentification.MCCMNC + "=?", new String[] { dummy_mccmnc });
        } catch (Exception e) {
            Log.d(TAG, "Error updating values:" + e);
        }

        try {
            Cursor findEntry = mContentResolver.query(CarrierIdentification.CONTENT_URI,
                    new String[] { CarrierIdentification.CID },
                    CarrierIdentification.MCCMNC + "=?", new String[] { dummy_mccmnc },
                    null);
            findEntry.moveToFirst();
            cid = findEntry.getInt(0);
        } catch (Exception e) {
            Log.d(TAG, "Query failed:" + e);
        }
        assertEquals(1, cid);
        assertTrue(mContentObserver.changed);
    }

    @Test
    public void testMultiRowInsertionQuery() {
        ContentValues contentValues = createCarrierInfoInternal();

        try {
            // insert a MVNO
            mContentResolver.insert(CarrierIdentification.CONTENT_URI, contentValues);
            // insert its MNO
            contentValues = new ContentValues();
            contentValues.put(CarrierIdentification.MCCMNC, dummy_mccmnc);
            contentValues.put(CarrierIdentification.CID, 1);
            mContentResolver.insert(CarrierIdentification.CONTENT_URI, contentValues);
        } catch (Exception e) {
            Log.d(TAG, "Error inserting carrierInfo:" + e);
        }

        Cursor findEntry = null;
        String[] columns = {CarrierIdentification.CID, CarrierIdentification.ICCID_PREFIX};
        try {
            findEntry = mContentResolver.query(CarrierIdentification.CONTENT_URI, columns,
                    CarrierIdentification.MCCMNC + "=?", new String[] { dummy_mccmnc },
                    null);
        } catch (Exception e) {
            Log.d(TAG, "Query failed:" + e);
        }
        assertEquals(2, findEntry.getCount());

        try {
            // query based on mccmnc & gid1 & iccid_prefix
            findEntry = mContentResolver.query(CarrierIdentification.CONTENT_URI, columns,
                    CarrierIdentification.MCCMNC + "=? and " + CarrierIdentification.GID1 + "=? and "
                    + CarrierIdentification.ICCID_PREFIX + "=?",
                    new String[] { dummy_mccmnc, dummy_gid1, dummy_iccid_prefix }, null);
        } catch (Exception e) {
            Log.d(TAG, "Query failed:" + e);
        }
        assertEquals(1, findEntry.getCount());
        findEntry.moveToFirst();
        assertEquals(dummy_cid, findEntry.getInt(0));
        assertEquals(dummy_iccid_prefix, findEntry.getString(1));
    }

    private static ContentValues createCarrierInfoInternal() {
        ContentValues contentValues = new ContentValues();
        contentValues.put(CarrierIdentification.MCCMNC, dummy_mccmnc);
        contentValues.put(CarrierIdentification.GID1, dummy_gid1);
        contentValues.put(CarrierIdentification.GID2, dummy_gid2);
        contentValues.put(CarrierIdentification.PLMN, dummy_plmn);
        contentValues.put(CarrierIdentification.IMSI_PREFIX_XPATTERN, dummy_imsi_prefix);
        contentValues.put(CarrierIdentification.SPN, dummy_spn);
        contentValues.put(CarrierIdentification.APN, dummy_apn);
        contentValues.put(CarrierIdentification.ICCID_PREFIX, dummy_iccid_prefix);
        contentValues.put(CarrierIdentification.NAME, dummy_name);
        contentValues.put(CarrierIdentification.CID, dummy_cid);
        return contentValues;
    }
}
