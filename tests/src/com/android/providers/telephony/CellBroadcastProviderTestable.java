/*
 * Copyright (C) 2019 The Android Open Source Project
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

import androidx.test.InstrumentationRegistry;

public class CellBroadcastProviderTestable extends CellBroadcastProvider {
    private static final String TAG = CellBroadcastProviderTestable.class.getSimpleName();

    public CellBroadcastProviderTestable(PermissionChecker permissionChecker) {
        super(permissionChecker);
    }

    @Override
    public boolean onCreate() {
        // DO NOT call super.onCreate(), otherwise the permission checker will be override.
        Log.d(TAG, "CellBroadcastProviderTestable onCreate");
        mDbHelper = new InMemoryCellBroadcastProviderDbHelper();
        return true;
    }

    public void closeDatabase() {
        mDbHelper.close();
    }

    public static class InMemoryCellBroadcastProviderDbHelper extends SQLiteOpenHelper {
        public InMemoryCellBroadcastProviderDbHelper() {
            super(InstrumentationRegistry.getTargetContext(),
                    null,    // db file name is null for in-memory db
                    null,    // CursorFactory is null by default
                    1);      // db version is no-op for tests
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            Log.d(TAG, "IN MEMORY DB CREATED");
            db.execSQL(getStringForCellBroadcastTableCreation(CELL_BROADCASTS_TABLE_NAME));
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}
    }

    public void initializeForTesting(Context context) {
        ProviderInfo providerInfo = new ProviderInfo();
        providerInfo.authority = CellBroadcastProvider.AUTHORITY;

        // Add context to given carrierIdProvider
        attachInfoForTesting(context, providerInfo);
    }
}
