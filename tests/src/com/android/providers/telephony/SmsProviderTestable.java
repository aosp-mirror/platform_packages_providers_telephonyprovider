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

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

/**
 * A subclass of SmsProvider used for testing on an in-memory database
 */
public class SmsProviderTestable extends SmsProvider {
    private static final String TAG = "SmsProviderTestable";

    @Override
    public boolean onCreate() {
        Log.d(TAG, "onCreate called: mDbHelper = new InMemorySmsProviderDbHelper()");
        mCeOpenHelper = new InMemorySmsProviderDbHelper();
        mDeOpenHelper = new InMemorySmsProviderDbHelper();
        return true;
    }

    // close mDbHelper database object
    protected void closeDatabase() {
        mCeOpenHelper.close();
        mDeOpenHelper.close();
    }

    /**
     * An in memory DB for SmsProviderTestable to use
     */
    public static class InMemorySmsProviderDbHelper extends SQLiteOpenHelper {


        public InMemorySmsProviderDbHelper() {
            super(null,      // no context is needed for in-memory db
                  null,      // db file name is null for in-memory db
                  null,      // CursorFactory is null by default
                  1);        // db version is no-op for tests
            Log.d(TAG, "InMemorySmsProviderDbHelper creating in-memory database");
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            // Set up the sms tables
            Log.d(TAG, "InMemorySmsProviderDbHelper onCreate creating the sms tables");
            db.execSQL(MmsSmsDatabaseHelper.CREATE_SMS_TABLE_STRING);
            db.execSQL(MmsSmsDatabaseHelper.CREATE_RAW_TABLE_STRING);
            db.execSQL(MmsSmsDatabaseHelper.CREATE_ATTACHMENTS_TABLE_STRING);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.d(TAG, "InMemorySmsProviderDbHelper onUpgrade doing nothing");
            return;
        }
    }
}

