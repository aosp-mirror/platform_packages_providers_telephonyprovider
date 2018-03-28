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
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.util.Log;

import android.content.ContentUris;
import android.database.SQLException;

import java.util.Arrays;

/**
 * The class to provide base facility to access Carrier related content,
 * which is stored in a SQLite database.
 */
public class CarrierProvider extends ContentProvider {

    private static final boolean VDBG = false; // STOPSHIP if true
    private static final String TAG = "CarrierProvider";

    private CarrierDatabaseHelper mDbHelper;
    private SQLiteDatabase mDatabase;

    static final String PROVIDER_NAME = "carrier_information";
    static final String URL = "content://" + PROVIDER_NAME + "/carrier";
    static final Uri CONTENT_URI = Uri.parse(URL);

    @Override
    public boolean onCreate() {
        Log.d(TAG, "onCreate");
        mDbHelper = new CarrierDatabaseHelper(getContext());
        return (mDatabase == null ? false : true);
    }

    @Override
    public String getType(Uri uri) {
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
        qb.setTables(CarrierDatabaseHelper.CARRIER_KEY_TABLE);

        SQLiteDatabase db = getReadableDatabase();
        Cursor c = qb.query(db, projectionIn, selection, selectionArgs, null, null, sortOrder);
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        values.put(CarrierDatabaseHelper.LAST_MODIFIED, System.currentTimeMillis());
        long row = getWritableDatabase().insertOrThrow(CarrierDatabaseHelper.CARRIER_KEY_TABLE,
                null, values);
        if (row > 0) {
            Uri newUri = ContentUris.withAppendedId(CONTENT_URI, row);
            getContext().getContentResolver().notifyChange(CONTENT_URI, null);
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
        final int count = getWritableDatabase().delete(CarrierDatabaseHelper.CARRIER_KEY_TABLE,
                selection, selectionArgs);
        Log.d(TAG, "  delete.count=" + count);
        return count;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        values.put(CarrierDatabaseHelper.LAST_MODIFIED, System.currentTimeMillis());
        if (VDBG) {
            Log.d(TAG, "update:"
                    + " uri=" + uri
                    + " values={" + values + "}"
                    + " selection=" + selection
                    + " selectionArgs=" + Arrays.toString(selectionArgs));
        }
        final int count = getWritableDatabase().update(CarrierDatabaseHelper.CARRIER_KEY_TABLE,
                values, selection, selectionArgs);
        if (count > 0) {
            getContext().getContentResolver().notifyChange(CONTENT_URI, null);
        }
        Log.d(TAG, "  update.count=" + count);
        return count;
    }

    /**
     * These methods can be overridden in a subclass for testing TelephonyProvider using an
     * in-memory database.
     */
    SQLiteDatabase getReadableDatabase() {
        return mDbHelper.getReadableDatabase();
    }
    SQLiteDatabase getWritableDatabase() {
        return mDbHelper.getWritableDatabase();
    }
}
