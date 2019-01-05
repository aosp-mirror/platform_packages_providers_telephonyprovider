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

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Constants and helpers related to threads for {@link RcsProvider} to keep the code clean.
 *
 * @hide
 */
class RcsProviderThreadHelper {
    static final String ID_COLUMN = "_id";
    static final String THREAD_TABLE = "rcs_thread";
    static final String OWNER_PARTICIPANT = "owner_participant";

    @VisibleForTesting
    public static void createThreadTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + THREAD_TABLE + " (" +
                ID_COLUMN + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                OWNER_PARTICIPANT + " INTEGER " +
                ");");
    }

    static void buildThreadQuery(SQLiteQueryBuilder qb) {
        qb.setTables(THREAD_TABLE);
    }

    static void insert(SQLiteDatabase db, ContentValues values) {
        db.insert(THREAD_TABLE, OWNER_PARTICIPANT, values);
        db.setTransactionSuccessful();
    }

    static int delete(SQLiteDatabase db, String selection, String[] selectionArgs) {
        int deletedRowCount = db.delete(THREAD_TABLE, selection, selectionArgs);
        db.setTransactionSuccessful();
        return deletedRowCount;
    }

    static int update(SQLiteDatabase db, ContentValues values, String selection,
            String[] selectionArgs) {
        int updatedRowCount = db.update(THREAD_TABLE, values, selection, selectionArgs);
        db.setTransactionSuccessful();
        return updatedRowCount;
    }
}
