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

import com.android.providers.telephony.CarrierProvider;
import static com.android.providers.telephony.CarrierDatabaseHelper.*;

/**
 * A subclass of TelephonyProvider used for testing on an in-memory database
 */
public class CarrierProviderTestable extends CarrierProvider {
    private static final String TAG = "CarrierProviderTestable";

    private InMemoryCarrierProviderDbHelper mDbHelper;

    @Override
    public boolean onCreate() {
        Log.d(TAG, "onCreate called: mDbHelper = new InMemoryCarrierProviderDbHelper()");
        mDbHelper = new InMemoryCarrierProviderDbHelper();
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

    /**
     * An in memory DB for CarrierProviderTestable to use
     */
    public static class InMemoryCarrierProviderDbHelper extends SQLiteOpenHelper {


        public InMemoryCarrierProviderDbHelper() {
            super(null,      // no context is needed for in-memory db
                    null,    // db file name is null for in-memory db
                    null,    // CursorFactory is null by default
                    1);      // db version is no-op for tests
            Log.d(TAG, "InMemoryCarrierProviderDbHelper creating in-memory database");
        }

        @Override
        public void onCreate(SQLiteDatabase db) {

            //set up the Carrier key table
            Log.d(TAG, "InMemoryCarrierProviderDbHelper onCreate creating the carrier key table");
            db.execSQL(getStringForCarrierKeyTableCreation(CARRIER_KEY_TABLE));
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.d(TAG, "InMemoryCarrierProviderDbHelper onUpgrade doing nothing");
            return;
        }
    }
}
