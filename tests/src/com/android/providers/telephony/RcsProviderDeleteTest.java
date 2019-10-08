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

import static android.provider.Telephony.RcsColumns.RcsGroupThreadColumns.GROUP_NAME_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsGroupThreadColumns.OWNER_PARTICIPANT_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsParticipantColumns.CANONICAL_ADDRESS_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsParticipantColumns.RCS_ALIAS_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsParticipantEventColumns.NEW_ALIAS_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsThreadEventColumns.NEW_NAME_COLUMN;

import static com.android.providers.telephony.RcsProvider.RCS_MESSAGE_TABLE;
import static com.android.providers.telephony.RcsProviderHelper.setup1To1Thread;
import static com.google.common.truth.Truth.assertThat;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.test.mock.MockContentResolver;

import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public class RcsProviderDeleteTest {
    private MockContentResolver mContentResolver;
    private RcsProviderTestable mRcsProvider;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mRcsProvider = new RcsProviderTestable();
        RcsProviderTestable.MockContextWithProvider
                context = new RcsProviderTestable.MockContextWithProvider(mRcsProvider);
        mContentResolver = context.getContentResolver();

        // insert a participant
        //  first into the MmsSmsProvider
        mRcsProvider.getWritableDatabase().execSQL(
                "INSERT INTO canonical_addresses VALUES (1, \"+15551234567\")");

        //  then into the RcsProvider
        ContentValues participantValues = new ContentValues();
        participantValues.put(RCS_ALIAS_COLUMN, "Bob");
        participantValues.put(CANONICAL_ADDRESS_ID_COLUMN, 1);
        assertThat(mContentResolver.insert(Uri.parse("content://rcs/participant"),
                participantValues)).isEqualTo(Uri.parse("content://rcs/participant/1"));

        setup1To1Thread(mContentResolver);

        // insert one group thread
        ContentValues groupContentValues = new ContentValues();
        groupContentValues.put(OWNER_PARTICIPANT_COLUMN, 1);
        groupContentValues.put(GROUP_NAME_COLUMN, "name");
        Uri groupThreadUri = Uri.parse("content://rcs/group_thread");
        assertThat(mContentResolver.insert(groupThreadUri, groupContentValues)).isEqualTo(
                Uri.parse("content://rcs/group_thread/2"));

        Uri addParticipantToGroupThread = Uri.parse("content://rcs/group_thread/2/participant/1");
        assertThat(mContentResolver.insert(addParticipantToGroupThread, null)).isEqualTo(
                addParticipantToGroupThread);

        // add incoming and outgoing messages to both threads
        ContentValues messageValues = new ContentValues();
        assertThat(mContentResolver.insert(Uri.parse("content://rcs/p2p_thread/1/incoming_message"),
                messageValues)).isEqualTo(
                Uri.parse("content://rcs/p2p_thread/1/incoming_message/1"));
        assertThat(mContentResolver.insert(Uri.parse("content://rcs/p2p_thread/1/outgoing_message"),
                messageValues)).isEqualTo(
                Uri.parse("content://rcs/p2p_thread/1/outgoing_message/2"));
        assertThat(
                mContentResolver.insert(Uri.parse("content://rcs/group_thread/2/incoming_message"),
                        messageValues)).isEqualTo(
                Uri.parse("content://rcs/group_thread/2/incoming_message/3"));
        assertThat(
                mContentResolver.insert(Uri.parse("content://rcs/group_thread/2/outgoing_message"),
                        messageValues)).isEqualTo(
                Uri.parse("content://rcs/group_thread/2/outgoing_message/4"));

        // add a file transfer to a message
        ContentValues fileTransferValues = new ContentValues();
        assertThat(mContentResolver.insert(Uri.parse("content://rcs/message/3/file_transfer"),
                fileTransferValues)).isEqualTo(Uri.parse("content://rcs/file_transfer/1"));

        // insert an alias change event
        ContentValues eventValues = new ContentValues();
        eventValues.put(NEW_ALIAS_COLUMN, "new alias");
        assertThat(
                mContentResolver.insert(Uri.parse("content://rcs/participant/1/alias_change_event"),
                        eventValues)).isEqualTo(Uri.parse(
                "content://rcs/participant/1/alias_change_event/1"));

        // create a group name change event
        eventValues.clear();
        eventValues.put(NEW_NAME_COLUMN, "new name");
        assertThat(mContentResolver.insert(
                Uri.parse("content://rcs/group_thread/2/name_changed_event"),
                eventValues)).isEqualTo(Uri.parse(
                "content://rcs/group_thread/2/name_changed_event/1"));
    }

    @After
    public void tearDown() {
        mRcsProvider.tearDown();
    }

    @Test
    public void testDelete1To1ThreadWithId() {
        assertThat(mContentResolver.delete(Uri.parse("content://rcs/p2p_thread/1"), null,
                null)).isEqualTo(1);
        assertDeletionViaQuery("content://rcs/p2p_thread/1");
    }

    @Test
    public void testDelete1To1ThreadWithSelection() {
        assertThat(mContentResolver.delete(Uri.parse("content://rcs/p2p_thread"),
                "rcs_fallback_thread_id=1", null)).isEqualTo(1);
        assertDeletionViaQuery("content://rcs/p2p_thread/1");
    }

    @Test
    public void testDeleteGroupThreadWithId() {
        assertThat(mContentResolver.delete(Uri.parse("content://rcs/group_thread/2"), null,
                null)).isEqualTo(1);
        assertDeletionViaQuery("content://rcs/group_thread/2");
    }

    @Test
    public void testDeleteGroupThreadWithSelection() {
        assertThat(mContentResolver.delete(Uri.parse("content://rcs/group_thread"),
                "group_name=\"name\"", null)).isEqualTo(1);
        assertDeletionViaQuery("content://rcs/group_thread/1");
    }

    @Test
    public void testDeleteParticipantWithIdWhileParticipatingInAThread() {
        assertThat(mContentResolver.delete(Uri.parse("content://rcs/participant/1"), null,
                null)).isEqualTo(0);
    }

    @Test
    public void testDeleteParticipantAfterLeavingThreads() {
        // leave the first thread
        assertThat(mContentResolver.delete(Uri.parse("content://rcs/group_thread/2/participant/1"),
                null, null)).isEqualTo(1);

        // try deleting the participant. It should fail
        assertThat(mContentResolver.delete(Uri.parse("content://rcs/participant/1"), null,
                null)).isEqualTo(0);

        // delete the p2p thread
        assertThat(mContentResolver.delete(Uri.parse("content://rcs/p2p_thread/1"), null,
                null)).isEqualTo(1);

        // try deleting the participant. It should succeed
        assertThat(mContentResolver.delete(Uri.parse("content://rcs/participant/1"), null,
                null)).isEqualTo(1);
        assertDeletionViaQuery("content://rcs/participant/1");
    }

    @Test
    public void testDeleteParticipantWithSelectionFails() {
        assertThat(
                mContentResolver.delete(Uri.parse("content://rcs/participant"), "rcs_alias=\"Bob\"",
                        null)).isEqualTo(0);
    }

    @Test
    public void testDeleteParticipantFrom1To1ThreadFails() {
        assertThat(
                mContentResolver.delete(Uri.parse("content://rcs/p2p_thread/1/participant/1"), null,
                        null)).isEqualTo(0);
    }

    @Test
    public void testDeleteParticipantFromGroupThread() {
        assertThat(mContentResolver.delete(Uri.parse("content://rcs/group_thread/2/participant/1"),
                null, null)).isEqualTo(1);
        assertDeletionViaQuery("content://rcs/group_thread/2/participant");
    }

    @Test
    public void testDeleteParticipantFromGroupThreadWithSelectionFails() {
        assertThat(mContentResolver.delete(Uri.parse("content://rcs/group_thread/2/participant"),
                "rcs_alias=?", new String[]{"Bob"})).isEqualTo(0);
    }

    @Test
    public void testDeleteMessagesUsingUnifiedMessageViewFails() {
        assertThat(mContentResolver.delete(Uri.parse("content://rcs/message/1"), null,
                null)).isEqualTo(0);
    }

    @Test
    public void testDeleteMessagesUsingThreadUrisFails() {
        assertThat(mContentResolver.delete(Uri.parse("content://rcs/p2p_thread/1/message/1"), null,
                null)).isEqualTo(0);
        assertThat(
                mContentResolver.delete(Uri.parse("content://rcs/p2p_thread/1/incoming_message/1"),
                        null, null)).isEqualTo(0);
    }

    @Test
    @Ignore // TODO: fix and un-ignore
    public void testDeleteMessage() {
        // verify there exists 4 messages
        Cursor cursor = mContentResolver.query(Uri.parse("content://rcs/message"), null, null,
                null);
        assertThat(cursor.getCount()).isEqualTo(4);
        cursor.close();

        // delete 2 of them
        assertThat(mContentResolver.delete(Uri.parse("content://rcs/incoming_message/1"), null,
                null)).isEqualTo(1);
        assertThat(mContentResolver.delete(Uri.parse("content://rcs/outgoing_message/4"), null,
                null)).isEqualTo(1);

        // verify that only 2 messages are left
        cursor = mContentResolver.query(Uri.parse("content://rcs/message"), null, null, null);
        assertThat(cursor.getCount()).isEqualTo(2);
        cursor.close();

        // verify that entries in common table is deleted and only messages with id's 2 and 3 remain
        SQLiteDatabase db = mRcsProvider.getWritableDatabase();
        cursor = db.query(RCS_MESSAGE_TABLE, null, null, null, null, null, null);
        assertThat(cursor.getCount()).isEqualTo(2);

        cursor.moveToNext();
        assertThat(cursor.getInt(0)).isEqualTo(2);
        cursor.moveToNext();
        assertThat(cursor.getInt(0)).isEqualTo(3);
        cursor.close();
    }

    @Test
    public void testDeleteFileTransfer() {
        assertThat(mContentResolver.delete(Uri.parse("content://rcs/file_transfer/1"), null,
                null)).isEqualTo(1);
        assertDeletionViaQuery("content://rcs/file_transfer/1");
    }

    @Test
    public void testDeleteParticipantEvent() {
        assertThat(mContentResolver.delete(Uri.parse(
                "content://rcs/participant/1/alias_change_event/1"), null, null)).isEqualTo(1);

        // try deleting again and verify nothing is deleted
        // TODO - convert to query once querying is in place
        assertThat(mContentResolver.delete(Uri.parse(
                "content://rcs/participant/1/alias_change_event/1"), null, null)).isEqualTo(0);
    }

    @Test
    public void testDeleteGroupThreadEvent() {
        assertThat(mContentResolver.delete(Uri.parse(
                "content://rcs/group_thread/2/name_changed_event/1"), null, null)).isEqualTo(1);

        // try deleting again and verify nothing is deleted
        // TODO - convert to query once querying is in place
        assertThat(mContentResolver.delete(Uri.parse(
                "content://rcs/group_thread/2/name_changed_event/1"), null, null)).isEqualTo(0);
    }

    private void assertDeletionViaQuery(String queryUri) {
        Cursor cursor = mContentResolver.query(Uri.parse(queryUri), null, null, null);
        assertThat(cursor.getCount()).isEqualTo(0);
        cursor.close();
    }
}
