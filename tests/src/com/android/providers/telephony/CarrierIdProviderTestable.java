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

import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

/**
 * A subclass of CarrierIdProvider used for testing on an in-memory database
 */
public class CarrierIdProviderTestable extends CarrierIdProvider {
    private static final String TAG = CarrierIdProviderTestable.class.getSimpleName();

    private InMemoryCarrierIdProviderDbHelper mDbHelper;

    @Override
    public boolean onCreate() {
        Log.d(TAG, "onCreate called: mDbHelper = new InMemoryCarrierIdProviderDbHelper()");
        mDbHelper = new InMemoryCarrierIdProviderDbHelper();
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

    void initializeForTesting(Context context) {
        ProviderInfo providerInfo = new ProviderInfo();
        providerInfo.authority = CarrierIdProvider.AUTHORITY;

        // Add context to given carrierIdProvider
        attachInfoForTesting(context, providerInfo);
    }

    /**
     * An in memory DB for CarrierIdProviderTestable to use
     */
    public static class InMemoryCarrierIdProviderDbHelper extends SQLiteOpenHelper {
        public InMemoryCarrierIdProviderDbHelper() {
            super(null,      // no context is needed for in-memory db
                  null,      // db file name is null for in-memory db
                  null,      // CursorFactory is null by default
                  1);        // db version is no-op for tests
            Log.d(TAG, "InMemoryCarrierIdProviderDbHelper creating in-memory database");
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            //set up the Carrier id table
            Log.d(TAG, "InMemoryCarrierIdProviderDbHelper onCreate creating the carrier infp table");
            db.execSQL(CarrierIdProvider.getStringForCarrierIdTableCreation(
                    CarrierIdProvider.CARRIER_ID_TABLE));
            db.execSQL(CarrierIdProvider.getStringForIndexCreation(
                    CarrierIdProvider.CARRIER_ID_TABLE));
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.d(TAG, "InMemoryCarrierIdProviderDbHelper onUpgrade doing nothing");
            return;
        }
    }
}
