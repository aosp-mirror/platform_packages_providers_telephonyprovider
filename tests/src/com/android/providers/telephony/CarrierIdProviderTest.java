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
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.SQLException;
import android.net.Uri;
import android.os.Handler;
import android.provider.Telephony.CarrierId;
import android.telephony.SubscriptionManager;
import android.test.mock.MockContentResolver;
import android.test.mock.MockContext;
import android.util.Log;

import junit.framework.TestCase;

import org.junit.Assert;
import org.junit.Test;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    private static final String dummy_access_rule =
            "B9CFCE1C47A6AC713442718F15EF55B00B3A6D1A6D48CB46249FA8EB51465350";
    private static final int dummy_cid = 0;

    private MockContextWithProvider mContext;
    private MockContentResolver mContentResolver;
    private CarrierIdProviderTestable mCarrierIdProviderTestable;
    private FakeContentObserver mContentObserver;
    private SharedPreferences mSharedPreferences = mock(SharedPreferences.class);
    private SubscriptionManager subscriptionManager = mock(SubscriptionManager.class);

    private class FakeContentResolver extends MockContentResolver {
        @Override
        public void notifyChange(Uri uri, ContentObserver observer) {
            super.notifyChange(uri, observer);
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

        public MockContextWithProvider(CarrierIdProviderTestable carrierIdProvider) {
            mResolver = new FakeContentResolver();

            carrierIdProvider.initializeForTesting(this);
            Log.d(TAG, "MockContextWithProvider: carrierIdProvider.getContext(): "
                    + carrierIdProvider.getContext());

            // Add given carrierIdProvider to mResolver, so that mResolver can send queries
            // to the provider.
            mResolver.addProvider(CarrierIdProvider.AUTHORITY, carrierIdProvider);
            Log.d(TAG, "MockContextWithProvider: Add carrierIdProvider to mResolver");
        }

        @Override
        public Object getSystemService(String name) {
            switch (name) {
                case Context.TELEPHONY_SUBSCRIPTION_SERVICE:
                    return subscriptionManager;
                default:
                    Log.d(TAG, "getSystemService: returning null");
                    return null;
            }
        }

        @Override
        public MockContentResolver getContentResolver() {
            return mResolver;
        }

        @Override
        public int checkCallingOrSelfPermission(String permission) {
            return PackageManager.PERMISSION_GRANTED;
        }

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            return mSharedPreferences;
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
            mContentResolver.insert(CarrierId.All.CONTENT_URI, createCarrierInfoInternal());
            Cursor countCursor = mContentResolver.query(CarrierId.All.CONTENT_URI,
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
            mContentResolver.insert(CarrierId.All.CONTENT_URI, contentValues);
            mContentResolver.insert(CarrierId.All.CONTENT_URI, contentValues);
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
            contentValues.put(CarrierId.All.GID1, dummy_gid1);
            mContentResolver.insert(CarrierId.All.CONTENT_URI, contentValues);
            Assert.fail("should throw an exception for null mccmnc");
        } catch (SQLException e) {
            Log.d(TAG, "Error inserting carrier info:" + e);
            assertFalse(mContentObserver.changed);
        }
    }

    /**
     * Test delete.
     */
    @Test
    public void testDeleteCarrierInfo() {
        try {
            mContentResolver.insert(CarrierId.All.CONTENT_URI,
                    createCarrierInfoInternal());
        } catch (Exception e) {
            Log.d(TAG, "Error inserting carrier info:" + e);
        }
        int numRowsDeleted = -1;
        try {
            String whereClause = CarrierId.All.MCCMNC + "=?";
            String[] whereArgs = new String[] { dummy_mccmnc };
            numRowsDeleted = mContentResolver.delete(CarrierId.All.CONTENT_URI,
                    whereClause, whereArgs);
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
            mContentResolver.insert(CarrierId.All.CONTENT_URI, contentValues);
        } catch (Exception e) {
            Log.d(TAG, "Error inserting carrierInfo:" + e);
        }

        try {
            contentValues.put(CarrierId.CARRIER_ID, 1);
            mContentResolver.update(CarrierId.All.CONTENT_URI, contentValues,
                    CarrierId.All.MCCMNC + "=?", new String[] { dummy_mccmnc });
        } catch (Exception e) {
            Log.d(TAG, "Error updating values:" + e);
        }

        try {
            Cursor findEntry = mContentResolver.query(CarrierId.All.CONTENT_URI,
                    new String[] { CarrierId.CARRIER_ID},
                    CarrierId.All.MCCMNC + "=?", new String[] { dummy_mccmnc },
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
            mContentResolver.insert(CarrierId.All.CONTENT_URI, contentValues);
            // insert its MNO
            contentValues = new ContentValues();
            contentValues.put(CarrierId.All.MCCMNC, dummy_mccmnc);
            contentValues.put(CarrierId.CARRIER_ID, 1);
            mContentResolver.insert(CarrierId.All.CONTENT_URI, contentValues);
        } catch (Exception e) {
            Log.d(TAG, "Error inserting carrierInfo:" + e);
        }

        Cursor findEntry = null;
        String[] columns = {CarrierId.CARRIER_ID, CarrierId.All.ICCID_PREFIX};
        try {
            findEntry = mContentResolver.query(CarrierId.All.CONTENT_URI, columns,
                    CarrierId.All.MCCMNC + "=?", new String[] { dummy_mccmnc },
                    null);
        } catch (Exception e) {
            Log.d(TAG, "Query failed:" + e);
        }
        assertEquals(2, findEntry.getCount());

        try {
            // query based on mccmnc & gid1 & iccid_prefix
            findEntry = mContentResolver.query(CarrierId.All.CONTENT_URI, columns,
                    CarrierId.All.MCCMNC + "=? and "
                    + CarrierId.All.GID1 + "=? and "
                    + CarrierId.All.ICCID_PREFIX + "=?",
                    new String[] { dummy_mccmnc, dummy_gid1, dummy_iccid_prefix }, null);
        } catch (Exception e) {
            Log.d(TAG, "Query failed:" + e);
        }
        assertEquals(1, findEntry.getCount());
        findEntry.moveToFirst();
        assertEquals(dummy_cid, findEntry.getInt(0));
        assertEquals(dummy_iccid_prefix, findEntry.getString(1));
    }

    @Test
    public void testGetVersion() {
        doReturn(5).when(mSharedPreferences).getInt(eq("version"), anyInt());
        int version = 0;
        try {
            Cursor cursor = mContext.getContentResolver().query(
                    Uri.withAppendedPath(CarrierId.All.CONTENT_URI,
                            "get_version"), null, null, null);
            cursor.moveToFirst();
            version = cursor.getInt(0);
        } catch (Exception e) {
            Log.d(TAG, "Error querying carrier list version:" + e);
        }
        assertEquals(5, version);
    }

    @Test
    public void testUpdateCurrentSubscription() {
        // update carrier id for subId 1
        try {
            ContentValues cv = new ContentValues();
            cv.put(CarrierId.CARRIER_ID, dummy_cid);
            cv.put(CarrierId.CARRIER_NAME, dummy_name);
            when(subscriptionManager.isActiveSubscriptionId(eq(1))).thenReturn(true);
            mContext.getContentResolver().update(Uri.withAppendedPath(CarrierId.CONTENT_URI,
                    "1"), cv, null, null);
        } catch (Exception e) {
            Log.d(TAG, "Error updating current subscription: " + e);
            e.printStackTrace();
        }
        int carrierId = -1;
        String carrierName = null;
        // query carrier id for subId 1
        try {
            final Cursor c = mContext.getContentResolver().query(
                    Uri.withAppendedPath(CarrierId.CONTENT_URI, "1"),
                    new String[] {CarrierId.CARRIER_ID, CarrierId.CARRIER_NAME}, null, null);
            c.moveToFirst();
            carrierId = c.getInt(0);
            carrierName = c.getString(1);
        } catch (Exception e) {
            Log.d(TAG, "Error query current subscription: " + e);
        }
        assertEquals(dummy_cid, carrierId);
        assertEquals(dummy_name, carrierName);

        // query carrier id for subId 2
        int count  = -1;
        try {
            final Cursor c = mContext.getContentResolver().query(
                    Uri.withAppendedPath(CarrierId.CONTENT_URI, "2"),
                    new String[]{CarrierId.CARRIER_ID, CarrierId.CARRIER_NAME}, null, null);
            count = c.getCount();
        } catch (Exception e) {
            Log.d(TAG, "Error query current subscription: " + e);
        }
        assertEquals(0, count);

        // query without subId, expect return carrier id of the default subId
        try {
            final Cursor c = mContext.getContentResolver().query(CarrierId.CONTENT_URI,
                    new String[]{CarrierId.CARRIER_ID, CarrierId.CARRIER_NAME}, null, null);
            c.moveToFirst();
            carrierId = c.getInt(0);
            carrierName = c.getString(1);
        } catch (Exception e) {
            Log.d(TAG, "Error query current subscription: " + e);
        }
        assertEquals(dummy_cid, carrierId);
        assertEquals(dummy_name, carrierName);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testQueryCurrrentSubscription_wrongProjection() {
        mContext.getContentResolver().query(CarrierId.CONTENT_URI,
                new String[]{CarrierId.CARRIER_ID, CarrierId.CARRIER_NAME, CarrierId.All.MCCMNC},
                null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testQueryWithWrongURI() {
        try {
            mContext.getContentResolver().query(Uri.withAppendedPath(
                    CarrierId.CONTENT_URI, "invalid"),
                    new String[]{CarrierId.CARRIER_ID, CarrierId.CARRIER_NAME}, null, null);
            Assert.fail("should throw an exception for wrong uri");
        } catch (IllegalArgumentException ex) {
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testUpdateCurrentSubscription_WrongURI() {
        try {
            ContentValues cv = new ContentValues();
            cv.put(CarrierId.CARRIER_ID, dummy_cid);
            cv.put(CarrierId.CARRIER_NAME, dummy_name);
            mContext.getContentResolver().update(CarrierId.CONTENT_URI, cv, null, null);
            Assert.fail("should throw an exception for wrong uri");
        } catch (IllegalArgumentException ex) {
            assertFalse(mContentObserver.changed);
        }
    }

    private static ContentValues createCarrierInfoInternal() {
        ContentValues contentValues = new ContentValues();
        contentValues.put(CarrierId.All.MCCMNC, dummy_mccmnc);
        contentValues.put(CarrierId.All.GID1, dummy_gid1);
        contentValues.put(CarrierId.All.GID2, dummy_gid2);
        contentValues.put(CarrierId.All.PLMN, dummy_plmn);
        contentValues.put(CarrierId.All.IMSI_PREFIX_XPATTERN, dummy_imsi_prefix);
        contentValues.put(CarrierId.All.SPN, dummy_spn);
        contentValues.put(CarrierId.All.APN, dummy_apn);
        contentValues.put(CarrierId.All.ICCID_PREFIX, dummy_iccid_prefix);
        contentValues.put(CarrierId.CARRIER_NAME, dummy_name);
        contentValues.put(CarrierId.CARRIER_ID, dummy_cid);
        contentValues.put(CarrierId.All.PRIVILEGE_ACCESS_RULE, dummy_access_rule);
        return contentValues;
    }
}
