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
 * limitations under the License
 */
package com.android.providers.telephony;

import static android.provider.Telephony.RcsColumns.RcsEventTypes.PARTICIPANT_JOINED_EVENT_TYPE;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.FILE_SIZE_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.SESSION_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsGroupThreadColumns.GROUP_NAME_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsMessageColumns.MESSAGE_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsMessageColumns.MESSAGE_TEXT_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsMessageColumns.MESSAGE_TYPE_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsMessageColumns.ORIGINATION_TIMESTAMP_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsParticipantColumns.CANONICAL_ADDRESS_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsParticipantColumns.RCS_ALIAS_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsParticipantColumns.RCS_PARTICIPANT_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsThreadColumns.RCS_THREAD_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsThreadEventColumns.DESTINATION_PARTICIPANT_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsThreadEventColumns.EVENT_TYPE_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsThreadEventColumns.NEW_NAME_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsThreadEventColumns.SOURCE_PARTICIPANT_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsUnifiedThreadColumns.THREAD_TYPE_COLUMN;
import static android.telephony.ims.RcsEventQueryParams.EVENT_QUERY_PARAMETERS_KEY;
import static android.telephony.ims.RcsEventQueryParams.GROUP_THREAD_NAME_CHANGED_EVENT;
import static android.telephony.ims.RcsMessageQueryParams.MESSAGE_QUERY_PARAMETERS_KEY;
import static android.telephony.ims.RcsParticipantQueryParams.PARTICIPANT_QUERY_PARAMETERS_KEY;
import static android.telephony.ims.RcsQueryContinuationToken.QUERY_CONTINUATION_TOKEN;
import static android.telephony.ims.RcsThreadQueryParams.THREAD_QUERY_PARAMETERS_KEY;

import static com.android.providers.telephony.RcsProviderHelper.setup1To1Thread;
import static com.google.common.truth.Truth.assertThat;

import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.telephony.ims.RcsEventQueryParams;
import android.telephony.ims.RcsGroupThread;
import android.telephony.ims.RcsMessageQueryParams;
import android.telephony.ims.RcsParticipantQueryParams;
import android.telephony.ims.RcsQueryContinuationToken;
import android.telephony.ims.RcsThreadQueryParams;
import android.test.mock.MockContentResolver;

import androidx.test.runner.AndroidJUnit4;

import com.android.providers.telephony.RcsProviderTestable.MockContextWithProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public class RcsProviderQueryTest {
    private MockContentResolver mContentResolver;
    private RcsProviderTestable mRcsProvider;

    private static final String GROUP_NAME = "group name";

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mRcsProvider = new RcsProviderTestable();
        MockContextWithProvider context = new MockContextWithProvider(mRcsProvider);
        mContentResolver = context.getContentResolver();

        // insert two participants
        Uri participantUri = Uri.parse("content://rcs/participant");
        ContentValues contentValues = new ContentValues();
        contentValues.put(CANONICAL_ADDRESS_ID_COLUMN, 99);
        contentValues.put(RCS_ALIAS_COLUMN, "Some alias");
        mContentResolver.insert(participantUri, contentValues);

        contentValues.clear();
        contentValues.put(CANONICAL_ADDRESS_ID_COLUMN, 100);
        contentValues.put(RCS_ALIAS_COLUMN, "Some other alias");
        mContentResolver.insert(participantUri, contentValues);

        // insert two 1 to 1 threads
        setup1To1Thread(mContentResolver, 1, 1);
        setup1To1Thread(mContentResolver, 2, 2);

        // insert one group thread
        ContentValues groupContentValues = new ContentValues(1);
        groupContentValues.put(GROUP_NAME_COLUMN, GROUP_NAME);
        assertThat(mContentResolver.insert(Uri.parse("content://rcs/group_thread"),
                groupContentValues)).isEqualTo(Uri.parse("content://rcs/group_thread/3"));

        // put participants into the group
        mContentResolver.insert(Uri.parse("content://rcs/group_thread/3/participant/1"), null);
        mContentResolver.insert(Uri.parse("content://rcs/group_thread/3/participant/2"), null);

        // insert two messages into first thread, leave the second one empty, insert one into group
        // thread
        ContentValues messageValues = new ContentValues();

        messageValues.put(ORIGINATION_TIMESTAMP_COLUMN, 300);
        messageValues.put(MESSAGE_TEXT_COLUMN, "Old message");
        assertThat(mContentResolver.insert(Uri.parse("content://rcs/p2p_thread/1/incoming_message"),
                messageValues)).isEqualTo(
                Uri.parse("content://rcs/p2p_thread/1/incoming_message/1"));

        messageValues.clear();
        messageValues.put(ORIGINATION_TIMESTAMP_COLUMN, 400);
        messageValues.put(MESSAGE_TEXT_COLUMN, "New message");
        assertThat(mContentResolver.insert(Uri.parse("content://rcs/p2p_thread/1/outgoing_message"),
                messageValues)).isEqualTo(
                Uri.parse("content://rcs/p2p_thread/1/outgoing_message/2"));

        messageValues.clear();
        messageValues.put(ORIGINATION_TIMESTAMP_COLUMN, 200);
        messageValues.put(MESSAGE_TEXT_COLUMN, "Group message");
        assertThat(
                mContentResolver.insert(Uri.parse("content://rcs/group_thread/3/incoming_message"),
                        messageValues)).isEqualTo(
                Uri.parse("content://rcs/group_thread/3/incoming_message/3"));

        // Add two events to the group thread
        ContentValues eventValues = new ContentValues();
        eventValues.put(NEW_NAME_COLUMN, "New group name");
        assertThat(mContentResolver.insert(
                Uri.parse("content://rcs/group_thread/3/name_changed_event"),
                eventValues)).isEqualTo(
                Uri.parse("content://rcs/group_thread/3/name_changed_event/1"));

        eventValues.clear();
        eventValues.put(SOURCE_PARTICIPANT_ID_COLUMN, 1);
        eventValues.put(DESTINATION_PARTICIPANT_ID_COLUMN, 2);
        assertThat(mContentResolver.insert(
                Uri.parse("content://rcs/group_thread/3/participant_joined_event"),
                eventValues)).isEqualTo(
                Uri.parse("content://rcs/group_thread/3/participant_joined_event/2"));
    }

    @After
    public void tearDown() {
        mRcsProvider.tearDown();
    }

    @Test
    public void testCanQueryUnifiedThreads() {
        RcsThreadQueryParams queryParameters = new RcsThreadQueryParams.Builder().build();
        Bundle bundle = new Bundle();
        bundle.putParcelable(THREAD_QUERY_PARAMETERS_KEY, queryParameters);

        Cursor cursor = mContentResolver.query(Uri.parse("content://rcs/thread"),
                null, bundle, null);
        assertThat(cursor.getCount()).isEqualTo(3);

        cursor.moveToNext();
        assertThat(cursor.getInt(cursor.getColumnIndex(THREAD_TYPE_COLUMN))).isEqualTo(1);
        assertThat(cursor.getString(cursor.getColumnIndex(GROUP_NAME_COLUMN))).isEqualTo(
                GROUP_NAME);
        assertThat(cursor.getInt(cursor.getColumnIndex(ORIGINATION_TIMESTAMP_COLUMN))).isEqualTo(
                200);
        assertThat(cursor.getString(cursor.getColumnIndex(MESSAGE_TEXT_COLUMN))).isEqualTo(
                "Group message");
        cursor.moveToNext();
        assertThat(cursor.getInt(cursor.getColumnIndex(THREAD_TYPE_COLUMN))).isEqualTo(0);
        assertThat(cursor.getString(cursor.getColumnIndex(GROUP_NAME_COLUMN))).isEqualTo(null);
        assertThat(cursor.getInt(cursor.getColumnIndex(ORIGINATION_TIMESTAMP_COLUMN))).isEqualTo(0);
        assertThat(cursor.getString(cursor.getColumnIndex(MESSAGE_TEXT_COLUMN))).isEqualTo(null);
        cursor.moveToNext();
        assertThat(cursor.getInt(cursor.getColumnIndex(THREAD_TYPE_COLUMN))).isEqualTo(0);
        assertThat(cursor.getString(cursor.getColumnIndex(GROUP_NAME_COLUMN))).isEqualTo(null);
        assertThat(cursor.getInt(cursor.getColumnIndex(ORIGINATION_TIMESTAMP_COLUMN))).isEqualTo(
                400);
        assertThat(cursor.getString(cursor.getColumnIndex(MESSAGE_TEXT_COLUMN))).isEqualTo(
                "New message");
    }

    @Test
    public void testCanQueryUnifiedThreadsWithLimitAndSorting() {
        RcsThreadQueryParams queryParameters = new RcsThreadQueryParams.Builder()
                .setThreadType(RcsThreadQueryParams.THREAD_TYPE_1_TO_1).setResultLimit(1)
                .setSortProperty(RcsThreadQueryParams.SORT_BY_TIMESTAMP).setSortDirection(true)
                .build();
        Bundle bundle = new Bundle();
        bundle.putParcelable(THREAD_QUERY_PARAMETERS_KEY, queryParameters);

        Cursor cursor = mContentResolver.query(Uri.parse("content://rcs/thread"),
                null, bundle, null);
        assertThat(cursor.getCount()).isEqualTo(1);

        cursor.moveToNext();
        assertThat(cursor.getInt(cursor.getColumnIndex(THREAD_TYPE_COLUMN))).isEqualTo(0);
        assertThat(cursor.getString(cursor.getColumnIndex(GROUP_NAME_COLUMN))).isEqualTo(null);
        assertThat(cursor.getInt(cursor.getColumnIndex(ORIGINATION_TIMESTAMP_COLUMN))).isEqualTo(0);
        assertThat(cursor.getString(cursor.getColumnIndex(MESSAGE_TEXT_COLUMN))).isEqualTo(null);
    }

    @Test
    public void testCanContinueThreadQuery() {
        // Limit results to 1.
        RcsThreadQueryParams queryParameters =
                new RcsThreadQueryParams.Builder().setResultLimit(1).build();
        Bundle bundle = new Bundle();
        bundle.putParcelable(THREAD_QUERY_PARAMETERS_KEY, queryParameters);

        // Perform an initial query, verify first thread is returned
        Cursor cursor = mContentResolver.query(Uri.parse("content://rcs/thread"), null, bundle,
                null);
        assertThat(cursor.getCount()).isEqualTo(1);

        cursor.moveToNext();
        assertThat(cursor.getInt(cursor.getColumnIndex(THREAD_TYPE_COLUMN))).isEqualTo(1);
        assertThat(cursor.getString(cursor.getColumnIndex(GROUP_NAME_COLUMN))).isEqualTo(
                GROUP_NAME);
        assertThat(cursor.getInt(cursor.getColumnIndex(ORIGINATION_TIMESTAMP_COLUMN))).isEqualTo(
                200);
        assertThat(cursor.getString(cursor.getColumnIndex(MESSAGE_TEXT_COLUMN))).isEqualTo(
                "Group message");

        // Put the continuation token in the bundle to do a follow up query
        RcsQueryContinuationToken continuationToken = cursor.getExtras().getParcelable(
                QUERY_CONTINUATION_TOKEN);
        bundle.clear();
        bundle.putParcelable(QUERY_CONTINUATION_TOKEN, continuationToken);
        cursor.close();

        cursor = mContentResolver.query(Uri.parse("content://rcs/thread"), null, bundle,
                null);
        assertThat(cursor.getCount()).isEqualTo(1);
        cursor.moveToNext();
        assertThat(cursor.getInt(cursor.getColumnIndex(THREAD_TYPE_COLUMN))).isEqualTo(0);
        assertThat(cursor.getString(cursor.getColumnIndex(GROUP_NAME_COLUMN))).isEqualTo(null);
        assertThat(cursor.getInt(cursor.getColumnIndex(ORIGINATION_TIMESTAMP_COLUMN))).isEqualTo(0);
        assertThat(cursor.getString(cursor.getColumnIndex(MESSAGE_TEXT_COLUMN))).isEqualTo(null);
        cursor.close();

        // Put the continuation token in the bundle to do a follow up query again, verify third
        // thread is returned
        continuationToken = cursor.getExtras().getParcelable(QUERY_CONTINUATION_TOKEN);
        bundle.clear();
        bundle.putParcelable(QUERY_CONTINUATION_TOKEN, continuationToken);
        cursor = mContentResolver.query(Uri.parse("content://rcs/thread"), null, bundle,
                null);
        assertThat(cursor.getCount()).isEqualTo(1);
        cursor.moveToNext();
        assertThat(cursor.getInt(cursor.getColumnIndex(THREAD_TYPE_COLUMN))).isEqualTo(0);
        assertThat(cursor.getString(cursor.getColumnIndex(GROUP_NAME_COLUMN))).isEqualTo(null);
        assertThat(cursor.getInt(cursor.getColumnIndex(ORIGINATION_TIMESTAMP_COLUMN))).isEqualTo(
                400);
        assertThat(cursor.getString(cursor.getColumnIndex(MESSAGE_TEXT_COLUMN))).isEqualTo(
                "New message");
        cursor.close();
    }

    @Test
    public void testQuery1To1Threads() {
        // verify two threads are returned in the query
        Cursor cursor = mContentResolver.query(Uri.parse("content://rcs/p2p_thread"),
                new String[]{RCS_THREAD_ID_COLUMN}, null, null, null);
        assertThat(cursor.getCount()).isEqualTo(2);
    }

    @Test
    public void testQueryGroupThreads() {
        // verify one thread is returned in the query
        Cursor cursor = mContentResolver.query(Uri.parse("content://rcs/group_thread"),
                new String[]{GROUP_NAME_COLUMN}, null, null, null);
        assertThat(cursor.getCount()).isEqualTo(1);

        cursor.moveToNext();
        assertThat(cursor.getString(0)).isEqualTo(GROUP_NAME);
    }

    @Test
    public void testQueryParticipant() {
        RcsParticipantQueryParams queryParameters = new RcsParticipantQueryParams.Builder()
                .build();

        Bundle bundle = new Bundle();
        bundle.putParcelable(PARTICIPANT_QUERY_PARAMETERS_KEY, queryParameters);

        Cursor cursor = mContentResolver.query(Uri.parse("content://rcs/participant"), null, bundle,
                null);
        assertThat(cursor.getCount()).isEqualTo(2);

        cursor.moveToNext();
        assertThat(cursor.getInt(cursor.getColumnIndex(RCS_PARTICIPANT_ID_COLUMN))).isEqualTo(2);
        assertThat(cursor.getInt(cursor.getColumnIndex(CANONICAL_ADDRESS_ID_COLUMN))).isEqualTo(
                100);
        assertThat(cursor.getString(cursor.getColumnIndex(RCS_ALIAS_COLUMN))).isEqualTo(
                "Some other alias");

        cursor.moveToNext();
        assertThat(cursor.getInt(cursor.getColumnIndex(RCS_PARTICIPANT_ID_COLUMN))).isEqualTo(1);
        assertThat(cursor.getInt(cursor.getColumnIndex(CANONICAL_ADDRESS_ID_COLUMN))).isEqualTo(99);
        assertThat(cursor.getString(cursor.getColumnIndex(RCS_ALIAS_COLUMN))).isEqualTo(
                "Some alias");
    }

    @Test
    public void testQueryParticipantWithContinuation() {
        Uri participantUri = Uri.parse("content://rcs/participant");

        // Perform the initial query
        RcsParticipantQueryParams queryParameters =
                new RcsParticipantQueryParams.Builder().setAliasLike("%ali%").setSortProperty(
                        RcsParticipantQueryParams.SORT_BY_ALIAS).setSortDirection(true)
                        .setResultLimit(1).build();

        Bundle bundle = new Bundle();
        bundle.putParcelable(PARTICIPANT_QUERY_PARAMETERS_KEY, queryParameters);

        Cursor cursor = mContentResolver.query(participantUri, null, bundle, null);
        assertThat(cursor.getCount()).isEqualTo(1);

        cursor.moveToNext();
        assertThat(cursor.getInt(0)).isEqualTo(1);
        assertThat(cursor.getInt(1)).isEqualTo(99);
        assertThat(cursor.getString(2)).isEqualTo("Some alias");

        // Perform the continuation query
        RcsQueryContinuationToken continuationToken = cursor.getExtras().getParcelable(
                QUERY_CONTINUATION_TOKEN);
        bundle.clear();
        bundle.putParcelable(QUERY_CONTINUATION_TOKEN, continuationToken);

        cursor = mContentResolver.query(participantUri, null, bundle, null);
        assertThat(cursor.getCount()).isEqualTo(1);

        cursor.moveToNext();
        assertThat(cursor.getInt(0)).isEqualTo(2);
        assertThat(cursor.getInt(1)).isEqualTo(100);
        assertThat(cursor.getString(2)).isEqualTo("Some other alias");

        // Perform the continuation query to verify no entries left
        continuationToken = cursor.getExtras().getParcelable(QUERY_CONTINUATION_TOKEN);
        bundle.clear();
        bundle.putParcelable(QUERY_CONTINUATION_TOKEN, continuationToken);

        cursor = mContentResolver.query(participantUri, null, bundle, null);
        assertThat(cursor.getCount()).isEqualTo(0);
        continuationToken = cursor.getExtras().getParcelable(QUERY_CONTINUATION_TOKEN);
        assertThat(continuationToken).isNull();
    }

    @Test
    public void testQueryGroupParticipants() {
        // TODO - implement
    }

    @Test
    @Ignore // TODO: fix and un-ignore
    public void testQueryEvents() {
        RcsEventQueryParams queryParameters = new RcsEventQueryParams.Builder().build();

        Bundle bundle = new Bundle();
        bundle.putParcelable(EVENT_QUERY_PARAMETERS_KEY, queryParameters);

        Cursor cursor = mContentResolver.query(Uri.parse("content://rcs/event"), null, bundle,
                null);
        assertThat(cursor.getCount()).isEqualTo(2);

        cursor.moveToNext();
        assertThat(cursor.getInt(cursor.getColumnIndex(EVENT_TYPE_COLUMN))).isEqualTo(
                PARTICIPANT_JOINED_EVENT_TYPE);
        assertThat(cursor.getInt(cursor.getColumnIndex(SOURCE_PARTICIPANT_ID_COLUMN))).isEqualTo(
                1);
        assertThat(cursor.getInt(cursor.getColumnIndex(DESTINATION_PARTICIPANT_ID_COLUMN))).isEqualTo(
                2);

        cursor.moveToNext();
        assertThat(cursor.getInt(cursor.getColumnIndex(EVENT_TYPE_COLUMN))).isEqualTo(
                GROUP_THREAD_NAME_CHANGED_EVENT);
        assertThat(cursor.getString(cursor.getColumnIndex(NEW_NAME_COLUMN))).isEqualTo(
                "New group name");
    }

    @Test
    @Ignore // TODO: fix and un-ignore
    public void testQueryEventsWithContinuation() {
        RcsEventQueryParams queryParameters =
                new RcsEventQueryParams.Builder().setResultLimit(1).setSortDirection(true)
                        .build();

        Bundle bundle = new Bundle();
        bundle.putParcelable(EVENT_QUERY_PARAMETERS_KEY, queryParameters);

        Cursor cursor = mContentResolver.query(Uri.parse("content://rcs/event"), null, bundle,
                null);
        assertThat(cursor.getCount()).isEqualTo(1);

        cursor.moveToNext();
        assertThat(cursor.getInt(cursor.getColumnIndex(EVENT_TYPE_COLUMN))).isEqualTo(
                GROUP_THREAD_NAME_CHANGED_EVENT);
        assertThat(cursor.getString(cursor.getColumnIndex(NEW_NAME_COLUMN))).isEqualTo(
                "New group name");
    }

    @Test
    @Ignore // TODO: fix and un-ignore
    public void testQueryEventsWithTypeLimitation() {
        RcsEventQueryParams queryParameters =
                new RcsEventQueryParams.Builder().setEventType(
                        GROUP_THREAD_NAME_CHANGED_EVENT).build();
        Bundle bundle = new Bundle();
        bundle.putParcelable(EVENT_QUERY_PARAMETERS_KEY, queryParameters);

        Cursor cursor = mContentResolver.query(Uri.parse("content://rcs/event"), null, bundle,
                null);
        assertThat(cursor.getCount()).isEqualTo(1);

        cursor.moveToNext();
        assertThat(cursor.getInt(cursor.getColumnIndex(EVENT_TYPE_COLUMN))).isEqualTo(
                GROUP_THREAD_NAME_CHANGED_EVENT);
        assertThat(cursor.getString(cursor.getColumnIndex(NEW_NAME_COLUMN))).isEqualTo(
                "New group name");
    }

    @Test
    @Ignore // TODO: fix and un-ignore
    public void testQueryMessages() {
        RcsMessageQueryParams queryParameters = new RcsMessageQueryParams.Builder().build();
        Bundle bundle = new Bundle();
        bundle.putParcelable(MESSAGE_QUERY_PARAMETERS_KEY, queryParameters);

        Cursor cursor = mContentResolver.query(Uri.parse("content://rcs/message"), null, bundle,
                null);

        assertThat(cursor.getCount()).isEqualTo(3);
        cursor.moveToNext();
        assertThat(cursor.getInt(cursor.getColumnIndex(MESSAGE_ID_COLUMN))).isEqualTo(3);
        assertThat(cursor.getInt(cursor.getColumnIndex(RCS_THREAD_ID_COLUMN))).isEqualTo(3);

        cursor.moveToNext();
        assertThat(cursor.getInt(cursor.getColumnIndex(MESSAGE_ID_COLUMN))).isEqualTo(2);
        assertThat(cursor.getInt(cursor.getColumnIndex(RCS_THREAD_ID_COLUMN))).isEqualTo(1);

        cursor.moveToNext();
        assertThat(cursor.getInt(cursor.getColumnIndex(MESSAGE_ID_COLUMN))).isEqualTo(1);
        assertThat(cursor.getInt(cursor.getColumnIndex(RCS_THREAD_ID_COLUMN))).isEqualTo(1);
    }

    @Test
    @Ignore // TODO: fix and un-ignore
    public void testQueryMessagesWithContinuation() {
        RcsMessageQueryParams queryParameters =
                new RcsMessageQueryParams.Builder().setMessageLike("%o%message").setResultLimit(
                        1).setSortProperty(RcsMessageQueryParams.SORT_BY_TIMESTAMP)
                        .setSortDirection(true).build();
        Bundle bundle = new Bundle();
        bundle.putParcelable(MESSAGE_QUERY_PARAMETERS_KEY, queryParameters);

        // Perform the initial query
        Cursor cursor = mContentResolver.query(Uri.parse("content://rcs/message"), null, bundle,
                null);

        assertThat(cursor.getCount()).isEqualTo(1);
        cursor.moveToNext();
        assertThat(cursor.getInt(cursor.getColumnIndex(MESSAGE_ID_COLUMN))).isEqualTo(3);
        assertThat(cursor.getInt(cursor.getColumnIndex(RCS_THREAD_ID_COLUMN))).isEqualTo(3);
        assertThat(cursor.getInt(cursor.getColumnIndex(ORIGINATION_TIMESTAMP_COLUMN))).isEqualTo(
                200);
        assertThat(cursor.getString(cursor.getColumnIndex(MESSAGE_TYPE_COLUMN))).isEqualTo(
                "Group message");

        // Perform the continuation query
        RcsQueryContinuationToken continuationToken = cursor.getExtras().getParcelable(
                QUERY_CONTINUATION_TOKEN);
        assertThat(continuationToken).isNotNull();
        bundle.clear();
        bundle.putParcelable(QUERY_CONTINUATION_TOKEN, continuationToken);

        cursor = mContentResolver.query(Uri.parse("content://rcs/message"), null, bundle, null);

        assertThat(cursor.getCount()).isEqualTo(1);
        cursor.moveToNext();
        assertThat(cursor.getInt(cursor.getColumnIndex(MESSAGE_ID_COLUMN))).isEqualTo(1);
        assertThat(cursor.getInt(cursor.getColumnIndex(RCS_THREAD_ID_COLUMN))).isEqualTo(1);
        assertThat(cursor.getInt(cursor.getColumnIndex(ORIGINATION_TIMESTAMP_COLUMN))).isEqualTo(
                300);
        assertThat(cursor.getString(cursor.getColumnIndex(MESSAGE_TEXT_COLUMN))).isEqualTo(
                "Old message");
    }

    @Test
    @Ignore // TODO: fix and un-ignore
    public void testQueryMessagesWithThreadFilter() {
        RcsMessageQueryParams queryParameters =
                new RcsMessageQueryParams.Builder().setThread(new RcsGroupThread(null, 3))
                        .build();
        Bundle bundle = new Bundle();
        bundle.putParcelable(MESSAGE_QUERY_PARAMETERS_KEY, queryParameters);

        // Perform the initial query
        Cursor cursor = mContentResolver.query(Uri.parse("content://rcs/message"), null, bundle,
                null);

        assertThat(cursor.getCount()).isEqualTo(1);
        cursor.moveToNext();
        assertThat(cursor.getInt(cursor.getColumnIndex(MESSAGE_ID_COLUMN))).isEqualTo(3);
        assertThat(cursor.getInt(cursor.getColumnIndex(RCS_THREAD_ID_COLUMN))).isEqualTo(3);
        assertThat(cursor.getInt(cursor.getColumnIndex(ORIGINATION_TIMESTAMP_COLUMN))).isEqualTo(
                200);
        assertThat(cursor.getString(cursor.getColumnIndex(MESSAGE_TEXT_COLUMN))).isEqualTo(
                "Group message");

    }

    @Test
    public void testQueryParticipantOf1To1Thread() {
        // query the participant back
        Uri queryUri = Uri.parse("content://rcs/p2p_thread/1/participant");
        Cursor cursor = mContentResolver.query(queryUri, null, null, null, null);
        assertThat(cursor.getCount()).isEqualTo(1);
        cursor.moveToNext();

        assertThat(cursor.getInt(1)).isEqualTo(99);
        assertThat(cursor.getString(2)).isEqualTo("Some alias");
    }

    @Test
    public void testQueryParticipantOfGroupThread() {
        // query all the participants in this thread
        Uri queryUri = Uri.parse("content://rcs/group_thread/3/participant");
        Cursor cursor = mContentResolver.query(queryUri, null, null, null, null);
        assertThat(cursor.getCount()).isEqualTo(1);
        cursor.moveToNext();

        assertThat(cursor.getInt(cursor.getColumnIndex(RCS_PARTICIPANT_ID_COLUMN))).isEqualTo(1);
        assertThat(cursor.getInt(cursor.getColumnIndex(CANONICAL_ADDRESS_ID_COLUMN))).isEqualTo(99);
        assertThat(cursor.getString(cursor.getColumnIndex(RCS_ALIAS_COLUMN))).isEqualTo(
                "Some alias");
    }

    @Test
    public void testQueryParticipantOfGroupThreadWithId() {
        Cursor cursor = mContentResolver.query(
                Uri.parse("content://rcs/group_thread/3/participant/1"), null, null, null, null);
        assertThat(cursor.getCount()).isEqualTo(1);
        cursor.moveToNext();

        assertThat(cursor.getInt(cursor.getColumnIndex(RCS_PARTICIPANT_ID_COLUMN))).isEqualTo(1);
        assertThat(cursor.getInt(cursor.getColumnIndex(CANONICAL_ADDRESS_ID_COLUMN))).isEqualTo(99);
        assertThat(cursor.getString(cursor.getColumnIndex(RCS_ALIAS_COLUMN))).isEqualTo(
                "Some alias");
    }

    @Test
    public void testQueryFileTransfer() {
        ContentValues values = new ContentValues();
        // add an incoming message to the thread 2
        assertThat(mContentResolver.insert(Uri.parse("content://rcs/p2p_thread/2/incoming_message"),
                values)).isEqualTo(Uri.parse("content://rcs/p2p_thread/2/incoming_message/4"));

        // add a file transfer
        values.put(SESSION_ID_COLUMN, "session_id");
        values.put(FILE_SIZE_COLUMN, 1234567890);
        assertThat(
                mContentResolver.insert(Uri.parse("content://rcs/message/4/file_transfer"),
                        values)).isEqualTo(Uri.parse("content://rcs/file_transfer/1"));

        // query the file transfer back
        Cursor cursor = mContentResolver.query(Uri.parse("content://rcs/file_transfer/1"), null,
                null, null, null, null);
        assertThat(cursor.getCount()).isEqualTo(1);

        cursor.moveToNext();
        assertThat(cursor.getInt(0)).isEqualTo(1);
        assertThat(cursor.getInt(1)).isEqualTo(4);
        assertThat(cursor.getString(2)).isEqualTo("session_id");
        assertThat(cursor.getLong(5)).isEqualTo(1234567890);
    }
}
