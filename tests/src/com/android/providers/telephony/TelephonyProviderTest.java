/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.annotation.TargetApi;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.res.Resources;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.ContentObserver;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteDatabase.CursorFactory;
import android.net.Uri;
import android.os.Build;
import android.os.FileUtils;
import android.os.Process;
import android.provider.Telephony.Carriers;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.test.AndroidTestCase;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;
import android.test.mock.MockContext;
import android.test.suitebuilder.annotation.SmallTest;
import android.text.TextUtils;
import android.util.Log;

import com.android.providers.telephony.TelephonyProvider;

import junit.framework.TestCase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;


/**
 * Tests for testing CRUD operations of TelephonyProvider.
 * Uses a MockContentResolver to get permission WRITE_APN_SETTINGS in order to test insert/delete
 * Uses TelephonyProviderTestable to set up in-memory database
 *
 * Build, install and run the tests by running the commands below:
 *     runtest --path <dir or file>
 *     runtest --path <dir or file> --test-method <testMethodName>
 *     e.g.)
 *         runtest --path tests/src/com/android/providers/telephony/TelephonyProviderTest.java \
 *                 --test-method testInsertCarriers
 */
public class TelephonyProviderTest extends TestCase {
    private static final String TAG = "TelephonyProviderTest";

    private MockContextWithProvider mContext;
    private MockContentResolver mContentResolver;
    private TelephonyProviderTestable mTelephonyProviderTestable;

    private int notifyChangeCount;

    private static final String TEST_SUBID = "1";
    private static final String TEST_OPERATOR = "123456";
    // Used to test the path for URL_TELEPHONY_USING_SUBID with subid 0
    private static final Uri CONTENT_URI_WITH_SUBID = Uri.parse(
            "content://telephony/carriers/subId/" + TEST_SUBID);

    // Constants for DPC related tests.
    private static final Uri URI_DPC = Uri.parse("content://telephony/carriers/dpc");
    private static final Uri URI_TELEPHONY = Carriers.CONTENT_URI;
    private static final Uri URI_FILTERED = Uri.parse("content://telephony/carriers/filtered");
    private static final Uri URI_ENFORCE_MANAGED= Uri.parse("content://telephony/carriers/enforce_managed");
    private static final String ENFORCED_KEY = "enforced";

    /**
     * This is used to give the TelephonyProviderTest a mocked context which takes a
     * TelephonyProvider and attaches it to the ContentResolver with telephony authority.
     * The mocked context also gives WRITE_APN_SETTINGS permissions
     */
    private class MockContextWithProvider extends MockContext {
        private final MockContentResolver mResolver;
        private final SharedPreferences mSharedPreferences = mock(SharedPreferences.class);
        private final SharedPreferences.Editor mEditor = mock(SharedPreferences.Editor.class);
        private TelephonyManager mTelephonyManager = mock(TelephonyManager.class);

        public MockContextWithProvider(TelephonyProvider telephonyProvider) {
            mResolver = new MockContentResolver() {
                @Override
                public void notifyChange(Uri uri, ContentObserver observer, boolean syncToNetwork,
                        int userHandle) {
                    notifyChangeCount++;
                }
            };

            // return test subId 0 for all operators
            doReturn(TEST_OPERATOR).when(mTelephonyManager).getSimOperator(anyInt());

            // Add authority="telephony" to given telephonyProvider
            ProviderInfo providerInfo = new ProviderInfo();
            providerInfo.authority = "telephony";

            // Add context to given telephonyProvider
            telephonyProvider.attachInfoForTesting(this, providerInfo);
            Log.d(TAG, "MockContextWithProvider: telephonyProvider.getContext(): "
                    + telephonyProvider.getContext());

            // Add given telephonyProvider to mResolver with authority="telephony" so that
            // mResolver can send queries to mTelephonyProvider
            mResolver.addProvider("telephony", telephonyProvider);
            Log.d(TAG, "MockContextWithProvider: Add telephonyProvider to mResolver");

            when(mSharedPreferences.edit()).thenReturn(mEditor);
        }

        @Override
        public Object getSystemService(String name) {
            if (name.equals(Context.TELEPHONY_SERVICE)) {
                Log.d(TAG, "getSystemService: returning mock TM");
                return mTelephonyManager;
            } else {
                Log.d(TAG, "getSystemService: returning null");
                return null;
            }
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

        @Override
       public SharedPreferences getSharedPreferences(String name, int mode) {
          return mSharedPreferences;
        }

        // Gives permission to write to the APN table within the MockContext
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
        mTelephonyProviderTestable = new TelephonyProviderTestable();
        mContext = new MockContextWithProvider(mTelephonyProviderTestable);
        mContentResolver = (MockContentResolver) mContext.getContentResolver();
        notifyChangeCount = 0;
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        mTelephonyProviderTestable.closeDatabase();
    }

    /**
     * Test bulk inserting, querying;
     * Verify that the inserted values match the result of the query.
     */
    @Test
    @SmallTest
    public void testBulkInsertCarriers() {
        // insert 2 test contentValues
        ContentValues contentValues = new ContentValues();
        final String insertApn = "exampleApnName";
        final String insertName = "exampleName";
        final Integer insertCurrent = 1;
        final String insertNumeric = TEST_OPERATOR;
        contentValues.put(Carriers.APN, insertApn);
        contentValues.put(Carriers.NAME, insertName);
        contentValues.put(Carriers.CURRENT, insertCurrent);
        contentValues.put(Carriers.NUMERIC, insertNumeric);

        ContentValues contentValues2 = new ContentValues();
        final String insertApn2 = "exampleApnName2";
        final String insertName2 = "exampleName2";
        final Integer insertCurrent2 = 1;
        final String insertNumeric2 = "789123";
        contentValues2.put(Carriers.APN, insertApn2);
        contentValues2.put(Carriers.NAME, insertName2);
        contentValues2.put(Carriers.CURRENT, insertCurrent2);
        contentValues2.put(Carriers.NUMERIC, insertNumeric2);

        Log.d(TAG, "testInsertCarriers: Bulk inserting contentValues=" + contentValues
                + ", " + contentValues2);
        ContentValues[] values = new ContentValues[]{ contentValues, contentValues2 };
        int rows = mContentResolver.bulkInsert(Carriers.CONTENT_URI, values);
        assertEquals(2, rows);
        assertEquals(1, notifyChangeCount);

        // get values in table
        final String[] testProjection =
        {
            Carriers.APN,
            Carriers.NAME,
            Carriers.CURRENT,
        };
        final String selection = Carriers.NUMERIC + "=?";
        String[] selectionArgs = { insertNumeric };
        Log.d(TAG, "testInsertCarriers query projection: " + testProjection
                + "\ntestInsertCarriers selection: " + selection
                + "\ntestInsertCarriers selectionArgs: " + selectionArgs);
        Cursor cursor = mContentResolver.query(Carriers.CONTENT_URI,
                testProjection, selection, selectionArgs, null);

        // verify that inserted values match results of query
        assertNotNull(cursor);
        assertEquals(1, cursor.getCount());
        cursor.moveToFirst();
        final String resultApn = cursor.getString(0);
        final String resultName = cursor.getString(1);
        final Integer resultCurrent = cursor.getInt(2);
        assertEquals(insertApn, resultApn);
        assertEquals(insertName, resultName);
        assertEquals(insertCurrent, resultCurrent);
    }

    /**
     * Test inserting, querying, and deleting values in carriers table.
     * Verify that the inserted values match the result of the query and are deleted.
     */
    @Test
    @SmallTest
    public void testInsertCarriers() {
        doSimpleTestForUri(Carriers.CONTENT_URI);
    }

    /**
     * Test inserting, querying, and deleting values in carriers table.
     * Verify that the inserted values match the result of the query and are deleted.
     */
    @Test
    @SmallTest
    public void testInsertCarriersWithSubId() {
        doSimpleTestForUri(CONTENT_URI_WITH_SUBID);
    }

    private void doSimpleTestForUri(Uri uri) {
        // insert test contentValues
        ContentValues contentValues = new ContentValues();
        final String insertApn = "exampleApnName";
        final String insertName = "exampleName";
        final String insertNumeric = TEST_OPERATOR;
        contentValues.put(Carriers.APN, insertApn);
        contentValues.put(Carriers.NAME, insertName);
        contentValues.put(Carriers.NUMERIC, insertNumeric);

        Log.d(TAG, "testInsertCarriers Inserting contentValues: " + contentValues);
        mContentResolver.insert(uri, contentValues);

        // get values in table
        final String[] testProjection =
        {
            Carriers.APN,
            Carriers.NAME,
        };
        final String selection = Carriers.NUMERIC + "=?";
        String[] selectionArgs = { insertNumeric };
        Log.d(TAG, "testInsertCarriers query projection: " + testProjection
                + "\ntestInsertCarriers selection: " + selection
                + "\ntestInsertCarriers selectionArgs: " + selectionArgs);
        Cursor cursor = mContentResolver.query(uri, testProjection, selection, selectionArgs, null);

        // verify that inserted values match results of query
        assertNotNull(cursor);
        assertEquals(1, cursor.getCount());
        cursor.moveToFirst();
        final String resultApn = cursor.getString(0);
        final String resultName = cursor.getString(1);
        assertEquals(insertApn, resultApn);
        assertEquals(insertName, resultName);

        // delete test content
        final String selectionToDelete = Carriers.NUMERIC + "=?";
        String[] selectionArgsToDelete = { insertNumeric };
        Log.d(TAG, "testInsertCarriers deleting selection: " + selectionToDelete
                + "testInsertCarriers selectionArgs: " + selectionArgs);
        int numRowsDeleted = mContentResolver.delete(uri, selectionToDelete, selectionArgsToDelete);
        assertEquals(1, numRowsDeleted);

        // verify that deleted values are gone
        cursor = mContentResolver.query(uri, testProjection, selection, selectionArgs, null);
        assertEquals(0, cursor.getCount());
    }

    @Test
    @SmallTest
    public void testOwnedBy() {
        // insert test contentValues
        ContentValues contentValues = new ContentValues();
        final String insertApn = "exampleApnName";
        final String insertName = "exampleName";
        final String insertNumeric = TEST_OPERATOR;
        final Integer insertOwnedBy = Carriers.OWNED_BY_OTHERS;
        contentValues.put(Carriers.APN, insertApn);
        contentValues.put(Carriers.NAME, insertName);
        contentValues.put(Carriers.NUMERIC, insertNumeric);
        contentValues.put(Carriers.OWNED_BY, insertOwnedBy);

        Log.d(TAG, "testInsertCarriers Inserting contentValues: " + contentValues);
        mContentResolver.insert(Carriers.CONTENT_URI, contentValues);

        // get values in table
        final String[] testProjection =
        {
            Carriers.APN,
            Carriers.NAME,
            Carriers.OWNED_BY,
        };
        final String selection = Carriers.NUMERIC + "=?";
        String[] selectionArgs = { insertNumeric };
        Log.d(TAG, "testInsertCarriers query projection: " + testProjection
                + "\ntestInsertCarriers selection: " + selection
                + "\ntestInsertCarriers selectionArgs: " + selectionArgs);
        Cursor cursor = mContentResolver.query(Carriers.CONTENT_URI,
                testProjection, selection, selectionArgs, null);

        // verify that inserted values match results of query
        assertNotNull(cursor);
        assertEquals(1, cursor.getCount());
        cursor.moveToFirst();
        final String resultApn = cursor.getString(0);
        final String resultName = cursor.getString(1);
        final Integer resultOwnedBy = cursor.getInt(2);
        assertEquals(insertApn, resultApn);
        assertEquals(insertName, resultName);
        // Verify that OWNED_BY is force set to OWNED_BY_OTHERS when inserted with general uri
        assertEquals(insertOwnedBy, resultOwnedBy);

        // delete test content
        final String selectionToDelete = Carriers.NUMERIC + "=?";
        String[] selectionArgsToDelete = { insertNumeric };
        Log.d(TAG, "testInsertCarriers deleting selection: " + selectionToDelete
                + "testInsertCarriers selectionArgs: " + selectionArgs);
        int numRowsDeleted = mContentResolver.delete(Carriers.CONTENT_URI,
                selectionToDelete, selectionArgsToDelete);
        assertEquals(1, numRowsDeleted);

        // verify that deleted values are gone
        cursor = mContentResolver.query(Carriers.CONTENT_URI,
                testProjection, selection, selectionArgs, null);
        assertEquals(0, cursor.getCount());
    }

    /**
     * Test inserting, querying, and deleting values in carriers table.
     * Verify that the inserted values match the result of the query and are deleted.
     */
    @Test
    @SmallTest
    public void testSimTable() {
        // insert test contentValues
        ContentValues contentValues = new ContentValues();
        final int insertSubId = 11;
        final String insertDisplayName = "exampleDisplayName";
        final String insertCarrierName = "exampleCarrierName";
        final String insertIccId = "exampleIccId";
        contentValues.put(SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID, insertSubId);
        contentValues.put(SubscriptionManager.DISPLAY_NAME, insertDisplayName);
        contentValues.put(SubscriptionManager.CARRIER_NAME, insertCarrierName);
        contentValues.put(SubscriptionManager.ICC_ID, insertIccId);

        Log.d(TAG, "testSimTable Inserting contentValues: " + contentValues);
        mContentResolver.insert(SubscriptionManager.CONTENT_URI, contentValues);

        // get values in table
        final String[] testProjection =
        {
            SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID,
            SubscriptionManager.CARRIER_NAME,
        };
        final String selection = SubscriptionManager.DISPLAY_NAME + "=?";
        String[] selectionArgs = { insertDisplayName };
        Log.d(TAG,"\ntestSimTable selection: " + selection
                + "\ntestSimTable selectionArgs: " + selectionArgs.toString());
        Cursor cursor = mContentResolver.query(SubscriptionManager.CONTENT_URI,
                testProjection, selection, selectionArgs, null);

        // verify that inserted values match results of query
        assertNotNull(cursor);
        assertEquals(1, cursor.getCount());
        cursor.moveToFirst();
        final int resultSubId = cursor.getInt(0);
        final String resultCarrierName = cursor.getString(1);
        assertEquals(insertSubId, resultSubId);
        assertEquals(insertCarrierName, resultCarrierName);

        // delete test content
        final String selectionToDelete = SubscriptionManager.DISPLAY_NAME + "=?";
        String[] selectionArgsToDelete = { insertDisplayName };
        Log.d(TAG, "testSimTable deleting selection: " + selectionToDelete
                + "testSimTable selectionArgs: " + selectionArgs);
        int numRowsDeleted = mContentResolver.delete(SubscriptionManager.CONTENT_URI,
                selectionToDelete, selectionArgsToDelete);
        assertEquals(1, numRowsDeleted);

        // verify that deleted values are gone
        cursor = mContentResolver.query(SubscriptionManager.CONTENT_URI,
                testProjection, selection, selectionArgs, null);
        assertEquals(0, cursor.getCount());
    }

    private int parseIdFromInsertedUri(Uri uri) {
        int id = 0;
        if (uri != null) {
            try {
                id = Integer.parseInt(uri.getLastPathSegment());
            }
            catch (NumberFormatException e) {
            }
        }
        assertTrue("Can't parse ID for inserted APN", id != 0);
        return id;
    }

    private int insertApnRecord(Uri uri, String apn, String name, int current, String numeric) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(Carriers.APN, apn);
        contentValues.put(Carriers.NAME, name);
        contentValues.put(Carriers.CURRENT, current);
        contentValues.put(Carriers.NUMERIC, numeric);
        Uri resultUri = mContentResolver.insert(uri, contentValues);
        return parseIdFromInsertedUri(resultUri);
    }

    /**
     * Test URL_ENFORCE_MANAGED and URL_FILTERED works correctly.
     * Verify that when enforce is set true via URL_ENFORCE_MANAGED, only DPC records are returned
     * for URL_FILTERED.
     * Verify that when enforce is set false via URL_ENFORCE_MANAGED, only non-DPC records
     * are returned for URL_FILTERED.
     */
    @Test
    @SmallTest
    public void testEnforceManagedUri() {
        mTelephonyProviderTestable.fakeCallingUid(Process.SYSTEM_UID);

        final int current = 1;
        final String numeric = "123456789";

        // Insert DPC record.
        final String dpcRecordApn = "exampleApnNameDPC";
        final String dpcRecordName = "exampleNameDPC";
        int dpcRecordId = insertApnRecord(URI_DPC, dpcRecordApn, dpcRecordName,
                current, numeric);

        // Insert non-DPC record.
        final String othersRecordApn = "exampleApnNameOTHERS";
        final String othersRecordName = "exampleNameDPOTHERS";
        int othersRecordId = insertApnRecord(URI_TELEPHONY, othersRecordApn, othersRecordName,
                current, numeric);

        // Set enforced = false.
        ContentValues enforceManagedValue = new ContentValues();
        enforceManagedValue.put(ENFORCED_KEY, false);
        Log.d(TAG, "testEnforceManagedUri Updating enforced = false: "
                + enforceManagedValue);
        mContentResolver.update(URI_ENFORCE_MANAGED, enforceManagedValue, "", new String[]{});

        // Verify that enforced is set to false in TelephonyProvider.
        Cursor enforceCursor = mContentResolver.query(URI_ENFORCE_MANAGED,
            null, null, null, null);
        assertNotNull(enforceCursor);
        assertEquals(1, enforceCursor.getCount());
        enforceCursor.moveToFirst();
        assertEquals(0, enforceCursor.getInt(0));

        // Verify URL_FILTERED query only returns non-DPC record.
        final String[] testProjection =
        {
            Carriers._ID,
            Carriers.OWNED_BY
        };
        final String selection = Carriers.NUMERIC + "=?";
        String[] selectionArgs = { numeric };
        Cursor cursorNotEnforced = mContentResolver.query(URI_FILTERED,
            testProjection, selection, selectionArgs, null);
        assertNotNull(cursorNotEnforced);
        assertEquals(1, cursorNotEnforced.getCount());
        cursorNotEnforced.moveToFirst();
        assertEquals(othersRecordId, cursorNotEnforced.getInt(0));
        assertEquals(Carriers.OWNED_BY_OTHERS, cursorNotEnforced.getInt(1));

        // Set enforced = true.
        enforceManagedValue.put(ENFORCED_KEY, true);
        Log.d(TAG, "testEnforceManagedUri Updating enforced = true: "
                + enforceManagedValue);
        mContentResolver.update(URI_ENFORCE_MANAGED, enforceManagedValue, "", new String[]{});

        // Verify that enforced is set to true in TelephonyProvider.
        enforceCursor = mContentResolver.query(URI_ENFORCE_MANAGED,
            null, null, null, null);
        assertNotNull(enforceCursor);
        assertEquals(1, enforceCursor.getCount());
        enforceCursor.moveToFirst();
        assertEquals(1, enforceCursor.getInt(0));

        // Verify URL_FILTERED query only returns DPC record.
        Cursor cursorEnforced = mContentResolver.query(URI_FILTERED,
                testProjection, selection, selectionArgs, null);
        assertNotNull(cursorEnforced);
        assertEquals(1, cursorEnforced.getCount());
        cursorEnforced.moveToFirst();
        assertEquals(dpcRecordId, cursorEnforced.getInt(0));
        assertEquals(Carriers.OWNED_BY_DPC, cursorEnforced.getInt(1));

        // Delete testing records.
        int numRowsDeleted = mContentResolver.delete(URI_TELEPHONY, selection, selectionArgs);
        assertEquals(1, numRowsDeleted);

        numRowsDeleted = mContentResolver.delete(
                Uri.parse(URI_DPC + "/" + dpcRecordId),
                "", new String[]{});
        assertEquals(1, numRowsDeleted);
    }

    @Test
    @SmallTest
    /**
     * Test URL_TELEPHONY cannot insert, query, update or delete DPC records.
     */
    public void testTelephonyUriDPCRecordAccessControl() {
        mTelephonyProviderTestable.fakeCallingUid(Process.SYSTEM_UID);

        final int current = 1;
        final String numeric = "123456789";

        // Insert DPC record.
        final String dpcRecordApn = "exampleApnNameDPC";
        final String dpcRecordName = "exampleNameDPC";
        int dpcRecordId = insertApnRecord(URI_DPC, dpcRecordApn, dpcRecordName,
                current, numeric);

        // Insert non-DPC record.
        final String othersRecordApn = "exampleApnNameOTHERS";
        final String othersRecordName = "exampleNameDPOTHERS";
        int othersRecordId = insertApnRecord(URI_TELEPHONY, othersRecordApn, othersRecordName,
                current, numeric);

        // Verify URL_TELEPHONY query only returns non-DPC record.
        final String[] testProjection =
        {
            Carriers._ID,
            Carriers.APN,
            Carriers.NAME,
            Carriers.CURRENT,
            Carriers.OWNED_BY,
        };
        final String selection = Carriers.NUMERIC + "=?";
        String[] selectionArgs = { numeric };
        Cursor cursorTelephony = mContentResolver.query(URI_TELEPHONY,
                testProjection, selection, selectionArgs, null);
        assertNotNull(cursorTelephony);
        assertEquals(1, cursorTelephony.getCount());
        cursorTelephony.moveToFirst();
        assertEquals(othersRecordId, cursorTelephony.getInt(0));
        assertEquals(othersRecordApn, cursorTelephony.getString(1));
        assertEquals(othersRecordName, cursorTelephony.getString(2));
        assertEquals(current, cursorTelephony.getInt(3));
        assertEquals(Carriers.OWNED_BY_OTHERS, cursorTelephony.getInt(4));

        // Verify URI_TELEPHONY updates only non-DPC records.
        ContentValues contentValuesOthersUpdate = new ContentValues();
        final String othersRecordUpdatedApn = "exampleApnNameOTHERSUpdated";
        final String othersRecordUpdatedName = "exampleNameOTHERSpdated";
        contentValuesOthersUpdate.put(Carriers.APN, othersRecordUpdatedApn);
        contentValuesOthersUpdate.put(Carriers.NAME, othersRecordUpdatedName);
        final int updateCount = mContentResolver.update(URI_TELEPHONY, contentValuesOthersUpdate,
                selection, selectionArgs);
        assertEquals(1, updateCount);
        Cursor cursorNonDPCUpdate = mContentResolver.query(URI_TELEPHONY,
                testProjection, selection, selectionArgs, null);
        Cursor cursorDPCUpdate = mContentResolver.query(URI_DPC,
                testProjection, selection, selectionArgs, null);

        // Verify that non-DPC records are updated.
        assertNotNull(cursorNonDPCUpdate);
        assertEquals(1, cursorNonDPCUpdate.getCount());
        cursorNonDPCUpdate.moveToFirst();
        assertEquals(othersRecordId, cursorNonDPCUpdate.getInt(0));
        assertEquals(othersRecordUpdatedApn, cursorNonDPCUpdate.getString(1));
        assertEquals(othersRecordUpdatedName, cursorNonDPCUpdate.getString(2));

        // Verify that DPC records are not updated.
        assertNotNull(cursorDPCUpdate);
        assertEquals(1, cursorDPCUpdate.getCount());
        cursorDPCUpdate.moveToFirst();
        assertEquals(dpcRecordId, cursorDPCUpdate.getInt(0));
        assertEquals(dpcRecordApn, cursorDPCUpdate.getString(1));
        assertEquals(dpcRecordName, cursorDPCUpdate.getString(2));

        // Verify URI_TELEPHONY deletes only non-DPC records.
        int numRowsDeleted = mContentResolver.delete(URI_TELEPHONY, selection, selectionArgs);
        assertEquals(1, numRowsDeleted);
        Cursor cursorTelephonyRemaining = mContentResolver.query(URI_TELEPHONY,
                testProjection, selection, selectionArgs, null);
        assertNotNull(cursorTelephonyRemaining);
        assertEquals(0, cursorTelephonyRemaining.getCount());
        Cursor cursorDPCDeleted = mContentResolver.query(URI_DPC,
                testProjection, selection, selectionArgs, null);
        assertNotNull(cursorDPCDeleted);
        assertEquals(1, cursorDPCDeleted.getCount());

        // Delete remaining test records.
        numRowsDeleted = mContentResolver.delete(
                Uri.parse(URI_DPC + "/" + dpcRecordId), "", new String[]{});
        assertEquals(1, numRowsDeleted);
    }

    /**
     * Test URL_DPC cannot insert or query non-DPC records.
     * Test URL_DPC_ID cannot update or delete non-DPC records.
     */
    @Test
    @SmallTest
    public void testDpcUri() {
        mTelephonyProviderTestable.fakeCallingUid(Process.SYSTEM_UID);

        final int current = 1;
        final String numeric = "123456789";

        // Insert DPC record.
        final String dpcRecordApn = "exampleApnNameDPC";
        final String dpcRecordName = "exampleNameDPC";
        int dpcRecordId = insertApnRecord(URI_DPC, dpcRecordApn, dpcRecordName,
                current, numeric);

        // Insert non-DPC record.
        final String othersRecordApn = "exampleApnNameOTHERS";
        final String othersRecordName = "exampleNameDPOTHERS";
        int othersRecordId = insertApnRecord(URI_TELEPHONY, othersRecordApn, othersRecordName,
                current, numeric);

        Log.d(TAG, "testDPCIdUri Id for inserted DPC record: " + dpcRecordId);
        Log.d(TAG, "testDPCIdUri Id for inserted non-DPC record: " + othersRecordId);

        // Verify that URI_DPC query only returns DPC records.
        // The columns to get in table.
        final String[] testProjection =
        {
            Carriers._ID,
            Carriers.APN,
            Carriers.NAME,
            Carriers.CURRENT,
            Carriers.OWNED_BY,
        };
        final String selection = Carriers.NUMERIC + "=?";
        String[] selectionArgs = { numeric };
        Cursor cursorDPC = mContentResolver.query(URI_DPC,
                testProjection, selection, selectionArgs, null);

        // Verify that DPC query returns only DPC records.
        assertNotNull(cursorDPC);
        assertEquals(1, cursorDPC.getCount());
        cursorDPC.moveToFirst();
        assertEquals(dpcRecordId, cursorDPC.getInt(0));
        assertEquals(dpcRecordApn, cursorDPC.getString(1));
        assertEquals(dpcRecordName, cursorDPC.getString(2));
        assertEquals(current, cursorDPC.getInt(3));
        assertEquals(Carriers.OWNED_BY_DPC, cursorDPC.getInt(4));

        // Verify that URI_DPC_ID updates only DPC records.
        ContentValues contentValuesDpcUpdate = new ContentValues();
        final String dpcRecordUpdatedApn = "exampleApnNameDPCUpdated";
        final String dpcRecordUpdatedName = "exampleNameDPCUpdated";
        contentValuesDpcUpdate.put(Carriers.APN, dpcRecordUpdatedApn);
        contentValuesDpcUpdate.put(Carriers.NAME, dpcRecordUpdatedName);
        final int updateCount = mContentResolver.update(
                Uri.parse(URI_DPC + "/" + dpcRecordId),
                contentValuesDpcUpdate, null, null);
        assertEquals(1, updateCount);
        Cursor cursorNonDPCUpdate = mContentResolver.query(URI_TELEPHONY,
                testProjection, selection, selectionArgs, null);
        Cursor cursorDPCUpdate = mContentResolver.query(URI_DPC,
                testProjection, selection, selectionArgs, null);

        // Verify that non-DPC records are not updated.
        assertNotNull(cursorNonDPCUpdate);
        assertEquals(1, cursorNonDPCUpdate.getCount());
        cursorNonDPCUpdate.moveToFirst();
        assertEquals(othersRecordId, cursorNonDPCUpdate.getInt(0));
        assertEquals(othersRecordApn, cursorNonDPCUpdate.getString(1));
        assertEquals(othersRecordName, cursorNonDPCUpdate.getString(2));

        // Verify that DPC records are updated.
        assertNotNull(cursorDPCUpdate);
        assertEquals(1, cursorDPCUpdate.getCount());
        cursorDPCUpdate.moveToFirst();
        assertEquals(dpcRecordId, cursorDPCUpdate.getInt(0));
        assertEquals(dpcRecordUpdatedApn, cursorDPCUpdate.getString(1));
        assertEquals(dpcRecordUpdatedName, cursorDPCUpdate.getString(2));

        // Test URI_DPC_ID deletes only DPC records.
        int numRowsDeleted = mContentResolver.delete(
                Uri.parse(URI_DPC + "/" + dpcRecordId),
                null, new String[]{});
        assertEquals(1, numRowsDeleted);
        numRowsDeleted = mContentResolver.delete(
                Uri.parse(URI_DPC + "/" + dpcRecordId),
                null, new String[]{});
        assertEquals(0, numRowsDeleted);

        // Delete remaining test records.
        numRowsDeleted = mContentResolver.delete(
                Uri.parse(URI_TELEPHONY + "/"  + othersRecordId),
                null, new String[]{});
        assertEquals(1, numRowsDeleted);
    }

    /**
     * Verify that SecurityException is thrown if URL_DPC, URL_FILTERED and
     * URL_ENFORCE_MANAGED is accessed from non-SYSTEM_UID.
     */
    @Test
    @SmallTest
    public void testAccessURLDPCThrowSecurityExceptionFromOtherUid() {
        mTelephonyProviderTestable.fakeCallingUid(Process.SYSTEM_UID + 1);

        // Test insert().
        ContentValues contentValuesDPC = new ContentValues();
        try {
            mContentResolver.insert(URI_DPC, contentValuesDPC);
            assertFalse("SecurityException should be thrown when URI_DPC is called from"
                    + " non-SYSTEM_UID", true);
        } catch (SecurityException e) {
            // Should catch SecurityException.
        }

        // Test query().
        try {
            mContentResolver.query(URI_DPC,
                    new String[]{}, "", new String[]{}, null);
            assertFalse("SecurityException should be thrown when URI_DPC is called from"
                    + " non-SYSTEM_UID", true);
        } catch (SecurityException e) {
            // Should catch SecurityException.
        }
        try {
            mContentResolver.query(URI_ENFORCE_MANAGED,
            new String[]{}, "", new String[]{}, null);
            assertFalse("SecurityException should be thrown when URI_ENFORCE_MANAGED is called "
                + "from non-SYSTEM_UID", true);
        } catch (SecurityException e) {
            // Should catch SecurityException.
        }

        // Test update().
        ContentValues contentValuesDPCUpdate = new ContentValues();
        try {
            mContentResolver.update(
                    Uri.parse(URI_DPC + "/1"),
                    contentValuesDPCUpdate, "", new String[]{});
            assertFalse("SecurityException should be thrown when URI_DPC is called"
                    + " from non-SYSTEM_UID", true);
        } catch (SecurityException e) {
            // Should catch SecurityException.
        }
        try {
            mContentResolver.update(URI_ENFORCE_MANAGED, contentValuesDPCUpdate,
                    "", new String[]{});
            assertFalse("SecurityException should be thrown when URI_DPC is called"
                    + " from non-SYSTEM_UID", true);
        } catch (SecurityException e) {
            // Should catch SecurityException.
        }

        // Test delete().
        try {
            mContentResolver.delete(
                    Uri.parse(URI_DPC + "/0"), "", new String[]{});
            assertFalse("SecurityException should be thrown when URI_DPC is called"
                    + " from non-SYSTEM_UID", true);
        } catch (SecurityException e) {
            // Should catch SecurityException.
        }
    }
}
