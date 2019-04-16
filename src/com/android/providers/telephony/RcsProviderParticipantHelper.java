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

import static android.provider.Telephony.CanonicalAddressesColumns.ADDRESS;
import static android.provider.Telephony.RcsColumns.RcsParticipantColumns.CANONICAL_ADDRESS_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsParticipantColumns.RCS_ALIAS_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsParticipantColumns.RCS_PARTICIPANT_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsParticipantColumns.RCS_PARTICIPANT_URI_PART;
import static android.provider.Telephony.RcsColumns.RcsParticipantHelpers.RCS_PARTICIPANT_WITH_ADDRESS_VIEW;
import static android.provider.Telephony.RcsColumns.RcsParticipantHelpers.RCS_PARTICIPANT_WITH_THREAD_VIEW;
import static android.provider.Telephony.RcsColumns.RcsThreadColumns.RCS_THREAD_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.TRANSACTION_FAILED;
import static android.telephony.ims.RcsParticipantQueryParams.PARTICIPANT_QUERY_PARAMETERS_KEY;

import static android.telephony.ims.RcsQueryContinuationToken.PARTICIPANT_QUERY_CONTINUATION_TOKEN_TYPE;
import static android.telephony.ims.RcsQueryContinuationToken.QUERY_CONTINUATION_TOKEN;
import static com.android.providers.telephony.RcsProvider.GROUP_THREAD_URI_PREFIX;
import static com.android.providers.telephony.RcsProvider.RCS_PARTICIPANT_TABLE;
import static com.android.providers.telephony.RcsProvider.RCS_PARTICIPANT_THREAD_JUNCTION_TABLE;
import static com.android.providers.telephony.RcsProvider.RCS_THREAD_TABLE;
import static com.android.providers.telephony.RcsProvider.TAG;
import static com.android.providers.telephony.RcsProviderThreadHelper.getThreadIdFromUri;
import static com.android.providers.telephony.RcsProviderUtil.INSERTION_FAILED;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.Bundle;
import android.telephony.ims.RcsParticipantQueryParams;
import android.telephony.ims.RcsQueryContinuationToken;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Constants and helpers related to participants for {@link RcsProvider} to keep the code clean.
 *
 * @hide
 */
class RcsProviderParticipantHelper {
    private static final int PARTICIPANT_ID_INDEX_IN_URI = 1;
    private static final int PARTICIPANT_ID_INDEX_IN_THREAD_URI = 3;

    @VisibleForTesting
    public static void createParticipantTables(SQLiteDatabase db) {
        Log.d(TAG, "Creating participant tables");

        // create participant tables
        db.execSQL("CREATE TABLE " + RCS_PARTICIPANT_TABLE + " (" +
                RCS_PARTICIPANT_ID_COLUMN + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                CANONICAL_ADDRESS_ID_COLUMN + " INTEGER ," +
                RCS_ALIAS_COLUMN + " TEXT, " +
                "FOREIGN KEY(" + CANONICAL_ADDRESS_ID_COLUMN + ") "
                + "REFERENCES canonical_addresses(_id)" +
                ");");

        db.execSQL("CREATE TABLE " + RCS_PARTICIPANT_THREAD_JUNCTION_TABLE + " (" +
                RCS_THREAD_ID_COLUMN + " INTEGER, " +
                RCS_PARTICIPANT_ID_COLUMN + " INTEGER, " +
                "CONSTRAINT thread_participant PRIMARY KEY("
                + RCS_THREAD_ID_COLUMN + ", " + RCS_PARTICIPANT_ID_COLUMN + "), " +
                "FOREIGN KEY(" + RCS_THREAD_ID_COLUMN
                + ") REFERENCES " + RCS_THREAD_TABLE + "(" + RCS_THREAD_ID_COLUMN + "), " +
                "FOREIGN KEY(" + RCS_PARTICIPANT_ID_COLUMN
                + ") REFERENCES " + RCS_PARTICIPANT_TABLE + "(" + RCS_PARTICIPANT_ID_COLUMN + "))");

        // create views

        // The following view joins rcs_participant table with canonical_addresses table to add the
        // actual address of a participant in the result.
        db.execSQL("CREATE VIEW " + RCS_PARTICIPANT_WITH_ADDRESS_VIEW + " AS SELECT "
                + "rcs_participant.rcs_participant_id, rcs_participant.canonical_address_id, "
                + "rcs_participant.rcs_alias, canonical_addresses.address FROM rcs_participant "
                + "LEFT JOIN canonical_addresses ON "
                + "rcs_participant.canonical_address_id=canonical_addresses._id");

        // The following view is the rcs_participant_with_address_view above, plus the information
        // on which threads this participant contributes to, to enable getting participants of a
        // thread
        db.execSQL("CREATE VIEW " + RCS_PARTICIPANT_WITH_THREAD_VIEW + " AS SELECT "
                + "rcs_participant.rcs_participant_id, rcs_participant.canonical_address_id, "
                + "rcs_participant.rcs_alias, canonical_addresses.address, rcs_thread_participant"
                + ".rcs_thread_id FROM rcs_participant LEFT JOIN canonical_addresses ON "
                + "rcs_participant.canonical_address_id=canonical_addresses._id LEFT JOIN "
                + "rcs_thread_participant ON rcs_participant.rcs_participant_id="
                + "rcs_thread_participant.rcs_participant_id");

        // TODO - create indexes for faster querying
    }

    private final SQLiteOpenHelper mSqLiteOpenHelper;

    RcsProviderParticipantHelper(SQLiteOpenHelper sqLiteOpenHelper) {
        mSqLiteOpenHelper = sqLiteOpenHelper;
    }

    Cursor queryParticipant(Bundle bundle) {
        RcsParticipantQueryParams queryParameters = null;
        RcsQueryContinuationToken continuationToken = null;

        if (bundle != null) {
            queryParameters = bundle.getParcelable(PARTICIPANT_QUERY_PARAMETERS_KEY);
            continuationToken = bundle.getParcelable(QUERY_CONTINUATION_TOKEN);
        }

        if (continuationToken != null) {
            return RcsProviderUtil.performContinuationQuery(mSqLiteOpenHelper.getReadableDatabase(),
                    continuationToken);
        }

        if (queryParameters == null) {
            queryParameters = new RcsParticipantQueryParams.Builder().build();
        }

        return performInitialQuery(queryParameters);
    }

    private Cursor performInitialQuery(RcsParticipantQueryParams queryParameters) {
        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();

        StringBuilder rawQuery = buildInitialRawQuery(queryParameters);
        RcsProviderUtil.appendLimit(rawQuery, queryParameters.getLimit());
        String rawQueryAsString = rawQuery.toString();
        Cursor cursor = db.rawQuery(rawQueryAsString, null);

        // If the query was paginated, build the next query
        int limit = queryParameters.getLimit();
        if (limit > 0) {
            RcsProviderUtil.createContinuationTokenBundle(cursor,
                    new RcsQueryContinuationToken(PARTICIPANT_QUERY_CONTINUATION_TOKEN_TYPE,
                            rawQueryAsString, limit, limit), QUERY_CONTINUATION_TOKEN);
        }

        return cursor;
    }

    private StringBuilder buildInitialRawQuery(RcsParticipantQueryParams queryParameters) {
        StringBuilder rawQuery = new StringBuilder("SELECT * FROM ");

        boolean isThreadFiltered = queryParameters.getThreadId() > 0;

        if (isThreadFiltered) {
            rawQuery.append(RCS_PARTICIPANT_WITH_THREAD_VIEW);
        } else {
            rawQuery.append(RCS_PARTICIPANT_WITH_ADDRESS_VIEW);
        }

        boolean isAliasFiltered = !TextUtils.isEmpty(queryParameters.getAliasLike());
        boolean isCanonicalAddressFiltered = !TextUtils.isEmpty(
                queryParameters.getCanonicalAddressLike());

        if (isAliasFiltered || isCanonicalAddressFiltered || isThreadFiltered) {
            rawQuery.append(" WHERE ");
        }

        if (isAliasFiltered) {
            rawQuery.append(RCS_ALIAS_COLUMN).append(" LIKE \"").append(
                    queryParameters.getAliasLike()).append("\"");
        }

        if (isCanonicalAddressFiltered) {
            if (isAliasFiltered) {
                rawQuery.append(" AND ");
            }
            rawQuery.append(ADDRESS).append(" LIKE \"").append(
                    queryParameters.getCanonicalAddressLike()).append("\"");
        }

        if (isThreadFiltered) {
            if (isAliasFiltered || isCanonicalAddressFiltered) {
                rawQuery.append(" AND ");
            }
            rawQuery.append(RCS_THREAD_ID_COLUMN).append("=").append(queryParameters.getThreadId());
        }

        rawQuery.append(" ORDER BY ");

        int sortingProperty = queryParameters.getSortingProperty();
        if (sortingProperty == RcsParticipantQueryParams.SORT_BY_ALIAS) {
            rawQuery.append(RCS_ALIAS_COLUMN);
        } else if (sortingProperty == RcsParticipantQueryParams.SORT_BY_CANONICAL_ADDRESS) {
            rawQuery.append(ADDRESS);
        } else {
            rawQuery.append(RCS_PARTICIPANT_ID_COLUMN);
        }
        rawQuery.append(queryParameters.getSortDirection() ? " ASC " : " DESC ");

        return rawQuery;
    }

    Cursor queryParticipantWithId(Uri uri, String[] projection) {
        SQLiteDatabase db = mSqLiteOpenHelper.getReadableDatabase();
        return db.query(RCS_PARTICIPANT_WITH_ADDRESS_VIEW, projection,
                getParticipantIdSelection(uri), null, null, null, null);
    }

    Cursor queryParticipantIn1To1Thread(Uri uri) {
        String threadId = getThreadIdFromUri(uri);
        SQLiteDatabase db = mSqLiteOpenHelper.getReadableDatabase();

        return db.rawQuery(
                "  SELECT * "
                        + "FROM rcs_participant "
                        + "WHERE rcs_participant.rcs_participant_id = ("
                        + "  SELECT rcs_thread_participant.rcs_participant_id "
                        + "  FROM rcs_thread_participant "
                        + "  WHERE rcs_thread_participant.rcs_thread_id=" + threadId + ")", null);
    }

    Cursor queryParticipantsInGroupThread(Uri uri) {
        String threadId = getThreadIdFromUri(uri);
        SQLiteDatabase db = mSqLiteOpenHelper.getReadableDatabase();

        return db.rawQuery("  SELECT * "
                + "FROM rcs_participant "
                + "WHERE rcs_participant.rcs_participant_id = ("
                + "  SELECT rcs_participant_id "
                + "  FROM rcs_thread_participant "
                + "  WHERE rcs_thread_id= " + threadId + ")", null);
    }

    Cursor queryParticipantInGroupThreadWithId(Uri uri) {
        String threadId = getThreadIdFromUri(uri);
        String participantId = getParticipantIdFromUri(uri);
        SQLiteDatabase db = mSqLiteOpenHelper.getReadableDatabase();

        return db.rawQuery("  SELECT * "
                        + "FROM rcs_participant "
                        + "WHERE rcs_participant.rcs_participant_id = ("
                        + "  SELECT rcs_participant_id "
                        + "  FROM rcs_thread_participant "
                        + "  WHERE rcs_thread_id=? AND rcs_participant_id=?)",
                new String[]{threadId, participantId});
    }

    long insertParticipant(ContentValues contentValues) {
        if (!contentValues.containsKey(CANONICAL_ADDRESS_ID_COLUMN) || TextUtils.isEmpty(
                contentValues.getAsString(CANONICAL_ADDRESS_ID_COLUMN))) {
            Log.e(TAG,
                    "RcsProviderParticipantHelper: Inserting participants without canonical "
                            + "address is not supported");
            return TRANSACTION_FAILED;
        }
        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();
        long rowId = db.insert(RCS_PARTICIPANT_TABLE, RCS_PARTICIPANT_ID_COLUMN, contentValues);
        Log.e(TAG, "Inserted participant with rowId=" + rowId);
        if (rowId < 0) {
            return TRANSACTION_FAILED;
        }
        return rowId;
    }

    /**
     * Inserts a participant into group thread. This function returns the participant ID instead of
     * the row id in the junction table
     */
    long insertParticipantIntoGroupThread(ContentValues values) {
        if (!values.containsKey(RCS_THREAD_ID_COLUMN) || !values.containsKey(
                RCS_PARTICIPANT_ID_COLUMN)) {
            Log.e(TAG, "RcsProviderParticipantHelper: Cannot insert participant into group.");
            return TRANSACTION_FAILED;
        }
        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();
        long insertedRowId = db.insert(RCS_PARTICIPANT_THREAD_JUNCTION_TABLE,
                RCS_PARTICIPANT_ID_COLUMN,
                values);

        if (insertedRowId == INSERTION_FAILED) {
            return TRANSACTION_FAILED;
        }

        return values.getAsLong(RCS_PARTICIPANT_ID_COLUMN);
    }

    /**
     * Inserts a participant into group thread. This function returns the participant ID instead of
     * the row id in the junction table
     */
    long insertParticipantIntoGroupThreadWithId(Uri uri) {
        String threadId = getThreadIdFromUri(uri);
        String participantId = getParticipantIdFromUri(uri);
        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();

        ContentValues contentValues = new ContentValues(2);
        contentValues.put(RCS_THREAD_ID_COLUMN, threadId);
        contentValues.put(RCS_PARTICIPANT_ID_COLUMN, participantId);

        long insertedRowId = db.insert(
                RCS_PARTICIPANT_THREAD_JUNCTION_TABLE, RCS_PARTICIPANT_ID_COLUMN, contentValues);

        if (insertedRowId == INSERTION_FAILED) {
            return TRANSACTION_FAILED;
        }

        return Long.parseLong(participantId);
    }

    int deleteParticipantWithId(Uri uri) {
        String participantId = uri.getPathSegments().get(PARTICIPANT_ID_INDEX_IN_URI);
        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();

        // See if this participant is involved in any threads
        Cursor cursor = db.query(RCS_PARTICIPANT_THREAD_JUNCTION_TABLE, null,
                RCS_PARTICIPANT_ID_COLUMN + "=?", new String[]{participantId}, null, null, null);

        int participatingThreadCount = 0;
        if (cursor != null) {
            participatingThreadCount = cursor.getCount();
            cursor.close();
        }

        if (participatingThreadCount > 0) {
            Log.e(TAG,
                    "RcsProviderParticipantHelper: Can't delete participant while it is still in "
                            + "RCS threads, uri:"
                            + uri);
            return 0;
        }

        return db.delete(RCS_PARTICIPANT_TABLE, RCS_PARTICIPANT_ID_COLUMN + "=?",
                new String[]{participantId});
    }

    int deleteParticipantFromGroupThread(Uri uri) {
        String threadId = getThreadIdFromUri(uri);
        String participantId = getParticipantIdFromUri(uri);
        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();
        // TODO check to remove owner
        return db.delete(RCS_PARTICIPANT_THREAD_JUNCTION_TABLE,
                RCS_THREAD_ID_COLUMN + "=? AND " + RCS_PARTICIPANT_ID_COLUMN + "=?",
                new String[]{threadId, participantId});
    }

    int updateParticipant(ContentValues contentValues, String selection, String[] selectionArgs) {
        if (contentValues.containsKey(RCS_PARTICIPANT_ID_COLUMN)) {
            Log.e(TAG, "RcsProviderParticipantHelper: Updating participant id is not supported");
            return 0;
        }

        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();
        return db.update(RCS_PARTICIPANT_TABLE, contentValues, selection, selectionArgs);
    }

    int updateParticipantWithId(ContentValues contentValues, Uri uri) {
        return updateParticipant(contentValues, getParticipantIdSelection(uri), null);
    }

    private String getParticipantIdSelection(Uri uri) {
        return RCS_PARTICIPANT_ID_COLUMN + "=" + uri.getPathSegments().get(
                PARTICIPANT_ID_INDEX_IN_URI);
    }

    Uri getParticipantInThreadUri(ContentValues values, long rowId) {
        if (values == null) {
            return null;
        }
        Integer threadId = values.getAsInteger(RCS_THREAD_ID_COLUMN);
        if (threadId == null) {
            return null;
        }

        return GROUP_THREAD_URI_PREFIX.buildUpon().appendPath(
                Integer.toString(threadId)).appendPath(RCS_PARTICIPANT_URI_PART).appendPath(
                Long.toString(rowId)).build();
    }

    private String getParticipantIdFromUri(Uri uri) {
        return uri.getPathSegments().get(PARTICIPANT_ID_INDEX_IN_THREAD_URI);
    }
}
