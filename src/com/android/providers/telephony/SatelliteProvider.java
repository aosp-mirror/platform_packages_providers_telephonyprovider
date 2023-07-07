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

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.provider.Telephony;
import android.util.Log;

import java.util.Arrays;

public class SatelliteProvider extends ContentProvider {
    private static final String TAG = "SatelliteProvider";
    private static final boolean VDBG = false; // STOPSHIP if true

    private SatelliteDatabaseHelper mDbHelper;

    @Override
    public boolean onCreate() {
        Log.d(TAG, "onCreate");
        mDbHelper = new SatelliteDatabaseHelper(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        if (VDBG) {
            Log.d(TAG, "query:"
                    + " uri=" + uri
                    + " values=" + Arrays.toString(projection)
                    + " selection=" + selection
                    + " selectionArgs=" + Arrays.toString(selectionArgs));
        }
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(Telephony.SatelliteDatagrams.TABLE_NAME);

        SQLiteDatabase db = getReadableDatabase();
        Cursor c = qb.query(db, projection, selection, selectionArgs, null, null, sortOrder);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        if (VDBG) {
            Log.d(TAG, "insert:"
                    + " uri=" + uri
                    + " values=" + values);
        }
        long row = getWritableDatabase().insertOrThrow(Telephony.SatelliteDatagrams.TABLE_NAME,
                null, values);
        if (row > 0) {
            Uri newUri = ContentUris.withAppendedId(Telephony.SatelliteDatagrams.CONTENT_URI, row);
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
        final int count = getWritableDatabase().delete(Telephony.SatelliteDatagrams.TABLE_NAME,
                selection, selectionArgs);
        Log.d(TAG, "  delete.count=" + count);
        return count;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
       return 0;
    }

    /**
     * These methods can be overridden in a subclass for testing SatelliteProvider using an
     * in-memory database.
     */
    SQLiteDatabase getReadableDatabase() {
        return mDbHelper.getReadableDatabase();
    }
    SQLiteDatabase getWritableDatabase() {
        return mDbHelper.getWritableDatabase();
    }
}
