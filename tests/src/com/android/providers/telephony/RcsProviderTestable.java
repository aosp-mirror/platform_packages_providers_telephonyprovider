/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.telephony.TelephonyManager;
import android.test.mock.MockContentResolver;
import android.test.mock.MockContext;

import org.mockito.Mockito;

/**
 * A subclass of RcsProvider used for testing on an in-memory database
 */
public class RcsProviderTestable extends RcsProvider {
    private MockContextWithProvider mockContextWithProvider;

    @Override
    public boolean onCreate() {
        mockContextWithProvider = new MockContextWithProvider(this);
        mDbOpenHelper = new InMemoryRcsDatabase();
        mParticipantHelper = new RcsProviderParticipantHelper(mDbOpenHelper);
        mThreadHelper = new RcsProviderThreadHelper(mDbOpenHelper);
        mMessageHelper = new RcsProviderMessageHelper(mDbOpenHelper);
        mEventHelper = new RcsProviderEventHelper(mDbOpenHelper);
        return true;
    }

    protected void tearDown() {
        mDbOpenHelper.close();
    }

    public SQLiteDatabase getWritableDatabase() {
        return mDbOpenHelper.getWritableDatabase();
    }

    class InMemoryRcsDatabase extends SQLiteOpenHelper {
        InMemoryRcsDatabase() {
            super(null,        // no context is needed for in-memory db
                    null,      // db file name is null for in-memory db
                    null,      // CursorFactory is null by default
                    1);        // db version is no-op for tests
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            MmsSmsDatabaseHelper mmsSmsDatabaseHelper = new MmsSmsDatabaseHelper(
                mockContextWithProvider, null);
            mmsSmsDatabaseHelper.createMmsTables(db);
            mmsSmsDatabaseHelper.createSmsTables(db);
            mmsSmsDatabaseHelper.createCommonTables(db);

            RcsProviderThreadHelper.createThreadTables(db);
            RcsProviderParticipantHelper.createParticipantTables(db);
            RcsProviderMessageHelper.createRcsMessageTables(db);
            RcsProviderEventHelper.createRcsEventTables(db);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // no-op
        }
    }

    static class MockContextWithProvider extends MockContext {
        private final MockContentResolver mResolver;

        MockContextWithProvider(RcsProvider rcsProvider) {
            mResolver = new MockContentResolver();

            // Add authority="rcs" to given smsProvider
            ProviderInfo providerInfo = new ProviderInfo();
            providerInfo.authority = RcsProvider.AUTHORITY;
            rcsProvider.attachInfoForTesting(this, providerInfo);
            mResolver.addProvider(RcsProvider.AUTHORITY, rcsProvider);
        }

        @Override
        public MockContentResolver getContentResolver() {
            return mResolver;
        }

        @Override
        public PackageManager getPackageManager() {
            return Mockito.mock(PackageManager.class);
        }

        @Override
        public Object getSystemService(String name) {
            switch (name) {
                case Context.APP_OPS_SERVICE:
                    return Mockito.mock(AppOpsManager.class);
                case Context.TELEPHONY_SERVICE:
                    return Mockito.mock(TelephonyManager.class);
                default:
                    return null;
            }
        }

        @Override
        public boolean isCredentialProtectedStorage() {
            return false;
        }
    }
}
