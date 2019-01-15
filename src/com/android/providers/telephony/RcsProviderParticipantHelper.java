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

import static com.android.providers.telephony.RcsProviderThreadHelper.RCS_THREAD_ID_COLUMN;
import static com.android.providers.telephony.RcsProviderThreadHelper.THREAD_TABLE;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Constants and helpers related to participants for {@link RcsProvider} to keep the code clean.
 *
 * @hide
 */
public class RcsProviderParticipantHelper {
    static final String ID_COLUMN = "_id";
    static final String PARTICIPANT_TABLE = "rcs_participant";
    static final String CANONICAL_ADDRESS_ID_COLUMN = "canonical_address_id";
    static final String RCS_ALIAS_COLUMN = "rcs_alias";

    static final String PARTICIPANT_THREAD_JUNCTION_TABLE = "rcs_thread_participant";
    static final String RCS_PARTICIPANT_ID_COLUMN = "rcs_participant_id";

    @VisibleForTesting
    public static void createParticipantTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + PARTICIPANT_TABLE + " (" +
                ID_COLUMN + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                CANONICAL_ADDRESS_ID_COLUMN + " INTEGER ," +
                RCS_ALIAS_COLUMN + " TEXT, " +
                "FOREIGN KEY(" + CANONICAL_ADDRESS_ID_COLUMN + ") "
                + "REFERENCES canonical_addresses(address)" +
                ");");

        db.execSQL("CREATE TABLE " + PARTICIPANT_THREAD_JUNCTION_TABLE + " (" +
                RCS_THREAD_ID_COLUMN + " INTEGER, " +
                RCS_PARTICIPANT_ID_COLUMN + " INTEGER, " +
                "CONSTRAINT thread_participant PRIMARY KEY("
                + RCS_THREAD_ID_COLUMN + ", " + RCS_PARTICIPANT_ID_COLUMN + "), " +
                "FOREIGN KEY(" + RCS_THREAD_ID_COLUMN
                + ") REFERENCES " + THREAD_TABLE + "(" + ID_COLUMN + "), " +
                "FOREIGN KEY(" + RCS_PARTICIPANT_ID_COLUMN
                + ") REFERENCES " + PARTICIPANT_TABLE + "(" + ID_COLUMN + "))");
    }

    static void buildParticipantsQuery(SQLiteQueryBuilder qb) {
        qb.setTables(PARTICIPANT_TABLE);
    }

    static long insert(SQLiteDatabase db, ContentValues values) {
        long rowId = db.insert(PARTICIPANT_TABLE, ID_COLUMN, values);
        db.setTransactionSuccessful();
        return rowId;
    }

    static int delete(SQLiteDatabase db, String selection,
            String[] selectionArgs) {
        int deletedRows = db.delete(PARTICIPANT_TABLE, selection, selectionArgs);
        db.setTransactionSuccessful();
        return deletedRows;
    }

    static int update(SQLiteDatabase db, ContentValues values,
            String selection, String[] selectionArgs) {
        int updatedRows = db.update(PARTICIPANT_TABLE, values, selection, selectionArgs);
        db.setTransactionSuccessful();
        return updatedRows;
    }
}
