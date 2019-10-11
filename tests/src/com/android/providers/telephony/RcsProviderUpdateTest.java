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

import static android.provider.Telephony.RcsColumns.Rcs1To1ThreadColumns.FALLBACK_THREAD_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.HEIGHT_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsFileTransferColumns.WIDTH_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsGroupThreadColumns.GROUP_NAME_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsGroupThreadColumns.OWNER_PARTICIPANT_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsIncomingMessageColumns.ARRIVAL_TIMESTAMP_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsMessageColumns.ORIGINATION_TIMESTAMP_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsMessageDeliveryColumns.DELIVERED_TIMESTAMP_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsParticipantColumns.CANONICAL_ADDRESS_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsParticipantColumns.RCS_ALIAS_COLUMN;

import static com.android.providers.telephony.RcsProviderHelper.setup1To1Thread;
import static com.google.common.truth.Truth.assertThat;

import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony.RcsColumns.RcsMessageDeliveryColumns;
import android.test.mock.MockContentResolver;

import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public class RcsProviderUpdateTest {
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

        // insert fallback threads
        mRcsProvider.getWritableDatabase().execSQL("INSERT INTO threads(_id) VALUES (1)");
        mRcsProvider.getWritableDatabase().execSQL("INSERT INTO threads(_id) VALUES (2)");

        setup1To1Thread(mContentResolver);

        // insert one group thread
        ContentValues groupContentValues = new ContentValues();
        groupContentValues.put(OWNER_PARTICIPANT_COLUMN, 1);
        groupContentValues.put(GROUP_NAME_COLUMN, "Name");
        Uri groupThreadUri = Uri.parse("content://rcs/group_thread");
        assertThat(mContentResolver.insert(groupThreadUri, groupContentValues)).isEqualTo(
                Uri.parse("content://rcs/group_thread/2"));

        Uri groupInsertionUri = Uri.parse("content://rcs/group_thread/2/participant/1");
        assertThat(mContentResolver.insert(groupInsertionUri, null)).isEqualTo(groupInsertionUri);

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

        // add message delivery to the outgoing messages
        ContentValues deliveryValues = new ContentValues();
        assertThat(mContentResolver.insert(Uri.parse("content://rcs/outgoing_message/2/delivery/1"),
                deliveryValues)).isEqualTo(
                Uri.parse("content://rcs/outgoing_message/2/delivery/1"));
        assertThat(mContentResolver.insert(Uri.parse("content://rcs/outgoing_message/4/delivery/1"),
                deliveryValues)).isEqualTo(
                Uri.parse("content://rcs/outgoing_message/4/delivery/1"));

        // add a file transfer to an incoming message
        ContentValues fileTransferValues = new ContentValues();
        assertThat(mContentResolver.insert(Uri.parse("content://rcs/message/3/file_transfer"),
                fileTransferValues)).isEqualTo(Uri.parse("content://rcs/file_transfer/1"));
    }

    @After
    public void tearDown() {
        mRcsProvider.tearDown();
    }

    @Test
    public void testUpdate1To1ThreadWithSelection() {
        // update the fallback thread id
        ContentValues contentValues = new ContentValues(1);
        contentValues.put(FALLBACK_THREAD_ID_COLUMN, 2);
        Uri p2pThreadUri = Uri.parse("content://rcs/p2p_thread");

        assertThat(mContentResolver.update(p2pThreadUri, contentValues, "rcs_fallback_thread_id=1",
                null)).isEqualTo(1);

        // verify the thread is actually updated
        Cursor cursor = mContentResolver.query(p2pThreadUri,
                new String[]{FALLBACK_THREAD_ID_COLUMN}, "rcs_fallback_thread_id=2", null, null);
        assertThat(cursor.getCount()).isEqualTo(1);

        cursor.moveToNext();
        assertThat(cursor.getInt(0)).isEqualTo(2);
    }

    @Test
    public void testUpdate1To1ThreadWithId() {
        // update the fallback thread id
        ContentValues contentValues = new ContentValues(1);
        contentValues.put(FALLBACK_THREAD_ID_COLUMN, 2);
        Uri p2pThreadUri = Uri.parse("content://rcs/p2p_thread/1");
        assertThat(mContentResolver.update(p2pThreadUri, contentValues, null, null)).isEqualTo(1);

        // verify the thread is actually updated
        Cursor cursor = mContentResolver.query(p2pThreadUri,
                new String[]{FALLBACK_THREAD_ID_COLUMN}, null, null, null);
        assertThat(cursor.getCount()).isEqualTo(1);

        cursor.moveToNext();
        assertThat(cursor.getInt(0)).isEqualTo(2);
    }

    @Test
    public void testUpdateGroupThreadWithSelection() {
        // update the group name
        ContentValues contentValues = new ContentValues(1);
        contentValues.put(GROUP_NAME_COLUMN, "New name");
        Uri groupThreadUri = Uri.parse("content://rcs/group_thread");
        assertThat(mContentResolver.update(groupThreadUri, contentValues, "group_name=\"Name\"",
                null)).isEqualTo(1);

        // verify the thread is actually updated
        Cursor cursor = mContentResolver.query(groupThreadUri, new String[]{GROUP_NAME_COLUMN},
                "group_name=\"New name\"", null, null);
        assertThat(cursor.getCount()).isEqualTo(1);

        cursor.moveToNext();
        assertThat(cursor.getString(0)).isEqualTo("New name");
    }

    @Test
    public void testUpdateGroupThreadWithId() {
        // update the group name
        ContentValues contentValues = new ContentValues(1);
        contentValues.put(GROUP_NAME_COLUMN, "New name");
        Uri groupThreadUri = Uri.parse("content://rcs/group_thread/2");
        assertThat(mContentResolver.update(groupThreadUri, contentValues, null, null)).isEqualTo(1);

        // verify the thread is actually updated
        Cursor cursor = mContentResolver.query(groupThreadUri, new String[]{GROUP_NAME_COLUMN},
                null, null, null);
        assertThat(cursor.getCount()).isEqualTo(1);
        cursor.moveToNext();
        assertThat(cursor.getString(0)).isEqualTo("New name");
    }

    @Test
    public void testUpdateParticipantWithId() {
        // change the participant name from Bob to Bobby
        ContentValues contentValues = new ContentValues(1);
        contentValues.put(RCS_ALIAS_COLUMN, "Bobby");

        Uri participantUri = Uri.parse("content://rcs/participant/1");

        assertThat(mContentResolver.update(participantUri, contentValues, null, null)).isEqualTo(1);

        // verify participant is actually updated
        Cursor cursor = mContentResolver.query(participantUri, new String[]{RCS_ALIAS_COLUMN}, null,
                null, null);
        assertThat(cursor.getCount()).isEqualTo(1);
        cursor.moveToNext();
        assertThat(cursor.getString(0)).isEqualTo("Bobby");
    }

    @Test
    public void testUpdate1To1ThreadParticipantFails() {
        assertThat(
                mContentResolver.update(Uri.parse("content://rcs/p2p_thread/1/participant/1"), null,
                        null, null)).isEqualTo(0);
    }

    @Test
    public void testUpdateGroupParticipantFails() {
        assertThat(mContentResolver.update(Uri.parse("content://rcs/group_thread/2/participant/1"),
                null, null, null)).isEqualTo(0);
    }

    @Test
    public void testUpdateUnifiedMessageViewFails() {
        ContentValues updateValues = new ContentValues();
        updateValues.put(ORIGINATION_TIMESTAMP_COLUMN, 1234567890);

        assertThat(mContentResolver.update(Uri.parse("content://rcs/message"), updateValues, null,
                null)).isEqualTo(0);
        assertThat(mContentResolver.update(Uri.parse("content://rcs/message/1"), updateValues, null,
                null)).isEqualTo(0);
    }

    @Test
    public void testUpdateMessageOnThreadFails() {
        ContentValues updateValues = new ContentValues();
        updateValues.put(ORIGINATION_TIMESTAMP_COLUMN, 1234567890);

        assertThat(mContentResolver.update(Uri.parse("content://rcs/p2p_thread/1/incoming_message"),
                updateValues, null, null)).isEqualTo(0);
        assertThat(
                mContentResolver.update(Uri.parse("content://rcs/p2p_thread/1/incoming_message/1"),
                        updateValues, null, null)).isEqualTo(0);
        assertThat(
                mContentResolver.update(Uri.parse("content://rcs/group_thread/2/outgoing_message"),
                        updateValues, null, null)).isEqualTo(0);
        assertThat(mContentResolver.update(
                Uri.parse("content://rcs/groupp_thread/2/outgoing_message/1"), updateValues, null,
                null)).isEqualTo(0);
    }

    @Test
    public void testUpdateMessage() {
        // update the message
        ContentValues updateValues = new ContentValues(1);
        updateValues.put(ORIGINATION_TIMESTAMP_COLUMN, 1234567890);
        assertThat(
                mContentResolver.update(Uri.parse("content://rcs/outgoing_message/2"), updateValues,
                        null, null)).isEqualTo(1);

        // verify the value is actually updated
        Cursor cursor = mContentResolver.query(Uri.parse("content://rcs/outgoing_message/2"), null,
                null, null, null, null);
        assertThat(cursor.getCount()).isEqualTo(1);
        cursor.moveToNext();
        assertThat(cursor.getLong(5)).isEqualTo(1234567890);
        cursor.close();
    }

    @Test
    @Ignore // TODO: fix and un-ignore
    public void testUpdateIncomingMessageSpecificColumn() {
        // update the message
        ContentValues updateValues = new ContentValues(1);
        updateValues.put(ARRIVAL_TIMESTAMP_COLUMN, 987654321);
        assertThat(
                mContentResolver.update(Uri.parse("content://rcs/incoming_message/3"), updateValues,
                        null, null)).isEqualTo(1);

        // verify the value is actually updated
        Cursor cursor = mContentResolver.query(Uri.parse("content://rcs/incoming_message/3"), null,
                null, null, null, null);
        assertThat(cursor.getCount()).isEqualTo(1);
        cursor.moveToNext();
        assertThat(cursor.getLong(7)).isEqualTo(987654321);
        cursor.close();
    }

    @Test
    public void testUpdateMessageDelivery() {
        ContentValues updateValues = new ContentValues();
        updateValues.put(DELIVERED_TIMESTAMP_COLUMN, 12345);
        updateValues.put(RcsMessageDeliveryColumns.SEEN_TIMESTAMP_COLUMN, 54321);

        assertThat(mContentResolver.update(Uri.parse("content://rcs/outgoing_message/2/delivery/1"),
                updateValues, null, null)).isEqualTo(1);

        // verify the value is actually updated
        Cursor cursor = mContentResolver.query(
                Uri.parse("content://rcs/outgoing_message/2/delivery"), null, null, null, null,
                null);
        assertThat(cursor.getCount()).isEqualTo(1);
        cursor.moveToNext();
        assertThat(cursor.getInt(0)).isEqualTo(2);
        assertThat(cursor.getInt(1)).isEqualTo(1);
        assertThat(cursor.getLong(2)).isEqualTo(12345);
        assertThat(cursor.getLong(3)).isEqualTo(54321);
    }

    @Test
    public void testUpdateFileTransfer() {
        ContentValues updateValues = new ContentValues();
        updateValues.put(WIDTH_COLUMN, 640);
        updateValues.put(HEIGHT_COLUMN, 480);

        assertThat(mContentResolver.update(Uri.parse("content://rcs/file_transfer/1"), updateValues,
                null, null)).isEqualTo(1);

        // verify that the values are actually updated
        Cursor cursor = mContentResolver.query(Uri.parse("content://rcs/file_transfer/1"), null,
                null, null, null, null);
        assertThat(cursor.getCount()).isEqualTo(1);
        cursor.moveToNext();
        assertThat(cursor.getInt(8)).isEqualTo(640);
        assertThat(cursor.getInt(9)).isEqualTo(480);
    }
}
