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
 * limitations under the License.
 */

package com.android.providers.telephony;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.provider.Telephony.CarrierIdentification;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * This class provides the ability to query the Carrier Identification databases
 * (A.K.A. cid) which is stored in a SQLite database.
 *
 * Each row in carrier identification db consists of matching rule (e.g., MCCMNC, GID1, GID2, PLMN)
 * and its matched carrier id & carrier name. Each carrier either MNO or MVNO could be
 * identified by multiple matching rules but is assigned with a unique ID (cid).
 *
 *
 * This class provides the ability to retrieve the cid of the current subscription.
 * This is done atomically through a query.
 *
 * This class also provides a way to update carrier identifying attributes of an existing entry.
 * Insert entries for new carriers or an existing carrier.
 */
public class CarrierIdProvider extends ContentProvider {

    private static final boolean VDBG = false; // STOPSHIP if true
    private static final String TAG = CarrierIdProvider.class.getSimpleName();

    private static final String DATABASE_NAME = "carrierIdentification.db";
    private static final int DATABASE_VERSION = 2;
    /**
     * The authority string for the CarrierIdProvider
     */
    @VisibleForTesting
    public static final String AUTHORITY = "carrier_identification";

    public static final String CARRIER_ID_TABLE = "carrier_id";

    private static final List<String> CARRIERS_ID_UNIQUE_FIELDS = new ArrayList<>(Arrays.asList(
            CarrierIdentification.MCCMNC,
            CarrierIdentification.GID1,
            CarrierIdentification.GID2,
            CarrierIdentification.PLMN,
            CarrierIdentification.IMSI_PREFIX_XPATTERN,
            CarrierIdentification.SPN,
            CarrierIdentification.APN));

    private CarrierIdDatabaseHelper mDbHelper;

    @VisibleForTesting
    public static String getStringForCarrierIdTableCreation(String tableName) {
        return "CREATE TABLE " + tableName
                + "(_id INTEGER PRIMARY KEY,"
                + CarrierIdentification.MCCMNC + " TEXT NOT NULL,"
                + CarrierIdentification.GID1 + " TEXT,"
                + CarrierIdentification.GID2 + " TEXT,"
                + CarrierIdentification.PLMN + " TEXT,"
                + CarrierIdentification.IMSI_PREFIX_XPATTERN + " TEXT,"
                + CarrierIdentification.SPN + " TEXT,"
                + CarrierIdentification.APN + " TEXT,"
                + CarrierIdentification.NAME + " TEXT,"
                + CarrierIdentification.CID + " INTEGER DEFAULT -1,"
                + "UNIQUE (" + TextUtils.join(", ", CARRIERS_ID_UNIQUE_FIELDS) + "));";
    }

    @VisibleForTesting
    public static String getStringForIndexCreation(String tableName) {
        return "CREATE INDEX IF NOT EXISTS mccmncIndex ON " + tableName + " ("
                + CarrierIdentification.MCCMNC + ");";
    }

    @Override
    public boolean onCreate() {
        Log.d(TAG, "onCreate");
        mDbHelper = new CarrierIdDatabaseHelper(getContext());
        mDbHelper.getReadableDatabase();
        return true;
    }

    @Override
    public String getType(Uri uri) {
        Log.d(TAG, "getType");
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projectionIn, String selection,
                        String[] selectionArgs, String sortOrder) {
        if (VDBG) {
            Log.d(TAG, "query:"
                    + " uri=" + uri
                    + " values=" + Arrays.toString(projectionIn)
                    + " selection=" + selection
                    + " selectionArgs=" + Arrays.toString(selectionArgs));
        }
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(CARRIER_ID_TABLE);

        SQLiteDatabase db = getReadableDatabase();
        return qb.query(db, projectionIn, selection, selectionArgs, null, null, sortOrder);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        final long row = getWritableDatabase().insertOrThrow(CARRIER_ID_TABLE, null, values);
        if (row > 0) {
            final Uri newUri = ContentUris.withAppendedId(CarrierIdentification.CONTENT_URI, row);
            getContext().getContentResolver().notifyChange(newUri, null);
            return newUri;
        }
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        if (VDBG) {
            Log.d(TAG, "delete:"
                    + " uri=" + uri
                    + " selection={" + selection + "}"
                    + " selection=" + selection
                    + " selectionArgs=" + Arrays.toString(selectionArgs));
        }
        final int count = getWritableDatabase().delete(CARRIER_ID_TABLE, selection, selectionArgs);
        Log.d(TAG, "  delete.count=" + count);
        if (count > 0) {
            getContext().getContentResolver().notifyChange(CarrierIdentification.CONTENT_URI, null);
        }
        return count;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        if (VDBG) {
            Log.d(TAG, "update:"
                    + " uri=" + uri
                    + " values={" + values + "}"
                    + " selection=" + selection
                    + " selectionArgs=" + Arrays.toString(selectionArgs));
        }
        final int count = getWritableDatabase().update(CARRIER_ID_TABLE, values, selection,
                selectionArgs);
        Log.d(TAG, "  update.count=" + count);
        if (count > 0) {
            getContext().getContentResolver().notifyChange(CarrierIdentification.CONTENT_URI, null);
        }
        return count;
    }

    /**
     * These methods can be overridden in a subclass for testing CarrierIdProvider using an
     * in-memory database.
     */
    SQLiteDatabase getReadableDatabase() {
        return mDbHelper.getReadableDatabase();
    }
    SQLiteDatabase getWritableDatabase() {
        return mDbHelper.getWritableDatabase();
    }

    private class CarrierIdDatabaseHelper extends SQLiteOpenHelper {
        private final String TAG = CarrierIdDatabaseHelper.class.getSimpleName();

        /**
         * CarrierIdDatabaseHelper carrier identification database helper class.
         * @param context of the user.
         */
        public CarrierIdDatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            Log.d(TAG, "onCreate");
            db.execSQL(getStringForCarrierIdTableCreation(CARRIER_ID_TABLE));
            db.execSQL(getStringForIndexCreation(CARRIER_ID_TABLE));
        }

        public void createCarrierTable(SQLiteDatabase db) {
            db.execSQL(getStringForCarrierIdTableCreation(CARRIER_ID_TABLE));
            db.execSQL(getStringForIndexCreation(CARRIER_ID_TABLE));
        }

        public void dropCarrierTable(SQLiteDatabase db) {
            db.execSQL("DROP TABLE IF EXISTS " + CARRIER_ID_TABLE + ";");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.d(TAG, "dbh.onUpgrade:+ db=" + db + " oldV=" + oldVersion + " newV=" + newVersion);
            if (oldVersion < DATABASE_VERSION) {
                dropCarrierTable(db);
                createCarrierTable(db);
            }
        }
    }
}