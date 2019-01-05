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

import static com.android.providers.telephony.RcsProvider.TAG;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.ims.RcsMessageStoreController;

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

    static final String CANONICAL_ADDRESSES_TABLE = "canonical_addresses";
    static final String ADDRESS_COLUMN = "address";

    private static final int NO_EXISTING_ADDRESS = Integer.MIN_VALUE;

    @VisibleForTesting
    public static void createParticipantTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + PARTICIPANT_TABLE + " (" +
                ID_COLUMN + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                CANONICAL_ADDRESS_ID_COLUMN + " INTEGER ," +
                RCS_ALIAS_COLUMN + " TEXT, " +
                "FOREIGN KEY(" + CANONICAL_ADDRESS_ID_COLUMN + ") "
                + "REFERENCES canonical_addresses(address)" +
                ");");
    }

    static void buildParticipantsQuery(SQLiteQueryBuilder qb) {
        qb.setTables(PARTICIPANT_TABLE);
    }

    static void insert(SQLiteDatabase db, ContentValues values) {
        // TODO - implement
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

    private static int getCanonicalAddressId(SQLiteDatabase db, ContentValues values) {
        String address = values.getAsString(RcsMessageStoreController.PARTICIPANT_ADDRESS_KEY);

        // see if the address already exists
        // TODO(sahinc) - refine this to match phone number formats in canonical addresses, or find
        // TODO(sahinc) - another solution.
        Cursor existingCanonicalAddressAsCursor = db.query(CANONICAL_ADDRESSES_TABLE,
                new String[]{ID_COLUMN}, ADDRESS_COLUMN + "=" + address,
                null, null, null, null);
        long canonicalAddressId = NO_EXISTING_ADDRESS;

        if (existingCanonicalAddressAsCursor != null
                && existingCanonicalAddressAsCursor.moveToFirst()) {
            canonicalAddressId = existingCanonicalAddressAsCursor.getLong(0);
            existingCanonicalAddressAsCursor.close();
        }

        // If there is no existing canonical address, add one.
        if (canonicalAddressId == NO_EXISTING_ADDRESS) {
            canonicalAddressId = db.insert(CANONICAL_ADDRESSES_TABLE, ADDRESS_COLUMN, values);
        }

        if (canonicalAddressId == NO_EXISTING_ADDRESS) {
            Log.e(TAG, "Could not create an entry in canonical addresses");
        }

        return (int) canonicalAddressId;
    }
}
