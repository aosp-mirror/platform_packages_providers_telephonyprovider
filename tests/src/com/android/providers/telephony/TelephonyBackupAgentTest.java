/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.annotation.TargetApi;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.BaseColumns;
import android.provider.Telephony;
import android.test.AndroidTestCase;
import android.test.mock.MockContentProvider;
import android.test.mock.MockCursor;
import android.util.ArrayMap;
import android.util.JsonReader;
import android.util.JsonWriter;
import android.util.SparseArray;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * Tests for testing backup/restore of SMS and text MMS messages.
 * For backup it creates fake provider and checks resulting json array.
 * For restore provides json array and checks inserts of the messages into provider.
 */
@TargetApi(Build.VERSION_CODES.M)
public class TelephonyBackupAgentTest extends AndroidTestCase {
    private SparseArray<String> mSubId2Phone;
    private ArrayMap<String, Integer> mPhone2SubId;
    private final List<ContentValues> mSmsTable = new ArrayList<>();
    private final List<ContentValues> mMmsTable = new ArrayList<>();
    private final List<ContentValues> mMmsAllContentValues = new ArrayList<>();
    private FakeCursor mSmsCursor, mMmsCursor;
    private ContentValues[] mSmsRows, mMmsRows;
    private ContentValues mMmsNonText;
    private String[] mSmsJson, mMmsJson;
    private String mAllSmsJson, mAllMmsJson;


    private StringWriter mStringWriter;
    private Map<Uri, FakeCursor> mCursors;
    private MockContentProvider mContentProvider;

    private static final String EMPTY_JSON_ARRAY = "[]";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mStringWriter = new StringWriter();
        mSubId2Phone = new SparseArray<String>();
        mSubId2Phone.append(1, "+111111111111111");
        mSubId2Phone.append(3, "+333333333333333");

        mPhone2SubId = new ArrayMap<>();
        for (int i=0; i<mSubId2Phone.size(); ++i) {
            mPhone2SubId.put(mSubId2Phone.valueAt(i), mSubId2Phone.keyAt(i));
        }

        mSmsCursor = new FakeCursor(mSmsTable, TelephonyBackupAgent.SMS_PROJECTION);
        mMmsCursor = new FakeCursor(mMmsTable, TelephonyBackupAgent.MMS_PROJECTION);
        mCursors = new HashMap<Uri, FakeCursor>();

        mSmsRows = new ContentValues[3];
        mSmsJson = new String[3];
        mSmsRows[0] = createSmsRow(1, 1, "+1232132214124", "sms 1", "sms subject", 9087978987l,
                999999999, 3, 44, 1);
        mSmsJson[0] = "{\"self_phone\":\"+111111111111111\",\"address\":" +
                "\"+1232132214124\",\"body\":\"sms 1\",\"subject\":\"sms subject\",\"date\":" +
                "\"9087978987\",\"date_sent\":\"999999999\",\"status\":\"3\",\"type\":\"44\"}";

        mSmsRows[1] = createSmsRow(2, 2, "+1232132214124", "sms 2", null, 9087978987l, 999999999,
                0, 4, 1);
        mSmsJson[1] = "{\"address\":\"+1232132214124\",\"body\":\"sms 2\",\"date\":" +
                "\"9087978987\",\"date_sent\":\"999999999\",\"status\":\"0\",\"type\":\"4\"}";

        mSmsRows[2] = createSmsRow(4, 3, "+1232221412433 +1232221412444", "sms 3", null,
                111111111111l, 999999999, 2, 3, 2);
        mSmsJson[2] =  "{\"self_phone\":\"+333333333333333\",\"address\":" +
                "\"+1232221412433 +1232221412444\",\"body\":\"sms 3\",\"date\":\"111111111111\"," +
                "\"date_sent\":" +
                "\"999999999\",\"status\":\"2\",\"type\":\"3\"}";

        mAllSmsJson = concatJson(mSmsJson);



        mMmsRows = new ContentValues[3];
        mMmsJson = new String[3];
        mMmsRows[0] = createMmsRow(1 /*id*/, 1 /*subid*/, "Subject 1" /*subject*/,
                100 /*subcharset*/, 111111 /*date*/, 111112 /*datesent*/, 3 /*type*/,
                17 /*version*/, 1 /*textonly*/,
                11 /*msgBox*/, "location 1" /*contentLocation*/, "MMs body 1" /*body*/,
                111 /*body charset*/,
                new String[]{"+11121212", "example@example.com", "+999999999"} /*addresses*/,
                1 /*threadId*/);

        mMmsJson[0] = "{\"self_phone\":\"+111111111111111\",\"sub\":\"Subject 1\"," +
                "\"date\":\"111111\",\"date_sent\":\"111112\",\"m_type\":\"3\",\"v\":\"17\"," +
                "\"msg_box\":\"11\",\"ct_l\":\"location 1\",\"mms_addresses\":[{\"type\":10," +
                "\"address\":\"+11121212\",\"charset\":100},{\"type\":11,\"address\":" +
                "\"example@example.com\",\"charset\":101},{\"type\":12,\"address\":\"+999999999\"" +
                ",\"charset\":102}],\"mms_body\":\"MMs body 1\",\"mms_charset\":111,\"" +
                "sub_cs\":\"100\"}";

        mMmsRows[1] = createMmsRow(2 /*id*/, 2 /*subid*/, null /*subject*/, 100 /*subcharset*/,
                111122 /*date*/, 1111112 /*datesent*/, 4 /*type*/, 18 /*version*/, 1 /*textonly*/,
                222 /*msgBox*/, "location 2" /*contentLocation*/, "MMs body 2" /*body*/,
                121 /*body charset*/,
                new String[]{"example@example.com", "+999999999"} /*addresses*/, 2 /*threadId*/);

        mMmsJson[1] = "{\"date\":\"111122\",\"date_sent\":\"1111112\",\"m_type\":\"4\"," +
                "\"v\":\"18\",\"msg_box\":\"222\",\"ct_l\":\"location 2\",\"mms_addresses\":" +
                "[{\"type\":10,\"address\":\"example@example.com\",\"charset\":100}," +
                "{\"type\":11,\"address\":\"+999999999\",\"charset\":101}]," +
                "\"mms_body\":\"MMs body 2\",\"mms_charset\":121}";

        mMmsRows[2] = createMmsRow(10 /*id*/, 3 /*subid*/, "Subject 10" /*subject*/,
                10 /*subcharset*/, 111133 /*date*/, 1111132 /*datesent*/, 5 /*type*/,
                19 /*version*/, 1 /*textonly*/,
                333 /*msgBox*/, null /*contentLocation*/, "MMs body 3" /*body*/,
                131 /*body charset*/, new String[]{"+8888888888"} /*addresses*/, 3 /*threadId*/);

        mMmsJson[2] = "{\"self_phone\":\"+333333333333333\",\"sub\":\"Subject 10\"," +
                "\"date\":\"111133\",\"date_sent\":\"1111132\",\"m_type\":\"5\",\"v\":\"19\"," +
                "\"msg_box\":\"333\",\"mms_addresses\":[{\"type\":10,\"address\":\"+8888888888\"," +
                "\"charset\":100}],\"mms_body\":\"MMs body 3\",\"mms_charset\":131," +
                "\"sub_cs\":\"10\"}";
        mAllMmsJson = concatJson(mMmsJson);


        // Should not be backed up. Cause flag text_only is false.
        mMmsNonText = createMmsRow(10 /*id*/, 3 /*subid*/, "Subject 10" /*subject*/,
                10 /*subcharset*/,
                111133 /*date*/, 1111132 /*datesent*/, 5 /*type*/, 19 /*version*/, 0 /*textonly*/,
                333 /*msgBox*/, null /*contentLocation*/, "MMs body 3" /*body*/,
                131 /*body charset*/, new String[]{"+8888888888"} /*addresses*/, 3 /*threadId*/);

        mContentProvider = new MockContentProvider() {
            @Override
            public Cursor query(Uri uri, String[] projection, String selection,
                                String[] selectionArgs, String sortOrder) {
                if (mCursors.containsKey(uri)) {
                    FakeCursor fakeCursor = mCursors.get(uri);
                    if (projection != null) {
                        fakeCursor.setProjection(projection);
                    }
                    return fakeCursor;
                }
                return super.query(uri, projection, selection, selectionArgs, sortOrder);
            }
        };

    }

    private static String concatJson(String[] json) {
        StringBuilder stringBuilder = new StringBuilder("[");
        for (int i=0; i<json.length; ++i) {
            if (i > 0) {
                stringBuilder.append(",");
            }
            stringBuilder.append(json[i]);
        }
        stringBuilder.append("]");
        return stringBuilder.toString();
    }

    private static ContentValues createSmsRow(int id, int subId, String address, String body,
                                              String subj, long date, long dateSent,
                                              int status, int type, long threadId) {
        ContentValues smsRow = new ContentValues();
        smsRow.put(Telephony.Sms._ID, id);
        smsRow.put(Telephony.Sms.SUBSCRIPTION_ID, subId);
        if (address != null) {
            smsRow.put(Telephony.Sms.ADDRESS, address);
        }
        if (body != null) {
            smsRow.put(Telephony.Sms.BODY, body);
        }
        if (subj != null) {
            smsRow.put(Telephony.Sms.SUBJECT, subj);
        }
        smsRow.put(Telephony.Sms.DATE, String.valueOf(date));
        smsRow.put(Telephony.Sms.DATE_SENT, String.valueOf(dateSent));
        smsRow.put(Telephony.Sms.STATUS, String.valueOf(status));
        smsRow.put(Telephony.Sms.TYPE, String.valueOf(type));
        smsRow.put(Telephony.Sms.THREAD_ID, threadId);

        return smsRow;
    }

    private ContentValues createMmsRow(int id, int subId, String subj, int subCharset,
                                       long date, long dateSent, int type, int version,
                                       int textOnly, int msgBox,
                                       String contentLocation, String body,
                                       int bodyCharset, String[] addresses, long threadId) {
        ContentValues mmsRow = new ContentValues();
        mmsRow.put(Telephony.Mms._ID, id);
        mmsRow.put(Telephony.Mms.SUBSCRIPTION_ID, subId);
        if (subj != null) {
            mmsRow.put(Telephony.Mms.SUBJECT, subj);
            mmsRow.put(Telephony.Mms.SUBJECT_CHARSET, String.valueOf(subCharset));
        }
        mmsRow.put(Telephony.Mms.DATE, String.valueOf(date));
        mmsRow.put(Telephony.Mms.DATE_SENT, String.valueOf(dateSent));
        mmsRow.put(Telephony.Mms.MESSAGE_TYPE, String.valueOf(type));
        mmsRow.put(Telephony.Mms.MMS_VERSION, String.valueOf(version));
        mmsRow.put(Telephony.Mms.TEXT_ONLY, textOnly);
        mmsRow.put(Telephony.Mms.MESSAGE_BOX, String.valueOf(msgBox));
        if (contentLocation != null) {
            mmsRow.put(Telephony.Mms.CONTENT_LOCATION, contentLocation);
        }
        mmsRow.put(Telephony.Mms.THREAD_ID, threadId);

        final Uri partUri = Telephony.Mms.CONTENT_URI.buildUpon().appendPath(String.valueOf(id)).
                appendPath("part").build();
        mCursors.put(partUri, createBodyCursor(body, bodyCharset));
        mMmsAllContentValues.add(mmsRow);

        final Uri addrUri = Telephony.Mms.CONTENT_URI.buildUpon().appendPath(String.valueOf(id)).
                appendPath("addr").build();
        mCursors.put(addrUri, createAddrCursor(addresses));

        return mmsRow;
    }

    private static final String APP_SMIL = "application/smil";
    private static final String TEXT_PLAIN = "text/plain";

    // Cursor with parts of Mms.
    private FakeCursor createBodyCursor(String body, int charset) {
        List<ContentValues> table = new ArrayList<>();

        final String srcName = String.format("text.%06d.txt", 0);
        final String smilBody = String.format(TelephonyBackupAgent.sSmilTextPart, srcName);
        final String smil = String.format(TelephonyBackupAgent.sSmilTextOnly, smilBody);

        final ContentValues smilPart = new ContentValues();
        smilPart.put(Telephony.Mms.Part.SEQ, -1);
        smilPart.put(Telephony.Mms.Part.CONTENT_TYPE, APP_SMIL);
        smilPart.put(Telephony.Mms.Part.NAME, "smil.xml");
        smilPart.put(Telephony.Mms.Part.CONTENT_ID, "<smil>");
        smilPart.put(Telephony.Mms.Part.CONTENT_LOCATION, "smil.xml");
        smilPart.put(Telephony.Mms.Part.TEXT, smil);
        table.add(smilPart); // This part should not be backed up.
        mMmsAllContentValues.add(smilPart);

        final ContentValues bodyPart = new ContentValues();
        bodyPart.put(Telephony.Mms.Part.SEQ, 0);
        bodyPart.put(Telephony.Mms.Part.CONTENT_TYPE, TEXT_PLAIN);
        bodyPart.put(Telephony.Mms.Part.NAME, srcName);
        bodyPart.put(Telephony.Mms.Part.CONTENT_ID, "<"+srcName+">");
        bodyPart.put(Telephony.Mms.Part.CONTENT_LOCATION, srcName);
        bodyPart.put(Telephony.Mms.Part.CHARSET, charset);
        bodyPart.put(Telephony.Mms.Part.TEXT, body);
        table.add(bodyPart);
        mMmsAllContentValues.add(bodyPart);

        return new FakeCursor(table, TelephonyBackupAgent.MMS_TEXT_PROJECTION);
    }

    // Cursor with addresses of Mms.
    private FakeCursor createAddrCursor(String[] addresses) {
        List<ContentValues> table = new ArrayList<>();
        for (int i=0; i<addresses.length; ++i) {
            ContentValues addr = new ContentValues();
            addr.put(Telephony.Mms.Addr.TYPE, 10+i);
            addr.put(Telephony.Mms.Addr.ADDRESS, addresses[i]);
            addr.put(Telephony.Mms.Addr.CHARSET, 100 + i);
            mMmsAllContentValues.add(addr);
            table.add(addr);
        }
        return new FakeCursor(table, TelephonyBackupAgent.MMS_ADDR_PROJECTION);
    }

    /**
     * Test with no sms in the provider.
     * @throws Exception
     */
    public void testBackupSms_NoSms() throws Exception {
        TelephonyBackupAgent.putSmsMessagesToJson(mSmsCursor, mSubId2Phone,
                new JsonWriter(mStringWriter), 1);
        assertEquals(EMPTY_JSON_ARRAY, mStringWriter.toString());
    }

    /**
     * Test with 3 sms in the provider with the limit per file 4.
     * @throws Exception
     */
    public void testBackupSms_AllSms() throws Exception {
        mSmsTable.addAll(Arrays.asList(mSmsRows));
        TelephonyBackupAgent.putSmsMessagesToJson(mSmsCursor, mSubId2Phone,
                new JsonWriter(mStringWriter), 4);
        final String expected =
                "[" + mSmsJson[0] + "," + mSmsJson[1] + "," + mSmsJson[2] + "]";
        assertEquals(expected, mStringWriter.toString());
    }

    /**
     * Test with 3 sms in the provider with the limit per file 3.
     * @throws Exception
     */
    public void testBackupSms_AllSmsWithExactFileLimit() throws Exception {
        mSmsTable.addAll(Arrays.asList(mSmsRows));
        TelephonyBackupAgent.putSmsMessagesToJson(mSmsCursor, mSubId2Phone,
                new JsonWriter(mStringWriter), 3);
        final String expected =
                "[" + mSmsJson[0] + "," + mSmsJson[1] + "," + mSmsJson[2] + "]";
        assertEquals(expected, mStringWriter.toString());
    }

    /**
     * Test with 3 sms in the provider with the limit per file 1.
     * @throws Exception
     */
    public void testBackupSms_AllSmsOneMessagePerFile() throws Exception {
        mSmsTable.addAll(Arrays.asList(mSmsRows));
        TelephonyBackupAgent.putSmsMessagesToJson(mSmsCursor, mSubId2Phone,
                new JsonWriter(mStringWriter), 1);
        assertEquals("[" + mSmsJson[0] + "]", mStringWriter.toString());

        mStringWriter = new StringWriter();
        TelephonyBackupAgent.putSmsMessagesToJson(mSmsCursor, mSubId2Phone,
                new JsonWriter(mStringWriter), 1);
        assertEquals("[" + mSmsJson[1] + "]", mStringWriter.toString());

        mStringWriter = new StringWriter();
        TelephonyBackupAgent.putSmsMessagesToJson(mSmsCursor, mSubId2Phone,
                new JsonWriter(mStringWriter), 1);
        assertEquals("[" + mSmsJson[2] + "]", mStringWriter.toString());
    }

    /**
     * Test with no mms in the pvovider.
     * @throws Exception
     */
    public void testBackupMms_NoMms() throws Exception {
        TelephonyBackupAgent.putMmsMessagesToJson(mMmsCursor, mContentProvider, mSubId2Phone,
                new JsonWriter(mStringWriter), 4);
        assertEquals(EMPTY_JSON_ARRAY, mStringWriter.toString());
    }

    /**
     * Test with all mms.
     * @throws Exception
     */
    public void testBackupMms_AllMms() throws Exception {
        mMmsTable.addAll(Arrays.asList(mMmsRows));
        mMmsTable.add(mMmsNonText);
        TelephonyBackupAgent.putMmsMessagesToJson(mMmsCursor, mContentProvider, mSubId2Phone,
                new JsonWriter(mStringWriter), 4);
        final String expected =
                "[" + mMmsJson[0] + "," + mMmsJson[1] + "," + mMmsJson[2] + "]";
        assertEquals(expected, mStringWriter.toString());
    }

    /**
     * Test with 3 mms in the provider with the limit per file 1.
     * @throws Exception
     */
    public void testBackupMms_OneMessagePerFile() throws Exception {
        mMmsTable.addAll(Arrays.asList(mMmsRows));
        TelephonyBackupAgent.putMmsMessagesToJson(mMmsCursor, mContentProvider, mSubId2Phone,
                new JsonWriter(mStringWriter), 1);
        assertEquals("[" + mMmsJson[0] + "]", mStringWriter.toString());

        mStringWriter = new StringWriter();
        TelephonyBackupAgent.putMmsMessagesToJson(mMmsCursor, mContentProvider, mSubId2Phone,
                new JsonWriter(mStringWriter), 1);
        assertEquals("[" + mMmsJson[1] + "]", mStringWriter.toString());

        mStringWriter = new StringWriter();
        TelephonyBackupAgent.putMmsMessagesToJson(mMmsCursor, mContentProvider, mSubId2Phone,
                new JsonWriter(mStringWriter), 2);
        assertEquals("[" + mMmsJson[2] + "]", mStringWriter.toString());
    }

    /**
     * Test with 3 mms in the provider with the limit per file 3.
     * @throws Exception
     */
    public void testBackupMms_WithExactFileLimit() throws Exception {
        mMmsTable.addAll(Arrays.asList(mMmsRows));
        TelephonyBackupAgent.putMmsMessagesToJson(mMmsCursor, mContentProvider, mSubId2Phone,
                new JsonWriter(mStringWriter), 3);
        final String expected =
                "[" + mMmsJson[0] + "," + mMmsJson[1] + "," + mMmsJson[2] + "]";
        assertEquals(expected, mStringWriter.toString());
    }

    /**
     * Test restore sms with the empty json array "[]".
     * @throws Exception
     */
    public void testRestoreSms_NoSms() throws Exception {
        JsonReader jsonReader = new JsonReader(new StringReader(EMPTY_JSON_ARRAY));
        FakeSmsProvider smsProvider = new FakeSmsProvider(null);
        TelephonyBackupAgent.putSmsMessagesToProvider(jsonReader, smsProvider,
                new ThreadProvider(), mPhone2SubId);
        assertEquals(0, smsProvider.getRowsAdded());
    }

    /**
     * Test restore sms with three sms json object in the array.
     * @throws Exception
     */
    public void testRestoreSms_AllSms() throws Exception {
        JsonReader jsonReader = new JsonReader(new StringReader(mAllSmsJson));
        FakeSmsProvider smsProvider = new FakeSmsProvider(mSmsRows);
        TelephonyBackupAgent.putSmsMessagesToProvider(jsonReader, smsProvider,
                new ThreadProvider(), mPhone2SubId);
        assertEquals(mSmsRows.length, smsProvider.getRowsAdded());
    }

    /**
     * Test restore mms with the empty json array "[]".
     * @throws Exception
     */
    public void testRestoreMms_NoMms() throws Exception {
        JsonReader jsonReader = new JsonReader(new StringReader(EMPTY_JSON_ARRAY));
        FakeMmsProvider mmsProvider = new FakeMmsProvider(null);
        TelephonyBackupAgent.putMmsMessagesToProvider(jsonReader, mmsProvider,
                new ThreadProvider(), mPhone2SubId);
        assertEquals(0, mmsProvider.getRowsAdded());
    }

    /**
     * Test restore sms with three mms json object in the array.
     * @throws Exception
     */
    public void testRestoreMms_AllMms() throws Exception {
        JsonReader jsonReader = new JsonReader(new StringReader(mAllMmsJson));
        FakeMmsProvider mmsProvider = new FakeMmsProvider(mMmsAllContentValues);
        TelephonyBackupAgent.putMmsMessagesToProvider(jsonReader, mmsProvider,
                new ThreadProvider(), mPhone2SubId);
        assertEquals(15, mmsProvider.getRowsAdded());
    }

    /**
     * class for checking sms insertion into the provider on restore.
     */
    private class FakeSmsProvider extends MockContentProvider {
        private int nextRow = 0;
        private ContentValues[] mSms;

        public FakeSmsProvider(ContentValues[] sms) {
            this.mSms = sms;
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            assertEquals(Telephony.Sms.CONTENT_URI, uri);
            ContentValues modifiedValues = new ContentValues(mSms[nextRow++]);
            modifiedValues.remove(Telephony.Sms._ID);
            modifiedValues.put(Telephony.Sms.READ, 1);
            modifiedValues.put(Telephony.Sms.SEEN, 1);
            if (mSubId2Phone.get(modifiedValues.getAsInteger(Telephony.Sms.SUBSCRIPTION_ID))
                    == null) {
                modifiedValues.put(Telephony.Sms.SUBSCRIPTION_ID, -1);
            }

            assertEquals(modifiedValues, values);
            return null;
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                            String sortOrder) {
            return null;
        }

        public int getRowsAdded() {
            return nextRow;
        }
    }

    /**
     * class for checking mms insertion into the provider on restore.
     */
    private class FakeMmsProvider extends MockContentProvider {
        private int nextRow = 0;
        private List<ContentValues> mValues;
        private long mDummyMsgId = -1;
        private long mMsgId = -1;

        public FakeMmsProvider(List<ContentValues> values) {
            this.mValues = values;
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            Uri retUri = Uri.parse("dummy_uri");
            ContentValues modifiedValues = new ContentValues(mValues.get(nextRow++));
            if (APP_SMIL.equals(values.get(Telephony.Mms.Part.CONTENT_TYPE))) {
                // Smil part.
                assertEquals(-1, mDummyMsgId);
                mDummyMsgId = values.getAsLong(Telephony.Mms.Part.MSG_ID);
            }

            if (values.get(Telephony.Mms.Part.SEQ) != null) {
                // Part of mms.
                final Uri expectedUri = Telephony.Mms.CONTENT_URI.buildUpon()
                        .appendPath(String.valueOf(mDummyMsgId))
                        .appendPath("part")
                        .build();
                assertEquals(expectedUri, uri);
            }

            if (values.get(Telephony.Mms.Part.MSG_ID) != null) {
                modifiedValues.put(Telephony.Mms.Part.MSG_ID, mDummyMsgId);
            }


            if (values.get(Telephony.Mms.SUBSCRIPTION_ID) != null) {
                assertEquals(Telephony.Mms.CONTENT_URI, uri);
                if (mSubId2Phone.get(modifiedValues.getAsInteger(Telephony.Sms.SUBSCRIPTION_ID))
                        == null) {
                    modifiedValues.put(Telephony.Sms.SUBSCRIPTION_ID, -1);
                }
                // Mms.
                modifiedValues.put(Telephony.Mms.READ, 1);
                modifiedValues.put(Telephony.Mms.SEEN, 1);
                mMsgId = modifiedValues.getAsInteger(BaseColumns._ID);
                retUri = Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, String.valueOf(mMsgId));
                modifiedValues.remove(BaseColumns._ID);
            }

            if (values.get(Telephony.Mms.Addr.ADDRESS) != null) {
                // Address.
                final Uri expectedUri = Telephony.Mms.CONTENT_URI.buildUpon()
                        .appendPath(String.valueOf(mMsgId))
                        .appendPath("addr")
                        .build();
                assertEquals(expectedUri, uri);
                assertNotSame(-1, mMsgId);
                modifiedValues.put(Telephony.Mms.Addr.MSG_ID, mMsgId);
                mDummyMsgId = -1;
            }

            for (String key : modifiedValues.keySet()) {
                assertEquals(modifiedValues.get(key), values.get(key));
            }
            assertEquals(modifiedValues.size(), values.size());
            return retUri;
        }

        @Override
        public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
            final Uri expectedUri = Telephony.Mms.CONTENT_URI.buildUpon()
                    .appendPath(String.valueOf(mDummyMsgId))
                    .appendPath("part")
                    .build();
            assertEquals(expectedUri, uri);
            ContentValues expected = new ContentValues();
            expected.put(Telephony.Mms.Part.MSG_ID, mMsgId);
            assertEquals(expected, values);
            return 2;
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                            String sortOrder) {
            return null;
        }

        public int getRowsAdded() {
            return nextRow;
        }
    }

    /**
     * class that implements MmsSms provider for thread ids.
     */
    private static class ThreadProvider extends MockContentProvider {

        Map<List<String>, Integer> threadIds;

        public ThreadProvider() {
            threadIds = new ArrayMap<>();
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                            String sortOrder) {
            List<String> recipients = uri.getQueryParameters("recipient");
            int threadId = threadIds.size() + 1;
            if (threadIds.containsKey(recipients)) {
                threadId = threadIds.get(recipients);
            } else {
                threadIds.put(recipients, threadId);
            }

            List<ContentValues> table = new ArrayList<>();
            ContentValues row = new ContentValues();
            row.put(BaseColumns._ID, String.valueOf(threadId));
            table.add(row);
            return new FakeCursor(table, projection);
        }
    }

    /**
     * general cursor for serving queries.
     */
    private static class FakeCursor extends MockCursor {
        String[] projection;
        List<ContentValues> rows;
        int nextRow = -1;

        public FakeCursor(List<ContentValues> rows, String[] projection) {
            this.projection = projection;
            this.rows = rows;
        }

        public void setProjection(String[] projection) {
            this.projection = projection;
        }

        @Override
        public int getColumnCount() {
            return projection.length;
        }

        @Override
        public String getColumnName(int columnIndex) {
            return projection[columnIndex];
        }

        @Override
        public String getString(int columnIndex) {
            return rows.get(nextRow).getAsString(projection[columnIndex]);
        }

        @Override
        public int getInt(int columnIndex) {
            return rows.get(nextRow).getAsInteger(projection[columnIndex]);
        }

        @Override
        public long getLong(int columnIndex) {
            return rows.get(nextRow).getAsLong(projection[columnIndex]);
        }

        @Override
        public boolean isAfterLast() {
            return nextRow >= getCount();
        }

        @Override
        public boolean isLast() {
            return nextRow == getCount() - 1;
        }

        @Override
        public boolean moveToFirst() {
            nextRow = 0;
            return getCount() > 0;
        }

        @Override
        public boolean moveToNext() {
            return getCount() > ++nextRow;
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public int getColumnIndex(String columnName) {
            for (int i=0; i<projection.length; ++i) {
                if (columnName.equals(projection[i])) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public void close() {
        }
    }
}
