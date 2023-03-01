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

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.provider.Telephony;
import android.util.Log;

import static com.android.providers.telephony.SatelliteDatabaseHelper.*;

/**
 * A subclass of SatelliteProvider used for testing on an in-memory database.
 */
public class SatelliteProviderTestable extends SatelliteProvider {
    private static final String TAG = "SatelliteProviderTestable";

    private InMemorySatelliteProviderDbHelper mDbHelper;

    @Override
    public boolean onCreate() {
        Log.d(TAG, "onCreate called: mDbHelper = new InMemorySatelliteProviderDbHelper()");
        mDbHelper = new InMemorySatelliteProviderDbHelper();
        return true;
    }

    // close mDbHelper database object
    protected void closeDatabase() {
        mDbHelper.close();
    }

    @Override
    SQLiteDatabase getReadableDatabase() {
        Log.d(TAG, "getReadableDatabase called" + mDbHelper.getReadableDatabase());
        return mDbHelper.getReadableDatabase();
    }

    @Override
    SQLiteDatabase getWritableDatabase() {
        Log.d(TAG, "getWritableDatabase called" + mDbHelper.getWritableDatabase());
        return mDbHelper.getWritableDatabase();
    }

    public static class InMemorySatelliteProviderDbHelper extends SQLiteOpenHelper {

        public InMemorySatelliteProviderDbHelper() {
            super(null,      // no context is needed for in-memory db
                    null,    // db file name is null for in-memory db
                    null,    // CursorFactory is null by default
                    1);      // db version is no-op for tests
            Log.d(TAG, "InMemorySatelliteProviderDbHelper creating in-memory database");
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            //set up the Datagram table
            Log.d(TAG, "InMemorySatelliteProviderDbHelper onCreate creating the "
                    + "datagram table");
            db.execSQL(getStringForDatagramTableCreation(Telephony.SatelliteDatagrams.TABLE_NAME));
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.d(TAG, "InMemorySatelliteProviderDbHelper onUpgrade doing nothing");
            return;
        }
    }
 }
