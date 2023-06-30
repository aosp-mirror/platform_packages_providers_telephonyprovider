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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.provider.BaseColumns;
import android.provider.Telephony;
import android.provider.Telephony.Mms;
import android.provider.Telephony.Mms.Addr;
import android.provider.Telephony.Mms.Part;
import android.provider.Telephony.Mms.Rate;
import android.provider.Telephony.MmsSms.PendingMessages;
import android.provider.Telephony.Threads;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;

import junit.framework.TestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;

@RunWith(JUnit4.class)
public class MmsSmsDatabaseHelperTest {
    private static final String TAG = MmsSmsDatabaseHelperTest.class.getSimpleName();
    // 40 is the first upgrade trigger in onUpgrade
    private static final int BASE_DATABASE_VERSION = 40;

    private Context mContext;
    private MmsSmsDatabaseHelper mMmsSmsDatabaseHelper;
    private SQLiteOpenHelper mInMemoryDbHelper;

    @Before
    public void setUp() throws Exception {
        Log.d(TAG, "setUp()");
        mContext = spy(ApplicationProvider.getApplicationContext());
        mMmsSmsDatabaseHelper = new MmsSmsDatabaseHelper(mContext, null);
        mInMemoryDbHelper = new InMemoryMmsSmsDatabaseHelper();

        doReturn(false).when(mContext).isCredentialProtectedStorage();
    }

    @Test
    public void testDBHelperOnUpgrade_hasSubIdField() {
        Log.d(TAG, "databaseHelperOnUpgrade_hasSubIdField");
        SQLiteDatabase db = mInMemoryDbHelper.getWritableDatabase();
        mMmsSmsDatabaseHelper.onUpgrade(db, BASE_DATABASE_VERSION,
                MmsSmsDatabaseHelper.DATABASE_VERSION);

        // Following tables must have sub_id column in the upgraded DB.
        String[] upgradedColumns;
        try (Cursor cursor = db.query(MmsSmsProvider.TABLE_THREADS, null, null, null,
                null, null, null)) {
            assertNotNull(cursor);
            upgradedColumns = cursor.getColumnNames();
            Log.d(TAG, "Table: " + MmsSmsProvider.TABLE_THREADS
                    + " columns: " + Arrays.toString(upgradedColumns));
            assertTrue(Arrays.asList(upgradedColumns)
                    .contains(Telephony.ThreadsColumns.SUBSCRIPTION_ID));
        }

        try (Cursor cursor = db.query(MmsProvider.TABLE_PART, null, null, null,
                null, null, null)) {
            assertNotNull(cursor);
            upgradedColumns = cursor.getColumnNames();
            Log.d(TAG, "Table: " + MmsProvider.TABLE_PART
                    + " columns: " + Arrays.toString(upgradedColumns));
            assertTrue(Arrays.asList(upgradedColumns).contains(Mms.Part.SUBSCRIPTION_ID));
        }

        try (Cursor cursor = db.query(SmsProvider.TABLE_CANONICAL_ADDRESSES, null, null, null,
                null, null, null)) {
            assertNotNull(cursor);
            upgradedColumns = cursor.getColumnNames();
            Log.d(TAG, "Table: " + SmsProvider.TABLE_CANONICAL_ADDRESSES
                    + " columns: " + Arrays.toString(upgradedColumns));
            assertTrue(Arrays.asList(upgradedColumns).contains(
                    Telephony.CanonicalAddressesColumns.SUBSCRIPTION_ID));
        }

        try (Cursor cursor = db.query(SmsProvider.TABLE_ATTACHMENTS, null, null, null,
                null, null, null)) {
            assertNotNull(cursor);
            upgradedColumns = cursor.getColumnNames();
            Log.d(TAG, "Table: " + SmsProvider.TABLE_ATTACHMENTS
                    + " columns: " + Arrays.toString(upgradedColumns));
            assertTrue(Arrays.asList(upgradedColumns).contains("sub_id"));
        }

        try (Cursor cursor = db.query(MmsProvider.TABLE_ADDR, null, null, null,
                null, null, null)) {
            assertNotNull(cursor);
            upgradedColumns = cursor.getColumnNames();
            Log.d(TAG, "Table: " + MmsProvider.TABLE_ADDR
                    + " columns: " + Arrays.toString(upgradedColumns));
            assertTrue(Arrays.asList(upgradedColumns).contains(Addr.SUBSCRIPTION_ID));
        }

        try (Cursor cursor = db.query(MmsProvider.TABLE_RATE, null, null, null,
                null, null, null)) {
            assertNotNull(cursor);
            upgradedColumns = cursor.getColumnNames();
            Log.d(TAG, "Table: " + MmsProvider.TABLE_RATE
                    + " columns: " + Arrays.toString(upgradedColumns));
            assertTrue(Arrays.asList(upgradedColumns).contains(Rate.SUBSCRIPTION_ID));
        }

        try (Cursor cursor = db.query(MmsProvider.TABLE_DRM, null, null, null,
                null, null, null)) {
            assertNotNull(cursor);
            upgradedColumns = cursor.getColumnNames();
            Log.d(TAG, "Table: " + MmsProvider.TABLE_DRM
                    + " columns: " + Arrays.toString(upgradedColumns));
            assertTrue(Arrays.asList(upgradedColumns).contains("sub_id"));
        }

        try (Cursor cursor = db.query(MmsProvider.TABLE_WORDS, null, null, null,
                null, null, null)) {
            assertNotNull(cursor);
            upgradedColumns = cursor.getColumnNames();
            Log.d(TAG, "Table: " + MmsProvider.TABLE_WORDS
                    + " columns: " + Arrays.toString(upgradedColumns));
            assertTrue(Arrays.asList(upgradedColumns).contains("sub_id"));
        }

        try (Cursor cursor = db.query(SmsProvider.TABLE_SR_PENDING, null, null, null,
                null, null, null)) {
            assertNotNull(cursor);
            upgradedColumns = cursor.getColumnNames();
            Log.d(TAG, "Table: " + SmsProvider.TABLE_SR_PENDING
                    + " columns: " + Arrays.toString(upgradedColumns));
            assertTrue(Arrays.asList(upgradedColumns).contains("sub_id"));
        }
    }

    /**
     * Helper for an in-memory DB used to test MmsSmsDatabaseHelper
     *
     * This in-memory DB is passed to
     * {@link MmsSmsDatabaseHelper#onUpgrade(SQLiteDatabase, int, int)} to test onUpgrade() actual
     * function without using the actual mmssms.db file.
     */
    private static class InMemoryMmsSmsDatabaseHelper extends SQLiteOpenHelper {
        public InMemoryMmsSmsDatabaseHelper() {
            super(null,     // no context is needed for in-memory db
                    null,    // db file name is null for in-memory db
                    null,    // CursorFactory is null by default
                    1);      // in-memory db version doesn't seem to matter
            Log.d(TAG, "InMemoryMmsSmsDatabaseHelper creating in-memory database");
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            Log.d(TAG, "InMemoryMmsSmsDatabaseHelper onCreate()");
            // Set up MMS and SMS tables without any fields added in onUpgrade
            // since these are the initial fields, there is no need to update this test fixture in
            // the future
            createMmsTables(db);
            createSmsTables(db);
            createCommonTables(db);
        }

        private void createMmsTables(SQLiteDatabase db) {
            Log.d(TAG, "InMemoryMmsSmsDatabaseHelper createMmsTables()");
            // Set up MMS tables without any fields added in onUpgrade
            // since these are the initial fields, there is no need to update this test fixture in
            // the future
            db.execSQL("CREATE TABLE " + MmsProvider.TABLE_PDU + " (" +
                    Mms._ID + " INTEGER PRIMARY KEY," +
                    Mms.THREAD_ID + " INTEGER," +
                    Mms.DATE + " INTEGER," +
                    Mms.MESSAGE_BOX + " INTEGER," +
                    Mms.READ + " INTEGER DEFAULT 0," +
                    Mms.MESSAGE_ID + " TEXT," +
                    Mms.SUBJECT + " TEXT," +
                    Mms.SUBJECT_CHARSET + " INTEGER," +
                    Mms.CONTENT_TYPE + " TEXT," +
                    Mms.CONTENT_LOCATION + " TEXT," +
                    Mms.EXPIRY + " INTEGER," +
                    Mms.MESSAGE_CLASS + " TEXT," +
                    Mms.MESSAGE_TYPE + " INTEGER," +
                    Mms.MMS_VERSION + " INTEGER," +
                    Mms.MESSAGE_SIZE + " INTEGER," +
                    Mms.PRIORITY + " INTEGER," +
                    Mms.READ_REPORT + " INTEGER," +
                    Mms.REPORT_ALLOWED + " INTEGER," +
                    Mms.RESPONSE_STATUS + " INTEGER," +
                    Mms.STATUS + " INTEGER," +
                    Mms.TRANSACTION_ID + " TEXT," +
                    Mms.RETRIEVE_STATUS + " INTEGER," +
                    Mms.RETRIEVE_TEXT + " TEXT," +
                    Mms.RETRIEVE_TEXT_CHARSET + " INTEGER," +
                    Mms.READ_STATUS + " INTEGER," +
                    Mms.CONTENT_CLASS + " INTEGER," +
                    Mms.RESPONSE_TEXT + " TEXT," +
                    Mms.DELIVERY_TIME + " INTEGER," +
                    Mms.DELIVERY_REPORT + " INTEGER);");

            db.execSQL("CREATE TABLE " + MmsProvider.TABLE_ADDR + " (" +
                    Addr._ID + " INTEGER PRIMARY KEY," +
                    Addr.MSG_ID + " INTEGER," +
                    Addr.CONTACT_ID + " INTEGER," +
                    Addr.ADDRESS + " TEXT," +
                    Addr.TYPE + " INTEGER," +
                    Addr.CHARSET + " INTEGER);");

            db.execSQL("CREATE TABLE " + MmsProvider.TABLE_PART + " (" +
                    Part._ID + " INTEGER PRIMARY KEY," +
                    Part.MSG_ID + " INTEGER," +
                    Part.SEQ + " INTEGER DEFAULT 0," +
                    Part.CONTENT_TYPE + " TEXT," +
                    Part.NAME + " TEXT," +
                    Part.CHARSET + " INTEGER," +
                    Part.CONTENT_DISPOSITION + " TEXT," +
                    Part.FILENAME + " TEXT," +
                    Part.CONTENT_ID + " TEXT," +
                    Part.CONTENT_LOCATION + " TEXT," +
                    Part.CT_START + " INTEGER," +
                    Part.CT_TYPE + " TEXT," +
                    Part._DATA + " TEXT);");

            db.execSQL("CREATE TABLE " + MmsProvider.TABLE_RATE + " (" +
                    Rate.SENT_TIME + " INTEGER);");

            db.execSQL("CREATE TABLE " + MmsProvider.TABLE_DRM + " (" +
                    BaseColumns._ID + " INTEGER PRIMARY KEY," +
                    "_data TEXT);");
        }

        private void createSmsTables(SQLiteDatabase db) {
            Log.d(TAG, "InMemoryMmsSmsDatabaseHelper createSmsTables()");
            // Set up SMS tables without any fields added in onUpgrade
            // since these are the initial fields, there is no need to update this test fixture in
            // the future
            db.execSQL("CREATE TABLE sms (" +
                    "_id INTEGER PRIMARY KEY," +
                    "thread_id INTEGER," +
                    "address TEXT," +
                    "person INTEGER," +
                    "date INTEGER," +
                    "protocol INTEGER," +
                    "read INTEGER DEFAULT 0," +
                    "status INTEGER DEFAULT -1," +
                    "type INTEGER," +
                    "reply_path_present INTEGER," +
                    "subject TEXT," +
                    "body TEXT," +
                    "service_center TEXT);");

            db.execSQL("CREATE TABLE raw (" +
                    "_id INTEGER PRIMARY KEY," +
                    "date INTEGER," +
                    "reference_number INTEGER," + // one per full message
                    "count INTEGER," + // the number of parts
                    "sequence INTEGER," + // the part number of this message
                    "destination_port INTEGER," +
                    "address TEXT," +
                    "pdu TEXT);"); // the raw PDU for this part

            db.execSQL("CREATE TABLE attachments (" +
                    "sms_id INTEGER," +
                    "content_url TEXT," +
                    "offset INTEGER);");

            db.execSQL("CREATE TABLE sr_pending (" +
                    "reference_number INTEGER," +
                    "action TEXT," +
                    "data TEXT);");
        }

        private void createCommonTables(SQLiteDatabase db) {
            Log.d(TAG, "InMemoryMmsSmsDatabaseHelper createCommonTables()");
            // Set up common tables without any fields added in onUpgrade
            // since these are the initial fields, there is no need to update this test fixture in
            // the future
            db.execSQL("CREATE TABLE canonical_addresses (" +
                    "_id INTEGER PRIMARY KEY," +
                    "address TEXT);");

            db.execSQL("CREATE TABLE threads (" +
                    Threads._ID + " INTEGER PRIMARY KEY," +
                    Threads.DATE + " INTEGER DEFAULT 0," +
                    Threads.MESSAGE_COUNT + " INTEGER DEFAULT 0," +
                    Threads.RECIPIENT_IDS + " TEXT," +
                    Threads.SNIPPET + " TEXT," +
                    Threads.SNIPPET_CHARSET + " INTEGER DEFAULT 0," +
                    Threads.READ + " INTEGER DEFAULT 1," +
                    Threads.TYPE + " INTEGER DEFAULT 0," +
                    Threads.ERROR + " INTEGER DEFAULT 0);");

            db.execSQL("CREATE TABLE " + MmsSmsProvider.TABLE_PENDING_MSG + " (" +
                    PendingMessages._ID + " INTEGER PRIMARY KEY," +
                    PendingMessages.PROTO_TYPE + " INTEGER," +
                    PendingMessages.MSG_ID + " INTEGER," +
                    PendingMessages.MSG_TYPE + " INTEGER," +
                    PendingMessages.ERROR_TYPE + " INTEGER," +
                    PendingMessages.ERROR_CODE + " INTEGER," +
                    PendingMessages.RETRY_INDEX + " INTEGER NOT NULL DEFAULT 0," +
                    PendingMessages.DUE_TIME + " INTEGER," +
                    PendingMessages.LAST_TRY + " INTEGER);");
        }


        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.d(TAG, "InMemoryMmsSmsDatabaseHelper onUpgrade doing nothing");
            return;
        }
    }
}
