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

import static android.provider.Telephony.RcsColumns.RcsGroupThreadColumns.CONFERENCE_URI_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsGroupThreadColumns.GROUP_ICON_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsGroupThreadColumns.GROUP_NAME_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsMessageColumns.GLOBAL_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsParticipantColumns.CANONICAL_ADDRESS_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsParticipantColumns.RCS_ALIAS_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsParticipantEventColumns.NEW_ALIAS_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsThreadEventColumns.NEW_NAME_COLUMN;

import static com.android.providers.telephony.RcsProviderHelper.setup1To1Thread;
import static com.google.common.truth.Truth.assertThat;

import android.content.ContentValues;
import android.database.Cursor;
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
public class RcsProviderInsertTest {
    private MockContentResolver mContentResolver;
    private RcsProviderTestable mRcsProvider;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mRcsProvider = new RcsProviderTestable();
        RcsProviderTestable.MockContextWithProvider
                context = new RcsProviderTestable.MockContextWithProvider(mRcsProvider);
        mContentResolver = context.getContentResolver();
    }

    @After
    public void tearDown() {
        mRcsProvider.tearDown();
    }

    @Test
    public void testInsertUnifiedThreadFails() {
        assertThat(mContentResolver.insert(Uri.parse("content://rcs/thread"), null)).isNull();
    }

    @Test
    public void testDuplicate1To1ThreadInsertion() {
        Uri uri = setup1To1Thread(mContentResolver);

        assertThat(mContentResolver.insert(uri, null)).isNull();
    }

    @Test
    public void testInsertGroupThread() {
        ContentValues contentValues = new ContentValues(3);
        contentValues.put(CONFERENCE_URI_COLUMN, "conference uri");
        contentValues.put(GROUP_NAME_COLUMN, "group name");
        contentValues.put(GROUP_ICON_COLUMN, "groupIcon");
        assertThat(mContentResolver.insert(Uri.parse("content://rcs/group_thread"),
                contentValues)).isEqualTo(Uri.parse("content://rcs/group_thread/1"));
    }

    @Test
    public void testInsertParticipant() {
        ContentValues contentValues = new ContentValues();
        contentValues.put(CANONICAL_ADDRESS_ID_COLUMN, 6);
        contentValues.put(RCS_ALIAS_COLUMN, "Alias");

        Uri uri = mContentResolver.insert(Uri.parse("content://rcs/participant"), contentValues);
        assertThat(uri).isEqualTo(Uri.parse("content://rcs/participant/1"));
    }

    @Test
    public void testInsertParticipantIntoGroupThread() {
        // create a participant
        ContentValues contentValues = new ContentValues();
        contentValues.put(CANONICAL_ADDRESS_ID_COLUMN, 23);
        mContentResolver.insert(Uri.parse("content://rcs/participant"), contentValues);

        // create a thread
        ContentValues values = new ContentValues(1);
        values.put(GROUP_NAME_COLUMN, "Group");
        mContentResolver.insert(Uri.parse("content://rcs/group_thread"), values);

        // add participant to the thread
        Uri uri = Uri.parse("content://rcs/group_thread/1/participant/1");
        assertThat(mContentResolver.insert(uri, null)).isEqualTo(uri);

        // assert that adding again fails
        assertThat(mContentResolver.insert(uri, null)).isNull();
    }

    @Test
    public void testInsertMessageFails() {
        ContentValues contentValues = new ContentValues();
        contentValues.put(GLOBAL_ID_COLUMN, "global RCS id");

        // try inserting messages without threads
        assertThat(mContentResolver.insert(Uri.parse("content://rcs/message"),
                contentValues)).isNull();
        assertThat(mContentResolver.insert(Uri.parse("content://rcs/message/6"),
                contentValues)).isNull();
        assertThat(mContentResolver.insert(Uri.parse("content://rcs/incoming_message"),
                contentValues)).isNull();
        assertThat(mContentResolver.insert(Uri.parse("content://rcs/incoming_message/12"),
                contentValues)).isNull();
        assertThat(mContentResolver.insert(Uri.parse("content://rcs/outgoing_message"),
                contentValues)).isNull();
        assertThat(mContentResolver.insert(Uri.parse("content://rcs/outgoing_message/18"),
                contentValues)).isNull();

        // try inserting into unified thread view
        assertThat(mContentResolver.insert(Uri.parse("content://rcs/thread/5/incoming_message"),
                contentValues)).isNull();
        assertThat(mContentResolver.insert(Uri.parse("content://rcs/thread/5/outgoing_message"),
                contentValues)).isNull();
    }

    @Test
    @Ignore // TODO: fix and un-ignore
    public void testInsertMessageIntoThread() {
        // create two threads
        setup1To1Thread(mContentResolver);
        ContentValues values = new ContentValues();
        assertThat(mContentResolver.insert(Uri.parse("content://rcs/group_thread"),
                values)).isNotNull();

        // add messages to threads
        assertThat(mContentResolver.insert(Uri.parse("content://rcs/p2p_thread/1/incoming_message"),
                values)).isEqualTo(Uri.parse("content://rcs/p2p_thread/1/incoming_message/1"));
        assertThat(mContentResolver.insert(Uri.parse("content://rcs/p2p_thread/1/outgoing_message"),
                values)).isEqualTo(Uri.parse("content://rcs/p2p_thread/1/outgoing_message/2"));
        assertThat(
                mContentResolver.insert(Uri.parse("content://rcs/group_thread/2/incoming_message"),
                        values)).isEqualTo(
                Uri.parse("content://rcs/group_thread/2/incoming_message/3"));
        assertThat(
                mContentResolver.insert(Uri.parse("content://rcs/group_thread/2/outgoing_message"),
                        values)).isEqualTo(
                Uri.parse("content://rcs/group_thread/2/outgoing_message/4"));

        // assert that they are accessible in messages table
        Cursor messageCursor = mContentResolver.query(Uri.parse("content://rcs/message"), null,
                null, null, null);
        assertThat(messageCursor.getCount()).isEqualTo(4);
    }

    @Test
    public void testInsertMessageDelivery() {
        setup1To1Thread(mContentResolver);

        ContentValues values = new ContentValues();

        // add an outgoing message to the thread
        assertThat(mContentResolver.insert(Uri.parse("content://rcs/p2p_thread/1/outgoing_message"),
                values)).isEqualTo(Uri.parse("content://rcs/p2p_thread/1/outgoing_message/1"));

        // add a delivery to the outgoing message
        assertThat(mContentResolver.insert(Uri.parse("content://rcs/outgoing_message/1/delivery/1"),
                values)).isEqualTo(Uri.parse("content://rcs/outgoing_message/1/delivery/1"));
    }

    @Test
    public void testInsertFileTransfer() {
        setup1To1Thread(mContentResolver);

        ContentValues values = new ContentValues();

        // add an outgoing message to the thread
        assertThat(mContentResolver.insert(Uri.parse("content://rcs/p2p_thread/1/outgoing_message"),
                values)).isEqualTo(Uri.parse("content://rcs/p2p_thread/1/outgoing_message/1"));

        // add a file transfer to the message
        assertThat(mContentResolver.insert(Uri.parse("content://rcs/message/1/file_transfer"),
                values)).isEqualTo(Uri.parse("content://rcs/file_transfer/1"));
    }

    @Test
    public void testInsertParticipantEvent() {
        // create a participant
        ContentValues contentValues = new ContentValues();
        contentValues.put(CANONICAL_ADDRESS_ID_COLUMN, 23);
        mContentResolver.insert(Uri.parse("content://rcs/participant"), contentValues);

        // insert an alias change event
        ContentValues eventValues = new ContentValues();
        eventValues.put(NEW_ALIAS_COLUMN, "new alias");
        assertThat(
                mContentResolver.insert(Uri.parse("content://rcs/participant/1/alias_change_event"),
                        eventValues)).isEqualTo(Uri.parse(
                "content://rcs/participant/1/alias_change_event/1"));
    }

    @Test
    public void testInsertGroupThreadEvent() {
        // create a group thread
        ContentValues contentValues = new ContentValues();
        assertThat(mContentResolver.insert(Uri.parse("content://rcs/group_thread"),
                contentValues)).isEqualTo(Uri.parse("content://rcs/group_thread/1"));

        // create a group name change event
        ContentValues eventValues = new ContentValues();
        eventValues.put(NEW_NAME_COLUMN, "new name");
        assertThat(mContentResolver.insert(
                Uri.parse("content://rcs/group_thread/1/name_changed_event"),
                eventValues)).isEqualTo(Uri.parse(
                "content://rcs/group_thread/1/name_changed_event/1"));
    }
}
