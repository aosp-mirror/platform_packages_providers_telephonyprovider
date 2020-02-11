/*
 * Copyright (C) 2015 The Android Open Source Project
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

import static android.provider.Telephony.Carriers.*;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

import com.android.internal.annotations.VisibleForTesting;
import com.android.providers.telephony.TelephonyProvider;

/**
 * A subclass of TelephonyProvider used for testing on an in-memory database
 */
public class TelephonyProviderTestable extends TelephonyProvider {
    private static final String TAG = "TelephonyProviderTestable";

    @VisibleForTesting
    public static final String TEST_SPN = "testspn";

    private InMemoryTelephonyProviderDbHelper mDbHelper;
    private MockInjector mMockInjector;

    public TelephonyProviderTestable() {
        this(new MockInjector());
    }

    private TelephonyProviderTestable(MockInjector mockInjector) {
        super(mockInjector);
        mMockInjector = mockInjector;
    }

    @Override
    public boolean onCreate() {
        Log.d(TAG, "onCreate called: mDbHelper = new InMemoryTelephonyProviderDbHelper()");
        mDbHelper = new InMemoryTelephonyProviderDbHelper();
        s_apnSourceServiceExists = false;
        return true;
    }

    // close mDbHelper database object
    protected void closeDatabase() {
        mDbHelper.close();
    }

    @Override
    SQLiteDatabase getReadableDatabase() {
        Log.d(TAG, "getReadableDatabase called");
        return mDbHelper.getReadableDatabase();
    }

    @Override
    SQLiteDatabase getWritableDatabase() {
        Log.d(TAG, "getWritableDatabase called");
        return mDbHelper.getWritableDatabase();
    }

    @Override
    void initDatabaseWithDatabaseHelper(SQLiteDatabase db) {
        Log.d(TAG, "initDatabaseWithDatabaseHelper called; doing nothing");
    }

    @Override
    boolean needApnDbUpdate() {
        Log.d(TAG, "needApnDbUpdate called; returning false");
        return false;
    }

    public void fakeCallingUid(int uid) {
        mMockInjector.fakeCallingUid(uid);
    }

    /**
     * An in memory DB for TelephonyProviderTestable to use
     */
    public static class InMemoryTelephonyProviderDbHelper extends SQLiteOpenHelper {


        public InMemoryTelephonyProviderDbHelper() {
            super(InstrumentationRegistry.getTargetContext(),
                    null,    // db file name is null for in-memory db
                    null,    // CursorFactory is null by default
                    1);      // db version is no-op for tests
            Log.d(TAG, "InMemoryTelephonyProviderDbHelper creating in-memory database");
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            // Set up the carriers table
            Log.d(TAG, "InMemoryTelephonyProviderDbHelper onCreate creating the carriers table");
            db.execSQL(getStringForCarrierTableCreation("carriers"));

            // set up the siminfo table
            Log.d(TAG, "InMemoryTelephonyProviderDbHelper onCreate creating the siminfo table");
            db.execSQL(getStringForSimInfoTableCreation("siminfo"));
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.d(TAG, "InMemoryTelephonyProviderDbHelper onUpgrade doing nothing");
            return;
        }
    }

    static class MockInjector extends Injector {
        private int callingUid = 0;

        @Override
        int binderGetCallingUid() {
            return callingUid;
        }

        void fakeCallingUid(int uid) {
            callingUid = uid;
        }
    }
}
