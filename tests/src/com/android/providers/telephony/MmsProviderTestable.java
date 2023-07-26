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

import static com.android.providers.telephony.MmsSmsDatabaseHelper.CREATE_ADDR_TABLE_STR;
import static com.android.providers.telephony.MmsSmsDatabaseHelper.CREATE_DRM_TABLE_STR;
import static com.android.providers.telephony.MmsSmsDatabaseHelper.CREATE_PART_TABLE_STR;
import static com.android.providers.telephony.MmsSmsDatabaseHelper.CREATE_PDU_TABLE_STR;
import static com.android.providers.telephony.MmsSmsDatabaseHelper.CREATE_RATE_TABLE_STR;

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

/**
 * A subclass of MmsProvider used for testing on an in-memory database
 */
public class MmsProviderTestable extends MmsProvider {
    private static final String TAG = "MmsProviderTestable";

    @Override
    public boolean onCreate() {
        Log.d(TAG, "onCreate called: mDbHelper = new InMemoryMmsProviderDbHelper()");
        mOpenHelper = new InMemoryMmsProviderDbHelper();
        return true;
    }

    // close mDbHelper database object
    protected void closeDatabase() {
        mOpenHelper.close();
    }

    /**
     * An in memory DB for MmsProviderTestable to use
     */
    public static class InMemoryMmsProviderDbHelper extends SQLiteOpenHelper {


        public InMemoryMmsProviderDbHelper() {
            super(null,      // no context is needed for in-memory db
                    null,      // db file name is null for in-memory db
                    null,      // CursorFactory is null by default
                    1);        // db version is no-op for tests
            Log.d(TAG, "InMemoryMmsProviderDbHelper creating in-memory database");
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            // Set up the mms tables
            Log.d(TAG, "InMemoryMmsProviderDbHelper onCreate creating the mms tables");
            db.execSQL(CREATE_PDU_TABLE_STR);
            db.execSQL(CREATE_ADDR_TABLE_STR);
            db.execSQL(CREATE_PART_TABLE_STR);
            db.execSQL(CREATE_RATE_TABLE_STR);
            db.execSQL(CREATE_DRM_TABLE_STR);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.d(TAG, "InMemorySmsProviderDbHelper onUpgrade doing nothing");
        }
    }
}
