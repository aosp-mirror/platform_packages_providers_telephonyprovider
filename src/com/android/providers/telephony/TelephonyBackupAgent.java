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

import com.google.android.mms.ContentType;
import com.google.android.mms.pdu.CharacterSets;

import com.android.internal.annotations.VisibleForTesting;

import android.annotation.TargetApi;
import android.app.backup.BackupAgent;
import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.app.backup.FullBackupDataOutput;
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.BaseColumns;
import android.provider.Telephony;
import android.telephony.PhoneNumberUtils;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.JsonReader;
import android.util.JsonWriter;
import android.util.Log;
import android.util.SparseArray;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/***
 * Backup agent for backup and restore SMS's and text MMS's.
 *
 * This backup agent stores SMS's into "sms_backup" file as a JSON array. Example below.
 *  [{"self_phone":"+1234567891011","address":"+1234567891012","body":"Example sms",
 *  "date":"1450893518140","date_sent":"1450893514000","status":"-1","type":"1"},
 *  {"self_phone":"+1234567891011","address":"12345","body":"Example 2","date":"1451328022316",
 *  "date_sent":"1451328018000","status":"-1","type":"1"}]
 *
 * Text MMS's are stored into "mms_backup" file as a JSON array. Example below.
 *  [{"self_phone":"+1234567891011","date":"1451322716","date_sent":"0","m_type":"128","v":"18",
 *  "msg_box":"2","mms_addresses":[{"type":137,"address":"+1234567891011","charset":106},
 *  {"type":151,"address":"example@example.com","charset":106}],"mms_body":"Mms to email",
 *  "mms_charset":106},
 *  {"self_phone":"+1234567891011","sub":"MMS subject","date":"1451322955","date_sent":"0",
 *  "m_type":"132","v":"17","msg_box":"1","ct_l":"http://promms/servlets/NOK5BBqgUHAqugrQNM",
 *  "mms_addresses":[{"type":151,"address":"+1234567891011","charset":106}],
 *  "mms_body":"Mms\nBody\r\n",
 *  "mms_charset":106,"sub_cs":"106"}]
 *
 *   It deflates the files on the flight.
 *   Every 1000 messages it backs up file, deletes it and creates a new one with the same name.
 *
 *   It stores how many bytes we are over the quota and don't backup the oldest messages.
 */

@TargetApi(Build.VERSION_CODES.M)
public class TelephonyBackupAgent extends BackupAgent {
    private static final String TAG = "TelephonyBackupAgent";
    private static final boolean DEBUG = false;


    // Copied from packages/apps/Messaging/src/com/android/messaging/sms/MmsUtils.java.
    private static final int DEFAULT_DURATION = 5000; //ms

    // Copied from packages/apps/Messaging/src/com/android/messaging/sms/MmsUtils.java.
    @VisibleForTesting
    static final String sSmilTextOnly =
            "<smil>" +
                "<head>" +
                    "<layout>" +
                        "<root-layout/>" +
                        "<region id=\"Text\" top=\"0\" left=\"0\" "
                        + "height=\"100%%\" width=\"100%%\"/>" +
                    "</layout>" +
                "</head>" +
                "<body>" +
                       "%s" +  // constructed body goes here
                "</body>" +
            "</smil>";

    // Copied from packages/apps/Messaging/src/com/android/messaging/sms/MmsUtils.java.
    @VisibleForTesting
    static final String sSmilTextPart =
            "<par dur=\"" + DEFAULT_DURATION + "ms\">" +
                "<text src=\"%s\" region=\"Text\" />" +
            "</par>";


    // JSON key for phone number a message was sent from or received to.
    private static final String SELF_PHONE_KEY = "self_phone";
    // JSON key for list of addresses of MMS message.
    private static final String MMS_ADDRESSES_KEY = "mms_addresses";
    // JSON key for list of recipients of sms message.
    private static final String SMS_RECIPIENTS = "sms_recipients";
    // JSON key for MMS body.
    private static final String MMS_BODY_KEY = "mms_body";
    // JSON key for MMS charset.
    private static final String MMS_BODY_CHARSET_KEY = "mms_charset";

    // File names for backup/restore.
    private static final String SMS_BACKUP_FILE = "sms_backup";
    private static final String MMS_BACKUP_FILE = "mms_backup";

    // Charset being used for reading/writing backup files.
    private static final String CHARSET_UTF8 = "UTF-8";

    // Order by ID entries from database.
    private static final String ORDER_BY_ID = BaseColumns._ID + " ASC";

    // Order by Date entries from database. We order it the oldest first in order to throw them if
    // we are over quota.
    private static final String ORDER_BY_DATE = "date ASC";

    // Columns from SMS database for backup/restore.
    @VisibleForTesting
    static final String[] SMS_PROJECTION = new String[] {
            Telephony.Sms._ID,
            Telephony.Sms.SUBSCRIPTION_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.SUBJECT,
            Telephony.Sms.DATE,
            Telephony.Sms.DATE_SENT,
            Telephony.Sms.STATUS,
            Telephony.Sms.TYPE,
            Telephony.Sms.THREAD_ID
    };

    // Columns to fetch recepients of SMS.
    private static final String[] SMS_RECIPIENTS_PROJECTION = {
            Telephony.Threads._ID,
            Telephony.Threads.RECIPIENT_IDS
    };

    // Columns from MMS database for backup/restore.
    @VisibleForTesting
    static final String[] MMS_PROJECTION = new String[] {
            Telephony.Mms._ID,
            Telephony.Mms.SUBSCRIPTION_ID,
            Telephony.Mms.SUBJECT,
            Telephony.Mms.SUBJECT_CHARSET,
            Telephony.Mms.DATE,
            Telephony.Mms.DATE_SENT,
            Telephony.Mms.MESSAGE_TYPE,
            Telephony.Mms.MMS_VERSION,
            Telephony.Mms.TEXT_ONLY,
            Telephony.Mms.MESSAGE_BOX,
            Telephony.Mms.CONTENT_LOCATION
    };

    // Columns from addr database for backup/restore. This database is used for fetching addresses
    // for MMS message.
    @VisibleForTesting
    static final String[] MMS_ADDR_PROJECTION = new String[] {
            Telephony.Mms.Addr.TYPE,
            Telephony.Mms.Addr.ADDRESS,
            Telephony.Mms.Addr.CHARSET
    };

    // Columns from part database for backup/restore. This database is used for fetching body text
    // and charset for MMS message.
    @VisibleForTesting
    static final String[] MMS_TEXT_PROJECTION = new String[] {
            Telephony.Mms.Part.CONTENT_TYPE,
            Telephony.Mms.Part.TEXT,
            Telephony.Mms.Part.CHARSET
    };

    // Maximum messages for one backup file. After reaching the limit the agent backs up the file,
    // deletes it and creates a new one with the same name.
    private static final int MAX_MSG_PER_FILE = 1000;


    // Default values for SMS, MMS, Addresses restore.
    private static final ContentValues defaultValuesSms = new ContentValues(3);
    private static final ContentValues defaultValuesMms = new ContentValues(5);
    private static final ContentValues defaultValuesAddr = new ContentValues(2);

    // Shared preferences for the backup agent.
    private static final String BACKUP_PREFS = "backup_shared_prefs";
    // Key for storing bytes over.
    private static final String BYTES_OVER_QUOTA_PREF_KEY = "bytes_over_quota";


    static {
        // Consider restored messages read and seen.
        defaultValuesSms.put(Telephony.Sms.READ, 1);
        defaultValuesSms.put(Telephony.Sms.SEEN, 1);
        // If there is no sub_id with self phone number on restore set it to -1.
        defaultValuesSms.put(Telephony.Sms.SUBSCRIPTION_ID, -1);

        defaultValuesMms.put(Telephony.Mms.READ, 1);
        defaultValuesMms.put(Telephony.Mms.SEEN, 1);
        defaultValuesMms.put(Telephony.Mms.SUBSCRIPTION_ID, -1);
        defaultValuesMms.put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_ALL);
        defaultValuesMms.put(Telephony.Mms.TEXT_ONLY, 1);

        defaultValuesAddr.put(Telephony.Mms.Addr.TYPE, 0);
        defaultValuesAddr.put(Telephony.Mms.Addr.CHARSET, CharacterSets.DEFAULT_CHARSET);
    }


    private SparseArray<String> subId2phone;
    private Map<String, Integer> phone2subId;
    private ContentProvider mSmsProvider;
    private ContentProvider mMmsProvider;
    private ContentProvider mMmsSmsProvider;

    // How many bytes we have to skip to fit into quota.
    private long mBytesOverQuota;

    @Override
    public void onCreate() {
        super.onCreate();

        subId2phone = new SparseArray<String>();
        phone2subId = new ArrayMap<String, Integer>();
        final SubscriptionManager subscriptionManager = SubscriptionManager.from(this);
        if (subscriptionManager != null) {
            final List<SubscriptionInfo> subInfo =
                    subscriptionManager.getActiveSubscriptionInfoList();
            if (subInfo != null) {
                for (SubscriptionInfo sub : subInfo) {
                    final String phoneNumber = getNormalizedNumber(sub);
                    subId2phone.append(sub.getSubscriptionId(), phoneNumber);
                    phone2subId.put(phoneNumber, sub.getSubscriptionId());
                }
            }
        }

        mSmsProvider = new SmsProvider();
        mMmsProvider = new MmsProvider();
        mMmsSmsProvider = new MmsSmsProvider();

        attachAndCreateProviders();
    }

    private void attachAndCreateProviders() {
        mSmsProvider.attachInfo(this, null);
        mSmsProvider.onCreate();

        mMmsProvider.attachInfo(this, null);
        mMmsProvider.onCreate();

        mMmsSmsProvider.attachInfo(this, null);
        mMmsSmsProvider.onCreate();
    }

    @Override
    public void onFullBackup(FullBackupDataOutput data) throws IOException {
        mBytesOverQuota = getSharedPreferences(BACKUP_PREFS, MODE_PRIVATE).
                getLong(BYTES_OVER_QUOTA_PREF_KEY, 0);

        backupAllSms(data);
        backupAllMms(data);
    }

    @Override
    public void onQuotaExceeded(long backupDataBytes, long quotaBytes) {
        mBytesOverQuota = (long)((backupDataBytes - quotaBytes)*1.1);
        getSharedPreferences(BACKUP_PREFS, MODE_PRIVATE).edit()
                .putLong(BYTES_OVER_QUOTA_PREF_KEY, mBytesOverQuota).apply();
    }

    private void backupAllSms(FullBackupDataOutput data) throws IOException {
        try (Cursor cursor = mSmsProvider.query(Telephony.Sms.CONTENT_URI, SMS_PROJECTION, null,
                null, ORDER_BY_DATE)) {
            if (DEBUG) {
                Log.i(TAG, "Backing up SMS");
            }
            if (cursor != null) {
                while (!cursor.isLast() && !cursor.isAfterLast()) {
                    try (JsonWriter jsonWriter = getJsonWriter(SMS_BACKUP_FILE)) {
                        putSmsMessagesToJson(cursor, subId2phone, jsonWriter, mMmsSmsProvider,
                                MAX_MSG_PER_FILE);
                    }
                    backupFile(SMS_BACKUP_FILE, data);
                }
            }
        }
    }

    private void backupAllMms(FullBackupDataOutput data) throws IOException {
        try (Cursor cursor = mMmsProvider.query(Telephony.Mms.CONTENT_URI, MMS_PROJECTION, null,
                null, ORDER_BY_DATE)) {
            if (DEBUG) {
                Log.i(TAG, "Backing up text MMS");
            }
            if (cursor != null) {
                while (!cursor.isLast() && !cursor.isAfterLast()) {
                    try (JsonWriter jsonWriter = getJsonWriter(MMS_BACKUP_FILE)) {
                        putMmsMessagesToJson(cursor, mMmsProvider, subId2phone, jsonWriter,
                                MAX_MSG_PER_FILE);
                    }
                    backupFile(MMS_BACKUP_FILE, data);
                }
            }
        }
    }

    @VisibleForTesting
    static void putMmsMessagesToJson(Cursor cursor, ContentProvider mmsProvider,
                                     SparseArray<String> subId2phone, JsonWriter jsonWriter,
                                     int maxMsgPerFile) throws IOException {
        jsonWriter.beginArray();
        for (int msgCount=0; msgCount<maxMsgPerFile && cursor.moveToNext();) {
            msgCount += writeMmsToWriter(jsonWriter, cursor, subId2phone, mmsProvider);
        }
        jsonWriter.endArray();
    }

    @VisibleForTesting
    static void putSmsMessagesToJson(Cursor cursor, SparseArray<String> subId2phone,
                                     JsonWriter jsonWriter, ContentProvider threadProvider,
                                     int maxMsgPerFile) throws IOException {

        jsonWriter.beginArray();
        for (int msgCount=0; msgCount<maxMsgPerFile && cursor.moveToNext(); ++msgCount) {
            writeSmsToWriter(jsonWriter, cursor, threadProvider, subId2phone);
        }
        jsonWriter.endArray();
    }

    private void backupFile(String fileName, FullBackupDataOutput data) {
        final File file = new File(getFilesDir().getPath() + "/" + fileName);
        try {
            if (mBytesOverQuota > 0) {
                mBytesOverQuota -= file.length();
                return;
            }
            super.fullBackupFile(file, data);
        } finally {
            file.delete();
        }
    }

    @Override
    public void onRestoreFile(ParcelFileDescriptor data, long size, File destination, int type,
                              long mode, long mtime) throws IOException {
        if (DEBUG) {
            Log.i(TAG, "Restoring file " + destination.getName());
        }

        if (destination.getName().equals(SMS_BACKUP_FILE)) {
            if (DEBUG) {
                Log.i(TAG, "Restoring SMS");
            }
            try (JsonReader jsonReader = getJsonReader(data.getFileDescriptor())) {
                putSmsMessagesToProvider(jsonReader, mSmsProvider, mMmsSmsProvider, phone2subId);
            }
        } else if (destination.getName().equals(MMS_BACKUP_FILE)) {
            if (DEBUG) {
                Log.i(TAG, "Restoring text MMS");
            }
            try (JsonReader jsonReader = getJsonReader(data.getFileDescriptor())) {
                putMmsMessagesToProvider(jsonReader, mMmsProvider, mMmsSmsProvider, phone2subId);
            }
        } else {
            super.onRestoreFile(data, size, destination, type, mode, mtime);
        }
        if (DEBUG) {
            Log.i(TAG, "Finished restore");
        }
    }

    @VisibleForTesting
    static void putSmsMessagesToProvider(JsonReader jsonReader, ContentProvider smsProvider,
                                         ContentProvider threadProvider,
                                         Map<String, Integer> phone2subId) throws IOException {
        jsonReader.beginArray();
        while (jsonReader.hasNext()) {
            ContentValues smsValues =
                    readSmsValuesFromReader(jsonReader, threadProvider, phone2subId);
            if (doesSmsExist(smsProvider, smsValues)) {
                if (DEBUG) {
                    Log.e(TAG, String.format("Sms: %s already exists", smsValues.toString()));
                }
                continue;
            }
            smsProvider.insert(Telephony.Sms.CONTENT_URI, smsValues);
        }
        jsonReader.endArray();
    }

    @VisibleForTesting
    static void putMmsMessagesToProvider(JsonReader jsonReader, ContentProvider mmsProvider,
                                         ContentProvider threadProvider,
                                         Map<String, Integer> phone2subId) throws IOException {
        jsonReader.beginArray();
        while (jsonReader.hasNext()) {
            final Mms mms = readMmsFromReader(jsonReader, threadProvider, phone2subId);
            if (doesMmsExist(mmsProvider, mms)) {
                if (DEBUG) {
                    Log.e(TAG, String.format("Mms: %s already exists", mms.toString()));
                }
                continue;
            }
            addMmsMessage(mmsProvider, mms);
        }
    }

    @VisibleForTesting
    static final String[] PROJECTION_ID = {BaseColumns._ID};

    private static boolean doesSmsExist(ContentProvider smsProvider, ContentValues smsValues) {
        final String where = String.format("%s = %d and %s = %s",
                Telephony.Sms.DATE, smsValues.getAsLong(Telephony.Sms.DATE),
                Telephony.Sms.BODY,
                DatabaseUtils.sqlEscapeString(smsValues.getAsString(Telephony.Sms.BODY)));
        try (Cursor cursor = smsProvider.query(Telephony.Sms.CONTENT_URI, PROJECTION_ID, where,
                null, null)) {
            return cursor != null && cursor.getCount() > 0;
        }
    }

    private static boolean doesMmsExist(ContentProvider mmsProvider, Mms mms) {
        final String where = String.format("%s = %d",
                Telephony.Sms.DATE, mms.values.getAsLong(Telephony.Mms.DATE));
        try (Cursor cursor = mmsProvider.query(Telephony.Mms.CONTENT_URI, PROJECTION_ID, where,
                null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    final int mmsId = cursor.getInt(0);
                    final MmsBody body = getMmsBody(mmsProvider, mmsId);
                    if (body != null && body.equals(mms.body)) {
                        return true;
                    }
                } while (cursor.moveToNext());
            }
        }
        return false;
    }

    private static String getNormalizedNumber(SubscriptionInfo subscriptionInfo) {
        if (subscriptionInfo == null) {
            return null;
        }
        return PhoneNumberUtils.formatNumberToE164(subscriptionInfo.getNumber(),
                subscriptionInfo.getCountryIso().toUpperCase(Locale.US));
    }

    private static void writeSmsToWriter(JsonWriter jsonWriter, Cursor cursor,
                                         ContentProvider threadProvider,
                                         SparseArray<String> subId2phone) throws IOException {
        jsonWriter.beginObject();

        for (int i=0; i<cursor.getColumnCount(); ++i) {
            final String name = cursor.getColumnName(i);
            final String value = cursor.getString(i);
            if (value == null) {
                continue;
            }
            switch (name) {
                case Telephony.Sms.SUBSCRIPTION_ID:
                    final int subId = cursor.getInt(i);
                    final String selfNumber = subId2phone.get(subId);
                    if (selfNumber != null) {
                        jsonWriter.name(SELF_PHONE_KEY).value(selfNumber);
                    }
                    break;
                case Telephony.Sms.THREAD_ID:
                    final long threadId = cursor.getLong(i);
                    writeSmsRecipientsToWriter(jsonWriter.name(SMS_RECIPIENTS),
                            getRecipientsByThread(threadProvider, threadId));
                    break;
                case Telephony.Sms._ID:
                    break;
                default:
                    jsonWriter.name(name).value(value);
                    break;
            }
        }
        jsonWriter.endObject();

    }

    private static void writeSmsRecipientsToWriter(JsonWriter jsonWriter, List<String> recipients)
            throws IOException {
        jsonWriter.beginArray();
        if (recipients != null) {
            for (String s : recipients) {
                jsonWriter.value(s);
            }
        }
        jsonWriter.endArray();
    }

    @VisibleForTesting
    static ContentValues readSmsValuesFromReader(JsonReader jsonReader,
                                                 ContentProvider threadProvider,
                                                 Map<String, Integer> phone2id)
            throws IOException {
        ContentValues values = new ContentValues(8+defaultValuesSms.size());
        values.putAll(defaultValuesSms);
        jsonReader.beginObject();
        while (jsonReader.hasNext()) {
            String name = jsonReader.nextName();
            switch (name) {
                case Telephony.Sms.BODY:
                case Telephony.Sms.DATE:
                case Telephony.Sms.DATE_SENT:
                case Telephony.Sms.STATUS:
                case Telephony.Sms.TYPE:
                case Telephony.Sms.SUBJECT:
                case Telephony.Sms.ADDRESS:
                    values.put(name, jsonReader.nextString());
                    break;
                case SMS_RECIPIENTS:
                    values.put(Telephony.Sms.THREAD_ID,
                            getOrCreateThreadId(threadProvider, getSmsRecipients(jsonReader)));
                    break;
                case SELF_PHONE_KEY:
                    final String selfPhone = jsonReader.nextString();
                    if (phone2id.containsKey(selfPhone)) {
                        values.put(Telephony.Sms.SUBSCRIPTION_ID, phone2id.get(selfPhone));
                    }
                    break;
                default:
                    if (DEBUG) {
                        Log.w(TAG, "Unknown name:" + name);
                    }
                    jsonReader.skipValue();
                    break;
            }
        }
        jsonReader.endObject();
        return values;
    }

    private static Set<String> getSmsRecipients(JsonReader jsonReader) throws IOException {
        Set<String> recipients = new ArraySet<String>();
        jsonReader.beginArray();
        while (jsonReader.hasNext()) {
            recipients.add(jsonReader.nextString());
        }
        jsonReader.endArray();
        return recipients;
    }

    private static int writeMmsToWriter(JsonWriter jsonWriter, Cursor cursor,
                                        SparseArray<String> subId2phone,
                                        ContentProvider mmsProvider) throws IOException {
        // Do not backup non text-only MMS's.
        if (cursor.getInt(cursor.getColumnIndex(Telephony.Mms.TEXT_ONLY)) != 1) {
            return 0;
        }
        final int mmsId = cursor.getInt(0);
        final MmsBody body = getMmsBody(mmsProvider, mmsId);
        if (body == null || body.text == null) {
            return 0;
        }

        boolean subjectNull = true;
        jsonWriter.beginObject();
        for (int i=0; i<cursor.getColumnCount(); ++i) {
            final String name = cursor.getColumnName(i);
            final String value = cursor.getString(i);
            if (value == null) {
                continue;
            }
            switch (name) {
                case Telephony.Sms.SUBSCRIPTION_ID:
                    final int subId = cursor.getInt(i);
                    final String selfNumber = subId2phone.get(subId);
                    if (selfNumber != null) {
                        jsonWriter.name(SELF_PHONE_KEY).value(selfNumber);
                    }
                    break;
                case Telephony.Mms._ID:
                case Telephony.Mms.TEXT_ONLY:
                case Telephony.Mms.SUBJECT_CHARSET:
                    break;
                case Telephony.Mms.SUBJECT:
                    subjectNull = false;
                default:
                    jsonWriter.name(name).value(value);
                    break;
            }
        }
        // Addresses.
        writeMmsAddresses(jsonWriter.name(MMS_ADDRESSES_KEY), mmsProvider, mmsId);
        // Body (text of the message).
        jsonWriter.name(MMS_BODY_KEY).value(body.text);
        // Charset of the body text.
        jsonWriter.name(MMS_BODY_CHARSET_KEY).value(body.charSet);

        if (!subjectNull) {
            // Subject charset.
            writeStringToWriter(jsonWriter, cursor, Telephony.Mms.SUBJECT_CHARSET);
        }
        jsonWriter.endObject();
        return 1;
    }

    private static Mms readMmsFromReader(JsonReader jsonReader, ContentProvider threadProvider,
                                         Map<String, Integer> phone2id) throws IOException {
        Mms mms = new Mms();
        mms.values = new ContentValues(6+defaultValuesMms.size());
        mms.values.putAll(defaultValuesMms);
        jsonReader.beginObject();
        String selfPhone = null;
        String bodyText = null;
        int bodyCharset = CharacterSets.DEFAULT_CHARSET;
        while (jsonReader.hasNext()) {
            String name = jsonReader.nextName();
            switch (name) {
                case SELF_PHONE_KEY:
                    selfPhone = jsonReader.nextString();
                    if (phone2id.containsKey(selfPhone)) {
                        mms.values.put(Telephony.Mms.SUBSCRIPTION_ID, phone2id.get(selfPhone));
                    }
                    break;
                case MMS_ADDRESSES_KEY:
                    getMmsAddressesFromReader(jsonReader, mms);
                    break;
                case MMS_BODY_KEY:
                    bodyText = jsonReader.nextString();
                    break;
                case MMS_BODY_CHARSET_KEY:
                    bodyCharset = jsonReader.nextInt();
                    break;
                case Telephony.Mms.SUBJECT:
                case Telephony.Mms.SUBJECT_CHARSET:
                case Telephony.Mms.DATE:
                case Telephony.Mms.DATE_SENT:
                case Telephony.Mms.MESSAGE_TYPE:
                case Telephony.Mms.MMS_VERSION:
                case Telephony.Mms.MESSAGE_BOX:
                case Telephony.Mms.CONTENT_LOCATION:
                    mms.values.put(name, jsonReader.nextString());
                    break;
                default:
                    if (DEBUG) {
                        Log.w(TAG, "Unknown name:" + name);
                    }
                    jsonReader.skipValue();
                    break;
            }
        }
        jsonReader.endObject();

        if (bodyText != null) {
            mms.body = new MmsBody(bodyText, bodyCharset);
        }

        { // Get ThreadId.
            Set<String> recipients = new ArraySet<String>();
            for (ContentValues mmsAddress : mms.addresses) {
                String address = getDecodedString(
                        getStringBytes(mmsAddress.getAsString(Telephony.Mms.Addr.ADDRESS),
                                CharacterSets.ISO_8859_1),
                        mmsAddress.getAsInteger(Telephony.Mms.Addr.CHARSET));
                if (selfPhone != null && selfPhone.equals(address))
                    continue;
                recipients.add(address);
            }
            mms.values.put(Telephony.Mms.THREAD_ID,
                    getOrCreateThreadId(threadProvider, recipients));
        }

        // Set default charset for subject.
        if (mms.values.get(Telephony.Mms.SUBJECT) != null &&
                mms.values.get(Telephony.Mms.SUBJECT_CHARSET) == null) {
            mms.values.put(Telephony.Mms.SUBJECT_CHARSET, CharacterSets.DEFAULT_CHARSET);
        }

        return mms;
    }

    private static MmsBody getMmsBody(ContentProvider mmsProvider, int mmsId) {
        Uri MMS_PART_CONTENT_URI = Telephony.Mms.CONTENT_URI.buildUpon()
                .appendPath(String.valueOf(mmsId)).appendPath("part").build();

        String body = null;
        int charSet = 0;

        try (Cursor cursor = mmsProvider.query(MMS_PART_CONTENT_URI, MMS_TEXT_PROJECTION,
                null, null/*selectionArgs*/, ORDER_BY_ID)) {
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    if (ContentType.TEXT_PLAIN.equals(cursor.getString(0))) {
                        body = (body == null ? cursor.getString(1)
                                             : body.concat(cursor.getString(1)));
                        charSet = cursor.getInt(2);
                    }
                } while (cursor.moveToNext());
            }
        }
        return (body == null ? null : new MmsBody(body, charSet));
    }

    private static void writeMmsAddresses(JsonWriter jsonWriter, ContentProvider mmsProvider,
                                          int mmsId) throws IOException {
        Uri.Builder builder = Telephony.Mms.CONTENT_URI.buildUpon();
        builder.appendPath(String.valueOf(mmsId)).appendPath("addr");
        Uri uriAddrPart = builder.build();

        jsonWriter.beginArray();
        try (Cursor cursor = mmsProvider.query(uriAddrPart, MMS_ADDR_PROJECTION,
                null/*selection*/, null/*selectionArgs*/, ORDER_BY_ID)) {
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    if (cursor.getString(cursor.getColumnIndex(Telephony.Mms.Addr.ADDRESS))
                            != null) {
                        jsonWriter.beginObject();
                        writeIntToWriter(jsonWriter, cursor, Telephony.Mms.Addr.TYPE);
                        writeStringToWriter(jsonWriter, cursor, Telephony.Mms.Addr.ADDRESS);
                        writeIntToWriter(jsonWriter, cursor, Telephony.Mms.Addr.CHARSET);
                        jsonWriter.endObject();
                    }
                } while (cursor.moveToNext());
            }
        }
        jsonWriter.endArray();
    }

    private static void getMmsAddressesFromReader(JsonReader jsonReader, Mms mms)
            throws IOException {
        mms.addresses = new ArrayList<ContentValues>();
        jsonReader.beginArray();
        while (jsonReader.hasNext()) {
            jsonReader.beginObject();
            ContentValues addrValues = new ContentValues(defaultValuesAddr);
            while (jsonReader.hasNext()) {
                final String name = jsonReader.nextName();
                switch (name) {
                    case Telephony.Mms.Addr.TYPE:
                    case Telephony.Mms.Addr.CHARSET:
                        addrValues.put(name, jsonReader.nextInt());
                        break;
                    case Telephony.Mms.Addr.ADDRESS:
                        addrValues.put(name, jsonReader.nextString());
                        break;
                    default:
                        if (DEBUG) {
                            Log.w(TAG, "Unknown name:" + name);
                        }
                        jsonReader.skipValue();
                        break;
                }
            }
            jsonReader.endObject();
            if (addrValues.containsKey(Telephony.Mms.Addr.ADDRESS)) {
                mms.addresses.add(addrValues);
            }
        }
        jsonReader.endArray();
    }

    private static void addMmsMessage(ContentProvider mmsProvider, Mms mms) {
        if (DEBUG) {
            Log.e(TAG, "Add mms:\n" + mms.toString());
        }
        final long dummyId = System.currentTimeMillis(); // Dummy ID of the msg.
        final Uri partUri = Telephony.Mms.CONTENT_URI.buildUpon()
                .appendPath(String.valueOf(dummyId)).appendPath("part").build();

        final String srcName = String.format("text.%06d.txt", 0);
        { // Insert SMIL part.
            final String smilBody = String.format(sSmilTextPart, srcName);
            final String smil = String.format(sSmilTextOnly, smilBody);
            final ContentValues values = new ContentValues(7);
            values.put(Telephony.Mms.Part.MSG_ID, dummyId);
            values.put(Telephony.Mms.Part.SEQ, -1);
            values.put(Telephony.Mms.Part.CONTENT_TYPE, ContentType.APP_SMIL);
            values.put(Telephony.Mms.Part.NAME, "smil.xml");
            values.put(Telephony.Mms.Part.CONTENT_ID, "<smil>");
            values.put(Telephony.Mms.Part.CONTENT_LOCATION, "smil.xml");
            values.put(Telephony.Mms.Part.TEXT, smil);
            if (mmsProvider.insert(partUri, values) == null) {
                if (DEBUG) {
                    Log.e(TAG, "Could not insert SMIL part");
                }
                return;
            }
        }

        { // Insert body part.
            final ContentValues values = new ContentValues(8);
            values.put(Telephony.Mms.Part.MSG_ID, dummyId);
            values.put(Telephony.Mms.Part.SEQ, 0);
            values.put(Telephony.Mms.Part.CONTENT_TYPE, ContentType.TEXT_PLAIN);
            values.put(Telephony.Mms.Part.NAME, srcName);
            values.put(Telephony.Mms.Part.CONTENT_ID, "<"+srcName+">");
            values.put(Telephony.Mms.Part.CONTENT_LOCATION, srcName);
            values.put(Telephony.Mms.Part.CHARSET, mms.body.charSet);
            values.put(Telephony.Mms.Part.TEXT, mms.body.text);
            if (mmsProvider.insert(partUri, values) == null) {
                if (DEBUG) {
                    Log.e(TAG, "Could not insert body part");
                }
                return;
            }
        }

        // Insert mms.
        final Uri mmsUri = mmsProvider.insert(Telephony.Mms.CONTENT_URI, mms.values);
        if (mmsUri == null) {
            if (DEBUG) {
                Log.e(TAG, "Could not insert mms");
            }
            return;
        }

        final long mmsId = ContentUris.parseId(mmsUri);
        { // Update parts with the right mms id.
            ContentValues values = new ContentValues(1);
            values.put(Telephony.Mms.Part.MSG_ID, mmsId);
            mmsProvider.update(partUri, values, null, null);
        }

        { // Insert adderesses into "addr".
            final Uri addrUri = Uri.withAppendedPath(mmsUri, "addr");
            for (ContentValues mmsAddress : mms.addresses) {
                ContentValues values = new ContentValues(mmsAddress);
                values.put(Telephony.Mms.Addr.MSG_ID, mmsId);
                mmsProvider.insert(addrUri, values);
            }
        }
    }

    private static final class MmsBody {
        public String text;
        public int charSet;

        public MmsBody(String text, int charSet) {
            this.text = text;
            this.charSet = charSet;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null || !(obj instanceof MmsBody)) {
                return false;
            }
            MmsBody typedObj = (MmsBody) obj;
            return this.text.equals(typedObj.text) && this.charSet == typedObj.charSet;
        }

        @Override
        public String toString() {
            return "Text:" + text + " charSet:" + charSet;
        }
    }

    private static final class Mms {
        public ContentValues values;
        public List<ContentValues> addresses;
        public MmsBody body;
        @Override
        public String toString() {
            return "Values:" + values.toString() + "\nRecipients:"+addresses.toString()
                    + "\nBody:" + body;
        }
    }

    private JsonWriter getJsonWriter(final String fileName) throws IOException {
        return new JsonWriter(new OutputStreamWriter(new DeflaterOutputStream(
                openFileOutput(fileName, MODE_PRIVATE)), CHARSET_UTF8));
    }

    private JsonReader getJsonReader(final FileDescriptor fileDescriptor) throws IOException {
        return new JsonReader(new InputStreamReader(new InflaterInputStream(
                new FileInputStream(fileDescriptor)), CHARSET_UTF8));
    }

    private static void writeStringToWriter(JsonWriter jsonWriter, Cursor cursor, String name)
            throws IOException {
        final String value = cursor.getString(cursor.getColumnIndex(name));
        if (value != null) {
            jsonWriter.name(name).value(value);
        }
    }

    private static void writeIntToWriter(JsonWriter jsonWriter, Cursor cursor, String name)
            throws IOException {
        final int value = cursor.getInt(cursor.getColumnIndex(name));
        if (value != 0) {
            jsonWriter.name(name).value(value);
        }
    }

    // Copied from packages/apps/Messaging/src/com/android/messaging/sms/DatabaseMessages.java.
    /**
     * Decoded string by character set
     */
    private static String getDecodedString(final byte[] data, final int charset)  {
        if (CharacterSets.ANY_CHARSET == charset) {
            return new String(data); // system default encoding.
        } else {
            try {
                final String name = CharacterSets.getMimeName(charset);
                return new String(data, name);
            } catch (final UnsupportedEncodingException e) {
                try {
                    return new String(data, CharacterSets.MIMENAME_ISO_8859_1);
                } catch (final UnsupportedEncodingException exception) {
                    return new String(data); // system default encoding.
                }
            }
        }
    }

    // Copied from packages/apps/Messaging/src/com/android/messaging/sms/DatabaseMessages.java.
    /**
     * Unpack a given String into a byte[].
     */
    private static byte[] getStringBytes(final String data, final int charset) {
        if (CharacterSets.ANY_CHARSET == charset) {
            return data.getBytes();
        } else {
            try {
                final String name = CharacterSets.getMimeName(charset);
                return data.getBytes(name);
            } catch (final UnsupportedEncodingException e) {
                return data.getBytes();
            }
        }
    }

    private static final Uri THREAD_ID_CONTENT_URI = Uri.parse("content://mms-sms/threadID");
    // Copied from frameworks/opt/telephony/src/java/android/provider/Telephony.java because we
    // can't use ContentResolver during backup/restore.
    private static long getOrCreateThreadId(
            ContentProvider contentProvider, Set<String> recipients) {
        Uri.Builder uriBuilder = THREAD_ID_CONTENT_URI.buildUpon();

        for (String recipient : recipients) {
            if (Telephony.Mms.isEmailAddress(recipient)) {
                recipient = Telephony.Mms.extractAddrSpec(recipient);
            }

            uriBuilder.appendQueryParameter("recipient", recipient);
        }

        Uri uri = uriBuilder.build();

        try (Cursor cursor = contentProvider.query(uri, PROJECTION_ID, null, null, null)) {
            if (cursor != null) {
                if (cursor.moveToFirst()) {
                    return cursor.getLong(0);
                } else {
                    Log.e(TAG, "getOrCreateThreadId returned no rows!");
                }
            }
        }

        Log.e(TAG, "getOrCreateThreadId failed with " + recipients.size() + " recipients");
        throw new IllegalArgumentException("Unable to find or allocate a thread ID.");
    }

    // Copied from packages/apps/Messaging/src/com/android/messaging/sms/MmsUtils.java.
    private static List<String> getRecipientsByThread(final ContentProvider threadProvider,
                                                      final long threadId) {
        final String spaceSepIds = getRawRecipientIdsForThread(threadProvider, threadId);
        if (!TextUtils.isEmpty(spaceSepIds)) {
            return getAddresses(threadProvider, spaceSepIds);
        }
        return null;
    }

    private static final Uri ALL_THREADS_URI =
            Telephony.Threads.CONTENT_URI.buildUpon().
                    appendQueryParameter("simple", "true").build();
    private static final int RECIPIENT_IDS  = 1;

    // Copied from packages/apps/Messaging/src/com/android/messaging/sms/MmsUtils.java.
    // NOTE: There are phones on which you can't get the recipients from the thread id for SMS
    // until you have a message in the conversation!
    private static String getRawRecipientIdsForThread(final ContentProvider threadProvider,
                                                      final long threadId) {
        if (threadId <= 0) {
            return null;
        }
        final Cursor thread = threadProvider.query(
                ALL_THREADS_URI,
                SMS_RECIPIENTS_PROJECTION, "_id=?", new String[]{String.valueOf(threadId)}, null);
        if (thread != null) {
            try {
                if (thread.moveToFirst()) {
                    // recipientIds will be a space-separated list of ids into the
                    // canonical addresses table.
                    return thread.getString(RECIPIENT_IDS);
                }
            } finally {
                thread.close();
            }
        }
        return null;
    }

    private static final Uri SINGLE_CANONICAL_ADDRESS_URI =
            Uri.parse("content://mms-sms/canonical-address");

    // Copied from packages/apps/Messaging/src/com/android/messaging/sms/MmsUtils.java.
    private static List<String> getAddresses(final ContentProvider threadProvider,
                                             final String spaceSepIds) {
        final List<String> numbers = new ArrayList<String>();
        final String[] ids = spaceSepIds.split(" ");
        for (final String id : ids) {
            long longId;

            try {
                longId = Long.parseLong(id);
                if (longId < 0) {
                    if (DEBUG) {
                        Log.e(TAG, "getAddresses: invalid id " + longId);
                    }
                    continue;
                }
            } catch (final NumberFormatException ex) {
                if (DEBUG) {
                    Log.e(TAG, "getAddresses: invalid id. " + ex, ex);
                }
                // skip this id
                continue;
            }

            // TODO: build a single query where we get all the addresses at once.
            Cursor c = null;
            try {
                c = threadProvider.query(
                        ContentUris.withAppendedId(SINGLE_CANONICAL_ADDRESS_URI, longId),
                        null, null, null, null);
            } catch (final Exception e) {
                if (DEBUG) {
                    Log.e(TAG, "getAddresses: query failed for id " + longId, e);
                }
            }
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        final String number = c.getString(0);
                        if (!TextUtils.isEmpty(number)) {
                            numbers.add(number);
                        } else {
                            if (DEBUG) {
                                Log.w(TAG, "Canonical MMS/SMS address is empty for id: " + longId);
                            }
                        }
                    }
                } finally {
                    c.close();
                }
            }
        }
        if (numbers.isEmpty()) {
            if (DEBUG) {
                Log.w(TAG, "No MMS addresses found from ids string [" + spaceSepIds + "]");
            }
        }
        return numbers;
    }

    @Override
    public void onBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
                         ParcelFileDescriptor newState) throws IOException {
        // Empty because is not used during full backup.
    }

    @Override
    public void onRestore(BackupDataInput data, int appVersionCode,
                          ParcelFileDescriptor newState) throws IOException {
        // Empty because is not used during full restore.
    }
}
