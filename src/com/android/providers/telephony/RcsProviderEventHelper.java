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

import static android.provider.Telephony.RcsColumns.RcsEventTypes.PARTICIPANT_ALIAS_CHANGED_EVENT_TYPE;
import static android.provider.Telephony.RcsColumns.RcsParticipantColumns.RCS_PARTICIPANT_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsParticipantEventColumns.NEW_ALIAS_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsEventTypes.ICON_CHANGED_EVENT_TYPE;
import static android.provider.Telephony.RcsColumns.RcsEventTypes.NAME_CHANGED_EVENT_TYPE;
import static android.provider.Telephony.RcsColumns.RcsEventTypes.PARTICIPANT_JOINED_EVENT_TYPE;
import static android.provider.Telephony.RcsColumns.RcsEventTypes.PARTICIPANT_LEFT_EVENT_TYPE;
import static android.provider.Telephony.RcsColumns.RcsThreadColumns.RCS_THREAD_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsThreadEventColumns.DESTINATION_PARTICIPANT_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsThreadEventColumns.EVENT_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsThreadEventColumns.EVENT_TYPE_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsThreadEventColumns.NEW_ICON_URI_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsThreadEventColumns.NEW_NAME_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsThreadEventColumns.SOURCE_PARTICIPANT_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsThreadEventColumns.TIMESTAMP_COLUMN;
import static android.provider.Telephony.RcsColumns.TRANSACTION_FAILED;
import static android.telephony.ims.RcsEventQueryParams.ALL_EVENTS;
import static android.telephony.ims.RcsEventQueryParams.ALL_GROUP_THREAD_EVENTS;
import static android.telephony.ims.RcsEventQueryParams.EVENT_QUERY_PARAMETERS_KEY;

import static android.telephony.ims.RcsQueryContinuationToken.EVENT_QUERY_CONTINUATION_TOKEN_TYPE;
import static android.telephony.ims.RcsQueryContinuationToken.QUERY_CONTINUATION_TOKEN;
import static com.android.providers.telephony.RcsProvider.RCS_PARTICIPANT_EVENT_TABLE;
import static com.android.providers.telephony.RcsProvider.RCS_PARTICIPANT_TABLE;
import static com.android.providers.telephony.RcsProvider.RCS_THREAD_EVENT_TABLE;
import static com.android.providers.telephony.RcsProvider.RCS_THREAD_TABLE;
import static com.android.providers.telephony.RcsProvider.RCS_UNIFIED_EVENT_VIEW;
import static com.android.providers.telephony.RcsProvider.TAG;
import static com.android.providers.telephony.RcsProviderThreadHelper.getThreadIdFromUri;
import static com.android.providers.telephony.RcsProviderUtil.INSERTION_FAILED;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.Bundle;
import android.telephony.ims.RcsEventQueryParams;
import android.telephony.ims.RcsQueryContinuationToken;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Constants and helpers related to events for {@link RcsProvider} to keep the code clean.
 *
 * @hide
 */
class RcsProviderEventHelper {
    private static final int PARTICIPANT_INDEX_IN_EVENT_URI = 1;
    private static final int EVENT_INDEX_IN_EVENT_URI = 3;

    @VisibleForTesting
    public static void createRcsEventTables(SQLiteDatabase db) {
        Log.d(TAG, "Creating event tables");

        // Add the event tables
        db.execSQL("CREATE TABLE " + RCS_THREAD_EVENT_TABLE + "(" + EVENT_ID_COLUMN
                + " INTEGER PRIMARY KEY AUTOINCREMENT, " + RCS_THREAD_ID_COLUMN + " INTEGER, "
                + SOURCE_PARTICIPANT_ID_COLUMN + " INTEGER, " + EVENT_TYPE_COLUMN + " INTEGER, "
                + TIMESTAMP_COLUMN + " INTEGER, " + DESTINATION_PARTICIPANT_ID_COLUMN + " INTEGER, "
                + NEW_ICON_URI_COLUMN + " TEXT, " + NEW_NAME_COLUMN + " TEXT, " + " FOREIGN KEY ("
                + RCS_THREAD_ID_COLUMN + ") REFERENCES " + RCS_THREAD_TABLE + " ("
                + RCS_THREAD_ID_COLUMN + "), FOREIGN KEY (" + SOURCE_PARTICIPANT_ID_COLUMN
                + ") REFERENCES " + RCS_PARTICIPANT_TABLE + " (" + RCS_PARTICIPANT_ID_COLUMN
                + "))");

        db.execSQL("CREATE TABLE " + RCS_PARTICIPANT_EVENT_TABLE + "(" + EVENT_ID_COLUMN
                + " INTEGER PRIMARY KEY AUTOINCREMENT, " + SOURCE_PARTICIPANT_ID_COLUMN +
                " INTEGER, " + TIMESTAMP_COLUMN + " INTEGER, "
                + NEW_ALIAS_COLUMN + " TEXT," + " FOREIGN KEY (" + SOURCE_PARTICIPANT_ID_COLUMN
                + ") REFERENCES " + RCS_PARTICIPANT_TABLE + " (" + RCS_PARTICIPANT_ID_COLUMN
                + "))");

        // Add the views

        // The following is a unified event view that puts every entry in both tables into one query
        db.execSQL("CREATE VIEW " + RCS_UNIFIED_EVENT_VIEW + " AS "
                + "SELECT " + PARTICIPANT_ALIAS_CHANGED_EVENT_TYPE + " AS " + EVENT_TYPE_COLUMN
                + ", " + EVENT_ID_COLUMN + ", " + SOURCE_PARTICIPANT_ID_COLUMN + ", "
                + TIMESTAMP_COLUMN + ", " + NEW_ALIAS_COLUMN + ", NULL as " + RCS_THREAD_ID_COLUMN
                + ", NULL as " + DESTINATION_PARTICIPANT_ID_COLUMN + ", NULL as "
                + NEW_ICON_URI_COLUMN + ", NULL as " + NEW_NAME_COLUMN + " "
                + "FROM " + RCS_PARTICIPANT_EVENT_TABLE + " "
                + "UNION "
                + "SELECT " + EVENT_TYPE_COLUMN + ", " + EVENT_ID_COLUMN + ", "
                + SOURCE_PARTICIPANT_ID_COLUMN + ", " + TIMESTAMP_COLUMN + ", "
                + "NULL as " + NEW_ALIAS_COLUMN + ", " + RCS_THREAD_ID_COLUMN + ", "
                + DESTINATION_PARTICIPANT_ID_COLUMN + ", " + NEW_ICON_URI_COLUMN + ", "
                + NEW_NAME_COLUMN + " "
                + "FROM " + RCS_THREAD_EVENT_TABLE);
    }

    private final SQLiteOpenHelper mSqLiteOpenHelper;

    Cursor queryEvents(Bundle bundle) {
        RcsEventQueryParams queryParameters = null;
        RcsQueryContinuationToken continuationToken = null;

        if (bundle != null) {
            queryParameters = bundle.getParcelable(EVENT_QUERY_PARAMETERS_KEY);
            continuationToken = bundle.getParcelable(QUERY_CONTINUATION_TOKEN);
        }

        if (continuationToken != null) {
            return RcsProviderUtil.performContinuationQuery(mSqLiteOpenHelper.getReadableDatabase(),
                    continuationToken);
        }

        // if no query parameters were entered, build an empty query parameters object
        if (queryParameters == null) {
            queryParameters = new RcsEventQueryParams.Builder().build();
        }

        return performInitialQuery(queryParameters);
    }

    private Cursor performInitialQuery(RcsEventQueryParams queryParameters) {
        SQLiteDatabase db = mSqLiteOpenHelper.getReadableDatabase();
        StringBuilder rawQuery = new StringBuilder("SELECT * FROM ").append(RCS_UNIFIED_EVENT_VIEW);

        int eventType = queryParameters.getEventType();
        if (eventType != ALL_EVENTS) {
            rawQuery.append(" WHERE ").append(EVENT_TYPE_COLUMN);
            if (eventType == ALL_GROUP_THREAD_EVENTS) {
                rawQuery.append(" IN (").append(
                        PARTICIPANT_JOINED_EVENT_TYPE).append(", ").append(
                        PARTICIPANT_LEFT_EVENT_TYPE).append(", ").append(
                        ICON_CHANGED_EVENT_TYPE).append(", ").append(
                        NAME_CHANGED_EVENT_TYPE).append(
                        ")");
            } else {
                rawQuery.append("=").append(eventType);
            }
        }

        rawQuery.append(" ORDER BY ");

        int sortingProperty = queryParameters.getSortingProperty();
        if (sortingProperty == RcsEventQueryParams.SORT_BY_TIMESTAMP) {
            rawQuery.append(TIMESTAMP_COLUMN);
        } else {
            rawQuery.append(EVENT_ID_COLUMN);
        }

        rawQuery.append(queryParameters.getSortDirection() ? " ASC " : " DESC ");

        RcsProviderUtil.appendLimit(rawQuery, queryParameters.getLimit());
        String rawQueryAsString = rawQuery.toString();
        Cursor cursor = db.rawQuery(rawQueryAsString, null);

        // if the query was paginated, build the next query
        int limit = queryParameters.getLimit();
        if (limit > 0) {
            RcsProviderUtil.createContinuationTokenBundle(cursor,
                    new RcsQueryContinuationToken(EVENT_QUERY_CONTINUATION_TOKEN_TYPE,
                        rawQueryAsString, limit, limit), QUERY_CONTINUATION_TOKEN);
        }

        return cursor;
    }

    RcsProviderEventHelper(SQLiteOpenHelper sqLiteOpenHelper) {
        mSqLiteOpenHelper = sqLiteOpenHelper;
    }

    long insertParticipantEvent(Uri uri, ContentValues values) {
        String participantId = getParticipantIdFromUri(uri);

        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();
        values.put(SOURCE_PARTICIPANT_ID_COLUMN, participantId);
        long rowId = db.insert(RCS_PARTICIPANT_EVENT_TABLE, SOURCE_PARTICIPANT_ID_COLUMN, values);
        values.remove(SOURCE_PARTICIPANT_ID_COLUMN);

        if (rowId == INSERTION_FAILED) {
            return TRANSACTION_FAILED;
        }

        return rowId;
    }

    long insertParticipantJoinedEvent(Uri uri, ContentValues values) {
        return insertGroupThreadEvent(uri, values, PARTICIPANT_JOINED_EVENT_TYPE);
    }

    long insertParticipantLeftEvent(Uri uri, ContentValues values) {
        return insertGroupThreadEvent(uri, values, PARTICIPANT_LEFT_EVENT_TYPE);
    }

    long insertThreadNameChangeEvent(Uri uri, ContentValues values) {
        return insertGroupThreadEvent(uri, values, NAME_CHANGED_EVENT_TYPE);
    }

    long insertThreadIconChangeEvent(Uri uri, ContentValues values) {
        return insertGroupThreadEvent(uri, values, ICON_CHANGED_EVENT_TYPE);
    }

    private long insertGroupThreadEvent(Uri uri, ContentValues valuesParameter,
            int eventType) {
        String threadId = getThreadIdFromUri(uri);

        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();
        ContentValues values = new ContentValues(valuesParameter);
        values.put(EVENT_TYPE_COLUMN, eventType);
        values.put(RCS_THREAD_ID_COLUMN, threadId);
        long rowId = db.insert(RCS_THREAD_EVENT_TABLE, EVENT_ID_COLUMN, values);

        if (rowId == INSERTION_FAILED) {
            return TRANSACTION_FAILED;
        }

        return rowId;
    }

    int deleteParticipantEvent(Uri uri) {
        String eventId = getEventIdFromEventUri(uri);
        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();

        return db.delete(RCS_PARTICIPANT_EVENT_TABLE, EVENT_ID_COLUMN + "=?",
                new String[]{eventId});
    }

    int deleteGroupThreadEvent(Uri uri) {
        String eventId = getEventIdFromEventUri(uri);
        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();

        return db.delete(RCS_THREAD_EVENT_TABLE, EVENT_ID_COLUMN + "=?", new String[]{eventId});
    }

    private String getEventIdFromEventUri(Uri uri) {
        return uri.getPathSegments().get(EVENT_INDEX_IN_EVENT_URI);
    }

    private String getParticipantIdFromUri(Uri uri) {
        return uri.getPathSegments().get(PARTICIPANT_INDEX_IN_EVENT_URI);
    }
}
