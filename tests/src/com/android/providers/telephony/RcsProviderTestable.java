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

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * A subclass of RcsProvider used for testing on an in-memory database
 */
public class RcsProviderTestable extends RcsProvider {

    @Override
    public boolean onCreate() {
        mDbOpenHelper = new InMemoryRcsDatabase();
        return true;
    }

    static class InMemoryRcsDatabase extends SQLiteOpenHelper {
        InMemoryRcsDatabase() {
            super(null,      // no context is needed for in-memory db
                    null,      // db file name is null for in-memory db
                    null,      // CursorFactory is null by default
                    1);        // db version is no-op for tests
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            RcsProviderThreadHelper.createThreadTables(db);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // no-op
        }
    }
}
