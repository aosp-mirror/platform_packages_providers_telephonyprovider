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

import static android.provider.Telephony.RcsColumns.CONTENT_AND_AUTHORITY;
import static android.provider.Telephony.RcsColumns.Rcs1To1ThreadColumns.RCS_1_TO_1_THREAD_URI_PART;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.CONTENT_TYPE_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.CONTENT_URI_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.FILE_SIZE_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.FILE_TRANSFER_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.HEIGHT_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.DURATION_MILLIS_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.PREVIEW_TYPE_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.PREVIEW_URI_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.SESSION_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.SUCCESSFULLY_TRANSFERRED_BYTES;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.TRANSFER_STATUS_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.WIDTH_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsGroupThreadColumns.RCS_GROUP_THREAD_URI_PART;
import static android.provider.Telephony.RcsColumns.RcsIncomingMessageColumns.ARRIVAL_TIMESTAMP_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsIncomingMessageColumns.INCOMING_MESSAGE_URI_PART;
import static android.provider.Telephony.RcsColumns.RcsIncomingMessageColumns.SENDER_PARTICIPANT_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsMessageColumns.GLOBAL_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsMessageColumns.LATITUDE_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsMessageColumns.LONGITUDE_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsMessageColumns.MESSAGE_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsMessageColumns.MESSAGE_TEXT_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsMessageColumns.ORIGINATION_TIMESTAMP_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsMessageColumns.STATUS_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsMessageColumns.SUB_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsMessageDeliveryColumns.DELIVERED_TIMESTAMP_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsOutgoingMessageColumns.OUTGOING_MESSAGE_URI_PART;
import static android.provider.Telephony.RcsColumns.RcsParticipantColumns.RCS_PARTICIPANT_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsThreadColumns.RCS_THREAD_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsUnifiedMessageColumns.MESSAGE_TYPE_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsUnifiedMessageColumns.MESSAGE_TYPE_INCOMING;
import static android.provider.Telephony.RcsColumns.RcsUnifiedMessageColumns.MESSAGE_TYPE_OUTGOING;
import static android.provider.Telephony.RcsColumns.RcsUnifiedMessageColumns.UNIFIED_INCOMING_MESSAGE_VIEW;
import static android.provider.Telephony.RcsColumns.RcsUnifiedMessageColumns.UNIFIED_OUTGOING_MESSAGE_VIEW;
import static android.provider.Telephony.RcsColumns.TRANSACTION_FAILED;
import static android.telephony.ims.RcsMessageQueryParams.MESSAGE_QUERY_PARAMETERS_KEY;
import static android.telephony.ims.RcsMessageQueryParams.THREAD_ID_NOT_SET;

import static android.telephony.ims.RcsQueryContinuationToken.MESSAGE_QUERY_CONTINUATION_TOKEN_TYPE;
import static android.telephony.ims.RcsQueryContinuationToken.QUERY_CONTINUATION_TOKEN;
import static com.android.providers.telephony.RcsProvider.RCS_FILE_TRANSFER_TABLE;
import static com.android.providers.telephony.RcsProvider.RCS_INCOMING_MESSAGE_TABLE;
import static com.android.providers.telephony.RcsProvider.RCS_MESSAGE_DELIVERY_TABLE;
import static com.android.providers.telephony.RcsProvider.RCS_MESSAGE_TABLE;
import static com.android.providers.telephony.RcsProvider.RCS_OUTGOING_MESSAGE_TABLE;
import static com.android.providers.telephony.RcsProvider.RCS_PARTICIPANT_TABLE;
import static com.android.providers.telephony.RcsProvider.RCS_PARTICIPANT_THREAD_JUNCTION_TABLE;
import static com.android.providers.telephony.RcsProvider.RCS_THREAD_TABLE;
import static com.android.providers.telephony.RcsProvider.TAG;
import static com.android.providers.telephony.RcsProvider.UNIFIED_MESSAGE_VIEW;
import static com.android.providers.telephony.RcsProviderThreadHelper.getThreadIdFromUri;
import static com.android.providers.telephony.RcsProviderUtil.INSERTION_FAILED;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Telephony.RcsColumns.RcsIncomingMessageColumns;
import android.provider.Telephony.RcsColumns.RcsMessageDeliveryColumns;
import android.telephony.ims.RcsMessageQueryParams;
import android.telephony.ims.RcsQueryContinuationToken;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Constants and helpers related to messages for {@link RcsProvider} to keep the code clean.
 *
 * @hide
 */
public class RcsProviderMessageHelper {
    private static final int MESSAGE_ID_INDEX_IN_URI = 1;
    private static final int MESSAGE_ID_INDEX_IN_THREAD_URI = 3;

    private final SQLiteOpenHelper mSqLiteOpenHelper;

    @VisibleForTesting
    public static void createRcsMessageTables(SQLiteDatabase db) {
        Log.d(TAG, "Creating message tables");

        // Add the message tables
        db.execSQL("CREATE TABLE " + RCS_MESSAGE_TABLE + "(" + MESSAGE_ID_COLUMN
                + " INTEGER PRIMARY KEY AUTOINCREMENT, " + RCS_THREAD_ID_COLUMN + " INTEGER, "
                + GLOBAL_ID_COLUMN + " TEXT, " + SUB_ID_COLUMN + " INTEGER, " + MESSAGE_TEXT_COLUMN
                + " TEXT," + LATITUDE_COLUMN + " REAL, " + LONGITUDE_COLUMN + " REAL, "
                + STATUS_COLUMN + " INTEGER, " + ORIGINATION_TIMESTAMP_COLUMN
                + " INTEGER, FOREIGN KEY(" + RCS_THREAD_ID_COLUMN + ") REFERENCES "
                + RCS_THREAD_TABLE + "(" + RCS_THREAD_ID_COLUMN + "))");

        db.execSQL("CREATE TABLE " + RCS_INCOMING_MESSAGE_TABLE + "(" + MESSAGE_ID_COLUMN
                + " INTEGER PRIMARY KEY, " + SENDER_PARTICIPANT_ID_COLUMN + " INTEGER, "
                + ARRIVAL_TIMESTAMP_COLUMN + " INTEGER, "
                + RcsIncomingMessageColumns.SEEN_TIMESTAMP_COLUMN + " INTEGER, FOREIGN KEY ("
                + MESSAGE_ID_COLUMN + ") REFERENCES " + RCS_MESSAGE_TABLE + "(" + MESSAGE_ID_COLUMN
                + "))");

        db.execSQL("CREATE TABLE " + RCS_OUTGOING_MESSAGE_TABLE + "(" + MESSAGE_ID_COLUMN
                + " INTEGER PRIMARY KEY, FOREIGN KEY (" + MESSAGE_ID_COLUMN + ") REFERENCES "
                + RCS_MESSAGE_TABLE + "(" + MESSAGE_ID_COLUMN + "))");

        db.execSQL("CREATE TABLE " + RCS_MESSAGE_DELIVERY_TABLE + "(" + MESSAGE_ID_COLUMN
                + " INTEGER, " + RCS_PARTICIPANT_ID_COLUMN + " INTEGER, "
                + DELIVERED_TIMESTAMP_COLUMN + " INTEGER, "
                + RcsMessageDeliveryColumns.SEEN_TIMESTAMP_COLUMN + " INTEGER, "
                + "CONSTRAINT message_delivery PRIMARY KEY (" + MESSAGE_ID_COLUMN + ", "
                + RCS_PARTICIPANT_ID_COLUMN + "), FOREIGN KEY (" + MESSAGE_ID_COLUMN
                + ") REFERENCES " + RCS_MESSAGE_TABLE + "(" + MESSAGE_ID_COLUMN + "), FOREIGN KEY ("
                + RCS_PARTICIPANT_ID_COLUMN + ") REFERENCES " + RCS_PARTICIPANT_TABLE + "("
                + RCS_PARTICIPANT_ID_COLUMN + "))");

        db.execSQL("CREATE TABLE " + RCS_FILE_TRANSFER_TABLE + " (" + FILE_TRANSFER_ID_COLUMN
                + " INTEGER PRIMARY KEY AUTOINCREMENT, " + MESSAGE_ID_COLUMN + " INTEGER, "
                + SESSION_ID_COLUMN + " TEXT, " + CONTENT_URI_COLUMN + " TEXT, "
                + CONTENT_TYPE_COLUMN + " TEXT, " + FILE_SIZE_COLUMN + " INTEGER, "
                + SUCCESSFULLY_TRANSFERRED_BYTES + " INTEGER, " + TRANSFER_STATUS_COLUMN +
                " INTEGER, " + WIDTH_COLUMN + " INTEGER, " + HEIGHT_COLUMN + " INTEGER, "
                + DURATION_MILLIS_COLUMN + " INTEGER, " + PREVIEW_URI_COLUMN + " TEXT, "
                + PREVIEW_TYPE_COLUMN + " TEXT, FOREIGN KEY (" + MESSAGE_ID_COLUMN + ") REFERENCES "
                + RCS_MESSAGE_TABLE + "(" + MESSAGE_ID_COLUMN + "))");

        // Add the views
        //
        // The following view inner joins incoming messages with all messages, inner joins outgoing
        // messages with all messages, and unions them together, while also adding an is_incoming
        // column for easily telling where the record came from. This may have been achieved with
        // an outer join but SQLite doesn't support them.
        //
        // CREATE VIEW unified_message_view AS
        //
        // SELECT rcs_message.rcs_message_row_id,
        //        rcs_message.rcs_thread_id,
        //        rcs_message.rcs_message_global_id,
        //        rcs_message.sub_id,
        //        rcs_message.status,
        //        rcs_message.origination_timestamp,
        //        rcs_message.rcs_text,
        //        rcs_message.latitude,
        //        rcs_message.longitude,
        //        0 AS sender_participant,
        //        0 AS arrival_timestamp,
        //        0 AS seen_timestamp,
        //        outgoing AS message_type
        //
        // FROM rcs_message INNER JOIN rcs_outgoing_message
        //          ON rcs_message.rcs_message_row_id=rcs_outgoing_message.rcs_message_row_id
        //
        // UNION
        //
        // SELECT rcs_message.rcs_message_row_id,
        //        rcs_message.rcs_thread_id,
        //        rcs_message.rcs_message_global_id,
        //        rcs_message.sub_id,
        //        rcs_message.status,
        //        rcs_message.origination_timestamp,
        //        rcs_message.rcs_text,
        //        rcs_message.latitude,
        //        rcs_message.longitude,
        //        rcs_incoming_message.sender_participant,
        //        rcs_incoming_message.arrival_timestamp,
        //        rcs_incoming_message.seen_timestamp,
        //        incoming AS message_type
        //
        // FROM rcs_message INNER JOIN rcs_incoming_message
        //          ON rcs_message.rcs_message_row_id=rcs_incoming_message.rcs_message_row_id
        //
        db.execSQL("CREATE VIEW " + UNIFIED_MESSAGE_VIEW + " AS SELECT "
                + RCS_MESSAGE_TABLE + "." + MESSAGE_ID_COLUMN + ", "
                + RCS_MESSAGE_TABLE + "." + RCS_THREAD_ID_COLUMN + ", "
                + RCS_MESSAGE_TABLE + "." + GLOBAL_ID_COLUMN + ", "
                + RCS_MESSAGE_TABLE + "." + SUB_ID_COLUMN + ", "
                + RCS_MESSAGE_TABLE + "." + STATUS_COLUMN + ", "
                + RCS_MESSAGE_TABLE + "." + ORIGINATION_TIMESTAMP_COLUMN + ", "
                + RCS_MESSAGE_TABLE + "." + MESSAGE_TEXT_COLUMN + ", "
                + RCS_MESSAGE_TABLE + "." + LATITUDE_COLUMN + ", "
                + RCS_MESSAGE_TABLE + "." + LONGITUDE_COLUMN + ", "
                + "0 AS " + SENDER_PARTICIPANT_ID_COLUMN + ", "
                + "0 AS " + ARRIVAL_TIMESTAMP_COLUMN + ", "
                + "0 AS " + RcsIncomingMessageColumns.SEEN_TIMESTAMP_COLUMN + ", "
                + MESSAGE_TYPE_OUTGOING + " AS " + MESSAGE_TYPE_COLUMN
                + " FROM " + RCS_MESSAGE_TABLE + " INNER JOIN " + RCS_OUTGOING_MESSAGE_TABLE
                + " ON " + RCS_MESSAGE_TABLE + "." + MESSAGE_ID_COLUMN + "="
                + RCS_OUTGOING_MESSAGE_TABLE + "." + MESSAGE_ID_COLUMN
                + " UNION SELECT "
                + RCS_MESSAGE_TABLE + "." + MESSAGE_ID_COLUMN + ", "
                + RCS_MESSAGE_TABLE + "." + RCS_THREAD_ID_COLUMN + ", "
                + RCS_MESSAGE_TABLE + "." + GLOBAL_ID_COLUMN + ", "
                + RCS_MESSAGE_TABLE + "." + SUB_ID_COLUMN + ", "
                + RCS_MESSAGE_TABLE + "." + STATUS_COLUMN + ", "
                + RCS_MESSAGE_TABLE + "." + ORIGINATION_TIMESTAMP_COLUMN + ", "
                + RCS_MESSAGE_TABLE + "." + MESSAGE_TEXT_COLUMN + ", "
                + RCS_MESSAGE_TABLE + "." + LATITUDE_COLUMN + ", "
                + RCS_MESSAGE_TABLE + "." + LONGITUDE_COLUMN + ", "
                + RCS_INCOMING_MESSAGE_TABLE + "." + SENDER_PARTICIPANT_ID_COLUMN + ", "
                + RCS_INCOMING_MESSAGE_TABLE + "." + ARRIVAL_TIMESTAMP_COLUMN + ", "
                + RCS_INCOMING_MESSAGE_TABLE + "." + RcsIncomingMessageColumns.SEEN_TIMESTAMP_COLUMN
                + ", "
                + MESSAGE_TYPE_INCOMING + " AS " + MESSAGE_TYPE_COLUMN
                + " FROM " + RCS_MESSAGE_TABLE + " INNER JOIN " + RCS_INCOMING_MESSAGE_TABLE
                + " ON " + RCS_MESSAGE_TABLE + "." + MESSAGE_ID_COLUMN + "="
                + RCS_INCOMING_MESSAGE_TABLE + "." + MESSAGE_ID_COLUMN);

        // The following view inner joins incoming messages with all messages
        //
        // CREATE VIEW unified_incoming_message_view AS
        //
        // SELECT rcs_message.rcs_message_row_id,
        //        rcs_message.rcs_thread_id,
        //        rcs_message.rcs_message_global_id,
        //        rcs_message.sub_id,
        //        rcs_message.status,
        //        rcs_message.origination_timestamp,
        //        rcs_message.rcs_text,
        //        rcs_message.latitude,
        //        rcs_message.longitude,
        //        rcs_incoming_message.sender_participant,
        //        rcs_incoming_message.arrival_timestamp,
        //        rcs_incoming_message.seen_timestamp,
        //
        // FROM rcs_message INNER JOIN rcs_incoming_message
        //          ON rcs_message.rcs_message_row_id=rcs_incoming_message.rcs_message_row_id

        db.execSQL("CREATE VIEW " + UNIFIED_INCOMING_MESSAGE_VIEW + " AS SELECT "
                + RCS_MESSAGE_TABLE + "." + MESSAGE_ID_COLUMN + ", "
                + RCS_MESSAGE_TABLE + "." + RCS_THREAD_ID_COLUMN + ", "
                + RCS_MESSAGE_TABLE + "." + GLOBAL_ID_COLUMN + ", "
                + RCS_MESSAGE_TABLE + "." + SUB_ID_COLUMN + ", "
                + RCS_MESSAGE_TABLE + "." + STATUS_COLUMN + ", "
                + RCS_MESSAGE_TABLE + "." + ORIGINATION_TIMESTAMP_COLUMN + ", "
                + RCS_MESSAGE_TABLE + "." + MESSAGE_TEXT_COLUMN + ", "
                + RCS_MESSAGE_TABLE + "." + LATITUDE_COLUMN + ", "
                + RCS_MESSAGE_TABLE + "." + LONGITUDE_COLUMN + ", "
                + RCS_INCOMING_MESSAGE_TABLE + "." + SENDER_PARTICIPANT_ID_COLUMN + ", "
                + RCS_INCOMING_MESSAGE_TABLE + "." + ARRIVAL_TIMESTAMP_COLUMN + ", "
                + RCS_INCOMING_MESSAGE_TABLE + "." + RcsIncomingMessageColumns.SEEN_TIMESTAMP_COLUMN
                + " FROM " + RCS_MESSAGE_TABLE + " INNER JOIN " + RCS_INCOMING_MESSAGE_TABLE
                + " ON " + RCS_MESSAGE_TABLE + "." + MESSAGE_ID_COLUMN + "="
                + RCS_INCOMING_MESSAGE_TABLE + "." + MESSAGE_ID_COLUMN);

        // The following view inner joins outgoing messages with all messages.
        //
        // CREATE VIEW unified_outgoing_message AS
        //
        // SELECT rcs_message.rcs_message_row_id,
        //        rcs_message.rcs_thread_id,
        //        rcs_message.rcs_message_global_id,
        //        rcs_message.sub_id,
        //        rcs_message.status,
        //        rcs_message.origination_timestamp
        //        rcs_message.rcs_text,
        //        rcs_message.latitude,
        //        rcs_message.longitude,
        //
        // FROM rcs_message INNER JOIN rcs_outgoing_message
        //          ON rcs_message.rcs_message_row_id=rcs_outgoing_message.rcs_message_row_id

        db.execSQL("CREATE VIEW " + UNIFIED_OUTGOING_MESSAGE_VIEW + " AS SELECT "
                + RCS_MESSAGE_TABLE + "." + MESSAGE_ID_COLUMN + ", "
                + RCS_MESSAGE_TABLE + "." + RCS_THREAD_ID_COLUMN + ", "
                + RCS_MESSAGE_TABLE + "." + GLOBAL_ID_COLUMN + ", "
                + RCS_MESSAGE_TABLE + "." + SUB_ID_COLUMN + ", "
                + RCS_MESSAGE_TABLE + "." + STATUS_COLUMN + ", "
                + RCS_MESSAGE_TABLE + "." + ORIGINATION_TIMESTAMP_COLUMN + ", "
                + RCS_MESSAGE_TABLE + "." + MESSAGE_TEXT_COLUMN + ", "
                + RCS_MESSAGE_TABLE + "." + LATITUDE_COLUMN + ", "
                + RCS_MESSAGE_TABLE + "." + LONGITUDE_COLUMN
                + " FROM " + RCS_MESSAGE_TABLE + " INNER JOIN " + RCS_OUTGOING_MESSAGE_TABLE
                + " ON " + RCS_MESSAGE_TABLE + "." + MESSAGE_ID_COLUMN + "="
                + RCS_OUTGOING_MESSAGE_TABLE + "." + MESSAGE_ID_COLUMN);

        // Add triggers

        // Delete the corresponding rcs_message row upon deleting a row in rcs_incoming_message
        //
        // CREATE TRIGGER delete_common_message_after_incoming
        //  AFTER DELETE ON rcs_incoming_message
        // BEGIN
        //  DELETE FROM rcs_message WHERE rcs_message.rcs_message_row_id=OLD.rcs_message_row_id;
        // END
        db.execSQL("CREATE TRIGGER deleteCommonMessageAfterIncoming AFTER DELETE ON "
                + RCS_INCOMING_MESSAGE_TABLE + " BEGIN DELETE FROM " + RCS_MESSAGE_TABLE
                + " WHERE " + RCS_MESSAGE_TABLE + "." + MESSAGE_ID_COLUMN + "=OLD."
                + MESSAGE_ID_COLUMN + "; END");

        // Delete the corresponding rcs_message row upon deleting a row in rcs_outgoing_message
        //
        // CREATE TRIGGER delete_common_message_after_outgoing
        //  AFTER DELETE ON rcs_outgoing_message
        // BEGIN
        //  DELETE FROM rcs_message WHERE rcs_message.rcs_message_row_id=OLD.rcs_message_row_id;
        // END
        db.execSQL("CREATE TRIGGER deleteCommonMessageAfterOutgoing AFTER DELETE ON "
                + RCS_OUTGOING_MESSAGE_TABLE + " BEGIN DELETE FROM " + RCS_MESSAGE_TABLE
                + " WHERE " + RCS_MESSAGE_TABLE + "." + MESSAGE_ID_COLUMN + "=OLD."
                + MESSAGE_ID_COLUMN + "; END");
    }

    RcsProviderMessageHelper(SQLiteOpenHelper sqLiteOpenHelper) {
        mSqLiteOpenHelper = sqLiteOpenHelper;
    }

    Cursor queryMessages(Bundle bundle) {
        RcsMessageQueryParams queryParameters = null;
        RcsQueryContinuationToken continuationToken = null;

        if (bundle != null) {
            queryParameters = bundle.getParcelable(MESSAGE_QUERY_PARAMETERS_KEY);
            continuationToken = bundle.getParcelable(QUERY_CONTINUATION_TOKEN);
        }

        if (continuationToken != null) {
            return RcsProviderUtil.performContinuationQuery(mSqLiteOpenHelper.getReadableDatabase(),
                    continuationToken);
        }

        // if no parameters were entered, build an empty query parameters object
        if (queryParameters == null) {
            queryParameters = new RcsMessageQueryParams.Builder().build();
        }

        return performInitialQuery(queryParameters);
    }

    private Cursor performInitialQuery(RcsMessageQueryParams queryParameters) {
        SQLiteDatabase db = mSqLiteOpenHelper.getReadableDatabase();

        StringBuilder rawQuery = new StringBuilder("SELECT * FROM ").append(UNIFIED_MESSAGE_VIEW);

        int messageType = queryParameters.getMessageType();
        String messageLike = queryParameters.getMessageLike();
        int threadId = queryParameters.getThreadId();

        boolean isMessageLikePresent = !TextUtils.isEmpty(messageLike);
        boolean isMessageTypeFiltered = (messageType == MESSAGE_TYPE_INCOMING)
                || (messageType == MESSAGE_TYPE_OUTGOING);
        boolean isThreadFiltered = threadId != THREAD_ID_NOT_SET;

        if (isMessageLikePresent || isMessageTypeFiltered || isThreadFiltered) {
            rawQuery.append(" WHERE ");
        }

        if (messageType == MESSAGE_TYPE_INCOMING) {
            rawQuery.append(MESSAGE_TYPE_COLUMN).append("=").append(MESSAGE_TYPE_INCOMING);
        } else if (messageType == MESSAGE_TYPE_OUTGOING) {
            rawQuery.append(MESSAGE_TYPE_COLUMN).append("=").append(MESSAGE_TYPE_OUTGOING);
        }

        if (isMessageLikePresent) {
            if (isMessageTypeFiltered) {
                rawQuery.append(" AND ");
            }
            rawQuery.append(MESSAGE_TEXT_COLUMN).append(" LIKE \"").append(messageLike)
                    .append("\"");
        }

        if (isThreadFiltered) {
            if (isMessageLikePresent || isMessageTypeFiltered) {
                rawQuery.append(" AND ");
            }
            rawQuery.append(RCS_THREAD_ID_COLUMN).append("=").append(threadId);
        }

        // TODO - figure out a way to see if this message has file transfer or not. Ideally we
        // should join the unified table with file transfer table, but using a trigger to change a
        // flag on rcs_message would also work

        rawQuery.append(" ORDER BY ");

        int sortingProperty = queryParameters.getSortingProperty();
        if (sortingProperty == RcsMessageQueryParams.SORT_BY_TIMESTAMP) {
            rawQuery.append(ORIGINATION_TIMESTAMP_COLUMN);
        } else {
            rawQuery.append(MESSAGE_ID_COLUMN);
        }

        rawQuery.append(queryParameters.getSortDirection() ? " ASC " : " DESC ");

        RcsProviderUtil.appendLimit(rawQuery, queryParameters.getLimit());
        String rawQueryAsString = rawQuery.toString();
        Cursor cursor = db.rawQuery(rawQueryAsString, null);

        // If the query was paginated, build the next query
        int limit = queryParameters.getLimit();
        if (limit > 0) {
            RcsProviderUtil.createContinuationTokenBundle(cursor,
                    new RcsQueryContinuationToken(MESSAGE_QUERY_CONTINUATION_TOKEN_TYPE,
                            rawQueryAsString, limit, limit), QUERY_CONTINUATION_TOKEN);
        }

        return cursor;
    }

    Cursor queryUnifiedMessageWithId(Uri uri) {
        return queryUnifiedMessageWithSelection(getMessageIdSelection(uri), null);
    }

    Cursor queryUnifiedMessageWithIdInThread(Uri uri) {
        return queryUnifiedMessageWithSelection(getMessageIdSelectionInThreadUri(uri), null);
    }

    Cursor queryUnifiedMessageWithSelection(String selection, String[] selectionArgs) {
        SQLiteDatabase db = mSqLiteOpenHelper.getReadableDatabase();
        return db.query(UNIFIED_MESSAGE_VIEW, null, selection, selectionArgs, null, null, null,
                null);
    }

    Cursor queryIncomingMessageWithId(Uri uri) {
        return queryIncomingMessageWithSelection(getMessageIdSelection(uri), null);
    }

    Cursor queryIncomingMessageWithSelection(String selection, String[] selectionArgs) {
        SQLiteDatabase db = mSqLiteOpenHelper.getReadableDatabase();
        return db.query(UNIFIED_INCOMING_MESSAGE_VIEW, null, selection, selectionArgs, null, null,
                null, null);
    }

    Cursor queryOutgoingMessageWithId(Uri uri) {
        return queryOutgoingMessageWithSelection(getMessageIdSelection(uri), null);
    }

    Cursor queryOutgoingMessageWithSelection(String selection, String[] selectionArgs) {
        SQLiteDatabase db = mSqLiteOpenHelper.getReadableDatabase();
        return db.query(UNIFIED_OUTGOING_MESSAGE_VIEW, null, selection, selectionArgs, null, null,
                null, null);
    }

    Cursor queryAllMessagesOnThread(Uri uri, String selection, String[] selectionArgs) {
        SQLiteDatabase db = mSqLiteOpenHelper.getReadableDatabase();

        String appendedSelection = appendThreadIdToSelection(uri, selection);
        return db.query(UNIFIED_MESSAGE_VIEW, null, appendedSelection, null, null, null, null);
    }

    Uri insertMessageOnThread(Uri uri, ContentValues valuesParameter, boolean isIncoming,
            boolean is1To1) {
        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();

        String threadId = RcsProviderThreadHelper.getThreadIdFromUri(uri);
        ContentValues values = new ContentValues(valuesParameter);
        values.put(RCS_THREAD_ID_COLUMN, Integer.parseInt(threadId));

        db.beginTransaction();

        ContentValues subMessageTableValues = new ContentValues();
        if (isIncoming) {
            subMessageTableValues = getIncomingMessageValues(values);
        }

        long rowId;
        try {
            rowId = db.insert(RCS_MESSAGE_TABLE, MESSAGE_ID_COLUMN, values);
            if (rowId == INSERTION_FAILED) {
                return null;
            }

            subMessageTableValues.put(MESSAGE_ID_COLUMN, rowId);
            long tempId = db.insert(
                    isIncoming ? RCS_INCOMING_MESSAGE_TABLE : RCS_OUTGOING_MESSAGE_TABLE,
                    MESSAGE_ID_COLUMN, subMessageTableValues);
            if (tempId == INSERTION_FAILED) {
                return null;
            }

            // if the thread is outgoing, insert message deliveries
            if (!isIncoming && !insertMessageDeliveriesForOutgoingMessageCreation(db, tempId,
                    threadId)) {
                return null;
            }

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        values.remove(RCS_THREAD_ID_COLUMN);

        String threadPart =  is1To1 ? RCS_1_TO_1_THREAD_URI_PART : RCS_GROUP_THREAD_URI_PART;
        String messagePart = isIncoming ? INCOMING_MESSAGE_URI_PART : OUTGOING_MESSAGE_URI_PART;

        return CONTENT_AND_AUTHORITY.buildUpon().appendPath(threadPart).appendPath(threadId).
                appendPath(messagePart).appendPath(Long.toString(rowId)).build();
    }

    // Tries to insert deliveries for outgoing message, returns false if it fails.
    private boolean insertMessageDeliveriesForOutgoingMessageCreation(
            SQLiteDatabase dbInTransaction, long messageId, String threadId) {
        try (Cursor participantsInThreadCursor = dbInTransaction.query(
                RCS_PARTICIPANT_THREAD_JUNCTION_TABLE, null, RCS_THREAD_ID_COLUMN + "=?",
                new String[]{threadId}, null, null, null)) {
            if (participantsInThreadCursor == null) {
                return false;
            }

            while (participantsInThreadCursor.moveToNext()) {
                String participantId = participantsInThreadCursor.getString(
                        participantsInThreadCursor.getColumnIndex(
                                RCS_PARTICIPANT_ID_COLUMN));

                long insertionRow = insertMessageDelivery(Long.toString(messageId), participantId,
                        new ContentValues());

                if (insertionRow == INSERTION_FAILED) {
                    return false;
                }
            }
        }
        return true;
    }

    long insertMessageDelivery(Uri uri, ContentValues values) {
        String messageId = getMessageIdFromUri(uri);
        String participantId = getParticipantIdFromDeliveryUri(uri);
        return insertMessageDelivery(messageId, participantId, values);
    }

    private long insertMessageDelivery(String messageId, String participantId,
            ContentValues valuesParameter) {
        ContentValues values = new ContentValues(valuesParameter);
        values.put(MESSAGE_ID_COLUMN, messageId);
        values.put(RCS_PARTICIPANT_ID_COLUMN, participantId);

        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();
        return db.insert(RCS_MESSAGE_DELIVERY_TABLE, MESSAGE_ID_COLUMN, values);
    }

    int updateDelivery(Uri uri, ContentValues contentValues) {
        String messageId = getMessageIdFromUri(uri);
        String participantId = getParticipantIdFromDeliveryUri(uri);

        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();
        return db.update(RCS_MESSAGE_DELIVERY_TABLE, contentValues,
                MESSAGE_ID_COLUMN + "=? AND " + RCS_PARTICIPANT_ID_COLUMN + "=?",
                new String[]{messageId, participantId});
    }

    int deleteIncomingMessageWithId(Uri uri) {
        return deleteIncomingMessageWithSelection(getMessageIdSelection(uri), null);
    }

    int deleteIncomingMessageWithSelection(String selection, String[] selectionArgs) {
        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();
        return db.delete(RCS_INCOMING_MESSAGE_TABLE, selection, selectionArgs);
    }

    int deleteOutgoingMessageWithId(Uri uri) {
        return deleteOutgoingMessageWithSelection(getMessageIdSelection(uri), null);
    }

    int deleteOutgoingMessageWithSelection(String selection, String[] selectionArgs) {
        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();
        return db.delete(RCS_OUTGOING_MESSAGE_TABLE, selection, selectionArgs);
    }

    int updateIncomingMessage(Uri uri, ContentValues values) {
        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();

        ContentValues incomingMessageValues = getIncomingMessageValues(values);

        int updateCountInIncoming = 0;
        int updateCountInCommon = 0;
        db.beginTransaction();
        if (!incomingMessageValues.isEmpty()) {
            updateCountInIncoming = db.update(RCS_INCOMING_MESSAGE_TABLE, incomingMessageValues,
                    getMessageIdSelection(uri), null);
        }
        if (!values.isEmpty()) {
            updateCountInCommon = db.update(RCS_MESSAGE_TABLE, values, getMessageIdSelection(uri),
                    null);
        }
        db.setTransactionSuccessful();
        db.endTransaction();

        return Math.max(updateCountInIncoming, updateCountInCommon);
    }

    int updateOutgoingMessage(Uri uri, ContentValues values) {
        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();
        return db.update(RCS_MESSAGE_TABLE, values, getMessageIdSelection(uri), null);
    }

    Cursor queryOutgoingMessageDeliveries(Uri uri) {
        String messageId = getMessageIdFromUri(uri);

        SQLiteDatabase db = mSqLiteOpenHelper.getReadableDatabase();
        return db.query(RCS_MESSAGE_DELIVERY_TABLE, null, MESSAGE_ID_COLUMN + "=?",
                new String[]{messageId}, null, null, null);
    }

    Cursor queryFileTransfer(Uri uri) {
        SQLiteDatabase db = mSqLiteOpenHelper.getReadableDatabase();
        return db.query(RCS_FILE_TRANSFER_TABLE, null, FILE_TRANSFER_ID_COLUMN + "=?",
                new String[]{getFileTransferIdFromUri(uri)}, null, null, null, null);
    }

    long insertFileTransferToMessage(Uri uri, ContentValues values) {
        String messageId = getMessageIdFromUri(uri);
        values.put(MESSAGE_ID_COLUMN, messageId);

        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();
        long rowId = db.insert(RCS_FILE_TRANSFER_TABLE, MESSAGE_ID_COLUMN, values);
        values.remove(MESSAGE_ID_COLUMN);

        if (rowId == INSERTION_FAILED) {
            rowId = TRANSACTION_FAILED;
        }

        return rowId;
    }

    int deleteFileTransfer(Uri uri) {
        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();
        return db.delete(RCS_FILE_TRANSFER_TABLE, FILE_TRANSFER_ID_COLUMN + "=?",
                new String[]{getFileTransferIdFromUri(uri)});
    }

    int updateFileTransfer(Uri uri, ContentValues values) {
        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();
        return db.update(RCS_FILE_TRANSFER_TABLE, values,
                FILE_TRANSFER_ID_COLUMN + "=?", new String[]{getFileTransferIdFromUri(uri)});
    }

    /**
     * Removes the incoming message values out of all values and returns as a separate content
     * values object.
     */
    private ContentValues getIncomingMessageValues(ContentValues allValues) {
        ContentValues incomingMessageValues = new ContentValues();

        if (allValues.containsKey(SENDER_PARTICIPANT_ID_COLUMN)) {
            incomingMessageValues.put(SENDER_PARTICIPANT_ID_COLUMN,
                    allValues.getAsInteger(SENDER_PARTICIPANT_ID_COLUMN));
            allValues.remove(SENDER_PARTICIPANT_ID_COLUMN);
        }

        if (allValues.containsKey(ARRIVAL_TIMESTAMP_COLUMN)) {
            incomingMessageValues.put(
                    ARRIVAL_TIMESTAMP_COLUMN, allValues.getAsLong(ARRIVAL_TIMESTAMP_COLUMN));
            allValues.remove(ARRIVAL_TIMESTAMP_COLUMN);
        }

        if (allValues.containsKey(RcsIncomingMessageColumns.SEEN_TIMESTAMP_COLUMN)) {
            incomingMessageValues.put(
                    RcsIncomingMessageColumns.SEEN_TIMESTAMP_COLUMN,
                    allValues.getAsLong(RcsIncomingMessageColumns.SEEN_TIMESTAMP_COLUMN));
            allValues.remove(RcsIncomingMessageColumns.SEEN_TIMESTAMP_COLUMN);
        }

        return incomingMessageValues;
    }

    private String appendThreadIdToSelection(Uri uri, String selection) {
        String threadIdSelection = RCS_THREAD_ID_COLUMN + "=" + getThreadIdFromUri(uri);

        if (TextUtils.isEmpty(selection)) {
            return threadIdSelection;
        }

        return "(" + selection + ") AND " + threadIdSelection;
    }

    private String getMessageIdSelection(Uri uri) {
        return MESSAGE_ID_COLUMN + "=" + getMessageIdFromUri(uri);
    }

    private String getMessageIdSelectionInThreadUri(Uri uri) {
        return MESSAGE_ID_COLUMN + "=" + getMessageIdFromThreadUri(uri);
    }

    private String getMessageIdFromUri(Uri uri) {
        return uri.getPathSegments().get(MESSAGE_ID_INDEX_IN_URI);
    }

    private String getFileTransferIdFromUri(Uri uri) {
        // this works because messages and file transfer uri's have the same indices.
        return getMessageIdFromUri(uri);
    }

    private String getParticipantIdFromDeliveryUri(Uri uri) {
        // this works because messages in threads and participants in deliveries have the same
        // indices.
        return getMessageIdFromThreadUri(uri);
    }

    private String getMessageIdFromThreadUri(Uri uri) {
        return uri.getPathSegments().get(MESSAGE_ID_INDEX_IN_THREAD_URI);
    }
}
