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

import android.annotation.NonNull;
import android.annotation.TargetApi;
import android.app.AlarmManager;
import android.app.IntentService;
import android.app.backup.BackupAgent;
import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.app.backup.FullBackupDataOutput;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
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

import com.android.internal.annotations.VisibleForTesting;

import com.google.android.mms.ContentType;
import com.google.android.mms.pdu.CharacterSets;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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
 *  "attachments":[{"mime_type":"image/jpeg","filename":"image000000.jpg"}],
 *  "smil":"<smil><head><layout><root-layout/><region id='Image' fit='meet' top='0' left='0'
 *   height='100%' width='100%'/></layout></head><body><par dur='5000ms'><img src='image000000.jpg'
 *   region='Image' /></par></body></smil>",
 *  "mms_charset":106,"sub_cs":"106"}]
 *
 *   It deflates the files on the flight.
 *   Every 1000 messages it backs up file, deletes it and creates a new one with the same name.
 *
 *   It stores how many bytes we are over the quota and don't backup the oldest messages.
 *
 *   NOTE: presently, only MMS's with text are backed up. However, MMS's with attachments are
 *   restored. In other words, this code can restore MMS attachments if the attachment data
 *   is in the json, but it doesn't currently backup the attachment data in the json.
 */

@TargetApi(Build.VERSION_CODES.M)
public class TelephonyBackupAgent extends BackupAgent {
    private static final String TAG = "TelephonyBackupAgent";
    private static final boolean DEBUG = false;
    private static volatile boolean sIsRestoring;


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
    // JSON key for list of attachments of MMS message.
    private static final String MMS_ATTACHMENTS_KEY = "attachments";
    // JSON key for SMIL part of the MMS.
    private static final String MMS_SMIL_KEY = "smil";
    // JSON key for list of recipients of the message.
    private static final String RECIPIENTS = "recipients";
    // JSON key for MMS body.
    private static final String MMS_BODY_KEY = "mms_body";
    // JSON key for MMS charset.
    private static final String MMS_BODY_CHARSET_KEY = "mms_charset";
    // JSON key for mime type.
    private static final String MMS_MIME_TYPE = "mime_type";
    // JSON key for attachment filename.
    private static final String MMS_ATTACHMENT_FILENAME = "filename";

    // File names suffixes for backup/restore.
    private static final String SMS_BACKUP_FILE_SUFFIX = "_sms_backup";
    private static final String MMS_BACKUP_FILE_SUFFIX = "_mms_backup";

    // File name formats for backup. It looks like 000000_sms_backup, 000001_sms_backup, etc.
    private static final String SMS_BACKUP_FILE_FORMAT = "%06d"+SMS_BACKUP_FILE_SUFFIX;
    private static final String MMS_BACKUP_FILE_FORMAT = "%06d"+MMS_BACKUP_FILE_SUFFIX;

    // Charset being used for reading/writing backup files.
    private static final String CHARSET_UTF8 = "UTF-8";

    // Order by ID entries from database.
    private static final String ORDER_BY_ID = BaseColumns._ID + " ASC";

    // Order by Date entries from database. We start backup from the oldest.
    private static final String ORDER_BY_DATE = "date ASC";

    // This is a hard coded string rather than a localized one because we don't want it to
    // change when you change locale.
    @VisibleForTesting
    static final String UNKNOWN_SENDER = "\u02BCUNKNOWN_SENDER!\u02BC";

    private static String ATTACHMENT_DATA_PATH = "/app_parts/";

    // Thread id for UNKNOWN_SENDER.
    private long mUnknownSenderThreadId;

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
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.READ
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
            Telephony.Mms.MESSAGE_BOX,
            Telephony.Mms.CONTENT_LOCATION,
            Telephony.Mms.THREAD_ID,
            Telephony.Mms.TRANSACTION_ID,
            Telephony.Mms.READ
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
            Telephony.Mms.Part.TEXT,
            Telephony.Mms.Part.CHARSET
    };
    static final int MMS_TEXT_IDX = 0;
    static final int MMS_TEXT_CHARSET_IDX = 1;

    // Buffer size for Json writer.
    public static final int WRITER_BUFFER_SIZE = 32*1024; //32Kb

    // We increase how many bytes backup size over quota by 10%, so we will fit into quota on next
    // backup
    public static final double BYTES_OVER_QUOTA_MULTIPLIER = 1.1;

    // Maximum messages for one backup file. After reaching the limit the agent backs up the file,
    // deletes it and creates a new one with the same name.
    // Not final for the testing.
    @VisibleForTesting
    int mMaxMsgPerFile = 1000;

    // Default values for SMS, MMS, Addresses restore.
    private static ContentValues sDefaultValuesSms = new ContentValues(5);
    private static ContentValues sDefaultValuesMms = new ContentValues(6);
    private static final ContentValues sDefaultValuesAddr = new ContentValues(2);
    private static final ContentValues sDefaultValuesAttachments = new ContentValues(2);

    // Shared preferences for the backup agent.
    private static final String BACKUP_PREFS = "backup_shared_prefs";
    // Key for storing quota bytes.
    private static final String QUOTA_BYTES = "backup_quota_bytes";
    // Key for storing backup data size.
    private static final String BACKUP_DATA_BYTES = "backup_data_bytes";
    // Key for storing timestamp when backup agent resets quota. It does that to get onQuotaExceeded
    // call so it could get the new quota if it changed.
    private static final String QUOTA_RESET_TIME = "reset_quota_time";
    private static final long QUOTA_RESET_INTERVAL = 30 * AlarmManager.INTERVAL_DAY; // 30 days.


    static {
        // Consider restored messages read and seen by default. The actual data can override
        // these values.
        sDefaultValuesSms.put(Telephony.Sms.READ, 1);
        sDefaultValuesSms.put(Telephony.Sms.SEEN, 1);
        sDefaultValuesSms.put(Telephony.Sms.ADDRESS, UNKNOWN_SENDER);
        // If there is no sub_id with self phone number on restore set it to -1.
        sDefaultValuesSms.put(Telephony.Sms.SUBSCRIPTION_ID, -1);

        sDefaultValuesMms.put(Telephony.Mms.READ, 1);
        sDefaultValuesMms.put(Telephony.Mms.SEEN, 1);
        sDefaultValuesMms.put(Telephony.Mms.SUBSCRIPTION_ID, -1);
        sDefaultValuesMms.put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_ALL);
        sDefaultValuesMms.put(Telephony.Mms.TEXT_ONLY, 1);

        sDefaultValuesAddr.put(Telephony.Mms.Addr.TYPE, 0);
        sDefaultValuesAddr.put(Telephony.Mms.Addr.CHARSET, CharacterSets.DEFAULT_CHARSET);
    }


    private SparseArray<String> mSubId2phone = new SparseArray<String>();
    private Map<String, Integer> mPhone2subId = new ArrayMap<String, Integer>();
    private Map<Long, Boolean> mThreadArchived = new HashMap<>();

    private ContentResolver mContentResolver;
    // How many bytes we can backup to fit into quota.
    private long mBytesOverQuota;

    // Cache list of recipients by threadId. It reduces db requests heavily. Used during backup.
    @VisibleForTesting
    Map<Long, List<String>> mCacheRecipientsByThread = null;
    // Cache threadId by list of recipients. Used during restore.
    @VisibleForTesting
    Map<Set<String>, Long> mCacheGetOrCreateThreadId = null;

    @Override
    public void onCreate() {
        super.onCreate();

        final SubscriptionManager subscriptionManager = SubscriptionManager.from(this);
        if (subscriptionManager != null) {
            final List<SubscriptionInfo> subInfo =
                    subscriptionManager.getCompleteActiveSubscriptionInfoList();
            if (subInfo != null) {
                for (SubscriptionInfo sub : subInfo) {
                    final String phoneNumber = getNormalizedNumber(sub);
                    mSubId2phone.append(sub.getSubscriptionId(), phoneNumber);
                    mPhone2subId.put(phoneNumber, sub.getSubscriptionId());
                }
            }
        }
        mContentResolver = getContentResolver();
        initUnknownSender();
    }

    @VisibleForTesting
    void setContentResolver(ContentResolver contentResolver) {
        mContentResolver = contentResolver;
    }
    @VisibleForTesting
    void setSubId(SparseArray<String> subId2Phone, Map<String, Integer> phone2subId) {
        mSubId2phone = subId2Phone;
        mPhone2subId = phone2subId;
    }

    @VisibleForTesting
    void initUnknownSender() {
        mUnknownSenderThreadId = getOrCreateThreadId(null);
        sDefaultValuesSms.put(Telephony.Sms.THREAD_ID, mUnknownSenderThreadId);
        sDefaultValuesMms.put(Telephony.Mms.THREAD_ID, mUnknownSenderThreadId);
    }

    @Override
    public void onFullBackup(FullBackupDataOutput data) throws IOException {
        SharedPreferences sharedPreferences = getSharedPreferences(BACKUP_PREFS, MODE_PRIVATE);
        if (sharedPreferences.getLong(QUOTA_RESET_TIME, Long.MAX_VALUE) <
                System.currentTimeMillis()) {
            clearSharedPreferences();
        }

        mBytesOverQuota = sharedPreferences.getLong(BACKUP_DATA_BYTES, 0) -
                sharedPreferences.getLong(QUOTA_BYTES, Long.MAX_VALUE);
        if (mBytesOverQuota > 0) {
            mBytesOverQuota *= BYTES_OVER_QUOTA_MULTIPLIER;
        }

        try (
                Cursor smsCursor = mContentResolver.query(Telephony.Sms.CONTENT_URI, SMS_PROJECTION,
                        null, null, ORDER_BY_DATE);
                Cursor mmsCursor = mContentResolver.query(Telephony.Mms.CONTENT_URI, MMS_PROJECTION,
                        null, null, ORDER_BY_DATE)) {

            if (smsCursor != null) {
                smsCursor.moveToFirst();
            }
            if (mmsCursor != null) {
                mmsCursor.moveToFirst();
            }

            // It backs up messages from the oldest to newest. First it looks at the timestamp of
            // the next SMS messages and MMS message. If the SMS is older it backs up 1000 SMS
            // messages, otherwise 1000 MMS messages. Repeat until out of SMS's or MMS's.
            // It ensures backups are incremental.
            int fileNum = 0;
            while (smsCursor != null && !smsCursor.isAfterLast() &&
                    mmsCursor != null && !mmsCursor.isAfterLast()) {
                final long smsDate = TimeUnit.MILLISECONDS.toSeconds(getMessageDate(smsCursor));
                final long mmsDate = getMessageDate(mmsCursor);
                if (smsDate < mmsDate) {
                    backupAll(data, smsCursor,
                            String.format(Locale.US, SMS_BACKUP_FILE_FORMAT, fileNum++));
                } else {
                    backupAll(data, mmsCursor, String.format(Locale.US,
                            MMS_BACKUP_FILE_FORMAT, fileNum++));
                }
            }

            while (smsCursor != null && !smsCursor.isAfterLast()) {
                backupAll(data, smsCursor,
                        String.format(Locale.US, SMS_BACKUP_FILE_FORMAT, fileNum++));
            }

            while (mmsCursor != null && !mmsCursor.isAfterLast()) {
                backupAll(data, mmsCursor,
                        String.format(Locale.US, MMS_BACKUP_FILE_FORMAT, fileNum++));
            }
        }

        mThreadArchived = new HashMap<>();
    }

    @VisibleForTesting
    void clearSharedPreferences() {
        getSharedPreferences(BACKUP_PREFS, MODE_PRIVATE).edit()
                .remove(BACKUP_DATA_BYTES)
                .remove(QUOTA_BYTES)
                .remove(QUOTA_RESET_TIME)
                .apply();
    }

    private static long getMessageDate(Cursor cursor) {
        return cursor.getLong(cursor.getColumnIndex(Telephony.Sms.DATE));
    }

    @Override
    public void onQuotaExceeded(long backupDataBytes, long quotaBytes) {
        SharedPreferences sharedPreferences = getSharedPreferences(BACKUP_PREFS, MODE_PRIVATE);
        if (sharedPreferences.contains(BACKUP_DATA_BYTES)
                && sharedPreferences.contains(QUOTA_BYTES)) {
            // Increase backup size by the size we skipped during previous backup.
            backupDataBytes += (sharedPreferences.getLong(BACKUP_DATA_BYTES, 0)
                    - sharedPreferences.getLong(QUOTA_BYTES, 0)) * BYTES_OVER_QUOTA_MULTIPLIER;
        }
        sharedPreferences.edit()
                .putLong(BACKUP_DATA_BYTES, backupDataBytes)
                .putLong(QUOTA_BYTES, quotaBytes)
                .putLong(QUOTA_RESET_TIME, System.currentTimeMillis() + QUOTA_RESET_INTERVAL)
                .apply();
    }

    private void backupAll(FullBackupDataOutput data, Cursor cursor, String fileName)
            throws IOException {
        if (cursor == null || cursor.isAfterLast()) {
            return;
        }

        // Backups consist of multiple chunks; each chunk consists of a set of messages
        // of the same type in a chronological order.
        BackupChunkInformation chunk;
        try (JsonWriter jsonWriter = getJsonWriter(fileName)) {
            if (fileName.endsWith(SMS_BACKUP_FILE_SUFFIX)) {
                chunk = putSmsMessagesToJson(cursor, jsonWriter);
            } else {
                chunk = putMmsMessagesToJson(cursor, jsonWriter);
            }
        }
        backupFile(chunk, fileName, data);
    }

    @VisibleForTesting
    @NonNull
    BackupChunkInformation putMmsMessagesToJson(Cursor cursor,
                             JsonWriter jsonWriter) throws IOException {
        BackupChunkInformation results = new BackupChunkInformation();
        jsonWriter.beginArray();
        for (; results.count < mMaxMsgPerFile && !cursor.isAfterLast();
                cursor.moveToNext()) {
            writeMmsToWriter(jsonWriter, cursor, results);
        }
        jsonWriter.endArray();
        return results;
    }

    @VisibleForTesting
    @NonNull
    BackupChunkInformation putSmsMessagesToJson(Cursor cursor, JsonWriter jsonWriter)
      throws IOException {
        BackupChunkInformation results = new BackupChunkInformation();
        jsonWriter.beginArray();
        for (; results.count < mMaxMsgPerFile && !cursor.isAfterLast();
                ++results.count, cursor.moveToNext()) {
            writeSmsToWriter(jsonWriter, cursor, results);
        }
        jsonWriter.endArray();
        return results;
    }

    private void backupFile(BackupChunkInformation chunkInformation, String fileName,
        FullBackupDataOutput data)
            throws IOException {
        final File file = new File(getFilesDir().getPath() + "/" + fileName);
        file.setLastModified(chunkInformation.timestamp);
        try {
            if (chunkInformation.count > 0) {
                if (mBytesOverQuota > 0) {
                    mBytesOverQuota -= file.length();
                    return;
                }
                super.fullBackupFile(file, data);
            }
        } finally {
            file.delete();
        }
    }

    public static class DeferredSmsMmsRestoreService extends IntentService {
        private static final String TAG = "DeferredSmsMmsRestoreService";

        private final Comparator<File> mFileComparator = new Comparator<File>() {
            @Override
            public int compare(File lhs, File rhs) {
                return rhs.getName().compareTo(lhs.getName());
            }
        };

        public DeferredSmsMmsRestoreService() {
            super(TAG);
            setIntentRedelivery(true);
        }

        private TelephonyBackupAgent mTelephonyBackupAgent;
        private PowerManager.WakeLock mWakeLock;

        @Override
        protected void onHandleIntent(Intent intent) {
            try {
                mWakeLock.acquire();
                sIsRestoring = true;

                File[] files = getFilesToRestore(this);

                if (files == null || files.length == 0) {
                    return;
                }
                Arrays.sort(files, mFileComparator);

                boolean didRestore = false;

                for (File file : files) {
                    final String fileName = file.getName();
                    Log.d(TAG, "onHandleIntent restoring file " + fileName);
                    try (FileInputStream fileInputStream = new FileInputStream(file)) {
                        mTelephonyBackupAgent.doRestoreFile(fileName, fileInputStream.getFD());
                        didRestore = true;
                    } catch (Exception e) {
                        // Either IOException or RuntimeException.
                        Log.e(TAG, "onHandleIntent", e);
                    } finally {
                        file.delete();
                    }
                }
                if (didRestore) {
                  // Tell the default sms app to do a full sync now that the messages have been
                  // restored.
                  Log.d(TAG, "onHandleIntent done - notifying default sms app");
                  ProviderUtil.notifyIfNotDefaultSmsApp(null /*uri*/, null /*calling package*/,
                      this);
                }
           } finally {
                sIsRestoring = false;
                mWakeLock.release();
            }
        }

        @Override
        public void onCreate() {
            super.onCreate();
            mTelephonyBackupAgent = new TelephonyBackupAgent();
            mTelephonyBackupAgent.attach(this);
            mTelephonyBackupAgent.onCreate();

            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        }

        @Override
        public void onDestroy() {
            if (mTelephonyBackupAgent != null) {
                mTelephonyBackupAgent.onDestroy();
                mTelephonyBackupAgent = null;
            }
            super.onDestroy();
        }

        static void startIfFilesExist(Context context) {
            File[] files = getFilesToRestore(context);
            if (files == null || files.length == 0) {
                return;
            }
            context.startService(new Intent(context, DeferredSmsMmsRestoreService.class));
        }

        private static File[] getFilesToRestore(Context context) {
            return context.getFilesDir().listFiles(new FileFilter() {
                @Override
                public boolean accept(File file) {
                    return file.getName().endsWith(SMS_BACKUP_FILE_SUFFIX) ||
                            file.getName().endsWith(MMS_BACKUP_FILE_SUFFIX);
                }
            });
        }
    }

    @Override
    public void onRestoreFinished() {
        super.onRestoreFinished();
        DeferredSmsMmsRestoreService.startIfFilesExist(this);
    }

    private void doRestoreFile(String fileName, FileDescriptor fd) throws IOException {
        Log.d(TAG, "Restoring file " + fileName);

        try (JsonReader jsonReader = getJsonReader(fd)) {
            if (fileName.endsWith(SMS_BACKUP_FILE_SUFFIX)) {
                Log.d(TAG, "Restoring SMS");
                putSmsMessagesToProvider(jsonReader);
            } else if (fileName.endsWith(MMS_BACKUP_FILE_SUFFIX)) {
                Log.d(TAG, "Restoring text MMS");
                putMmsMessagesToProvider(jsonReader);
            } else {
                Log.e(TAG, "Unknown file to restore:" + fileName);
            }
        }
    }

    @VisibleForTesting
    void putSmsMessagesToProvider(JsonReader jsonReader) throws IOException {
        jsonReader.beginArray();
        int msgCount = 0;
        final int bulkInsertSize = mMaxMsgPerFile;
        ContentValues[] values = new ContentValues[bulkInsertSize];
        while (jsonReader.hasNext()) {
            ContentValues cv = readSmsValuesFromReader(jsonReader);
            try {
                if (mSmsProviderQuery.doesSmsExist(cv)) {
                    continue;
                }
                values[(msgCount++) % bulkInsertSize] = cv;
                if (msgCount % bulkInsertSize == 0) {
                    mContentResolver.bulkInsert(Telephony.Sms.CONTENT_URI, values);
                }
            } catch (Exception e) {
                Log.e(TAG, "putSmsMessagesToProvider", e);
            }
        }
        if (msgCount % bulkInsertSize > 0) {
            mContentResolver.bulkInsert(Telephony.Sms.CONTENT_URI,
                    Arrays.copyOf(values, msgCount % bulkInsertSize));
        }
        jsonReader.endArray();
    }

    @VisibleForTesting
    void putMmsMessagesToProvider(JsonReader jsonReader) throws IOException {
        jsonReader.beginArray();
        int total = 0;
        while (jsonReader.hasNext()) {
            final Mms mms = readMmsFromReader(jsonReader);
            if (DEBUG) {
                Log.d(TAG, "putMmsMessagesToProvider " + mms);
            }
            try {
                if (doesMmsExist(mms)) {
                    if (DEBUG) {
                        Log.e(TAG, String.format("Mms: %s already exists", mms.toString()));
                    } else {
                        Log.w(TAG, "Mms: Found duplicate MMS");
                    }
                    continue;
                }
                total++;
                addMmsMessage(mms);
            } catch (Exception e) {
                Log.e(TAG, "putMmsMessagesToProvider", e);
            }
        }
        Log.d(TAG, "putMmsMessagesToProvider handled " + total + " new messages.");
    }

    @VisibleForTesting
    static final String[] PROJECTION_ID = {BaseColumns._ID};
    private static final int ID_IDX = 0;

    /**
     * Interface to allow mocking method for testing.
     */
    public interface SmsProviderQuery {
        boolean doesSmsExist(ContentValues smsValues);
    }

    private SmsProviderQuery mSmsProviderQuery = new SmsProviderQuery() {
        @Override
        public boolean doesSmsExist(ContentValues smsValues) {
            // The SMS body might contain '\0' characters (U+0000) such as in the case of
            // http://b/160801497 . SQLite does not allow '\0' in String literals, but as of SQLite
            // version 3.32.2 2020-06-04, it does allow them as selectionArgs; therefore, we're
            // using the latter approach here.
            final String selection = String.format(Locale.US, "%s=%d AND %s=?",
                    Telephony.Sms.DATE, smsValues.getAsLong(Telephony.Sms.DATE),
                    Telephony.Sms.BODY);
            String[] selectionArgs = new String[] { smsValues.getAsString(Telephony.Sms.BODY)};
            try (Cursor cursor = mContentResolver.query(Telephony.Sms.CONTENT_URI, PROJECTION_ID,
                    selection, selectionArgs, null)) {
                return cursor != null && cursor.getCount() > 0;
            }
        }
    };

    @VisibleForTesting
    public void setSmsProviderQuery(SmsProviderQuery smsProviderQuery) {
        mSmsProviderQuery = smsProviderQuery;
    }

    private boolean doesMmsExist(Mms mms) {
        final String where = String.format(Locale.US, "%s = %d",
                Telephony.Sms.DATE, mms.values.getAsLong(Telephony.Mms.DATE));
        try (Cursor cursor = mContentResolver.query(Telephony.Mms.CONTENT_URI, PROJECTION_ID, where,
                null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    final int mmsId = cursor.getInt(ID_IDX);
                    final MmsBody body = getMmsBody(mmsId);
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
        // country iso might not be always available in some corner cases (e.g. mis-configured SIM,
        // carrier config, or test SIM has incorrect IMSI, etc...). In that case, just return the
        // unformatted number.
        if (!TextUtils.isEmpty(subscriptionInfo.getCountryIso())) {
            return PhoneNumberUtils.formatNumberToE164(subscriptionInfo.getNumber(),
                    subscriptionInfo.getCountryIso().toUpperCase(Locale.US));
        } else {
            return subscriptionInfo.getNumber();
        }
    }

    private void writeSmsToWriter(JsonWriter jsonWriter, Cursor cursor,
            BackupChunkInformation chunk) throws IOException {
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
                    final String selfNumber = mSubId2phone.get(subId);
                    if (selfNumber != null) {
                        jsonWriter.name(SELF_PHONE_KEY).value(selfNumber);
                    }
                    break;
                case Telephony.Sms.THREAD_ID:
                    final long threadId = cursor.getLong(i);
                    handleThreadId(jsonWriter, threadId);
                    break;
                case Telephony.Sms._ID:
                    break;
                case Telephony.Sms.DATE:
                case Telephony.Sms.DATE_SENT:
                    chunk.timestamp = findNewestValue(chunk.timestamp, value);
                    jsonWriter.name(name).value(value);
                    break;
                default:
                    jsonWriter.name(name).value(value);
                    break;
            }
        }
        jsonWriter.endObject();
    }

    private long findNewestValue(long current, String latest) {
        if(latest == null) {
            return current;
        }

        try {
            long latestLong = Long.valueOf(latest);
            return Math.max(current, latestLong);
        } catch (NumberFormatException e) {
            Log.d(TAG, "Unable to parse value "+latest);
            return current;
        }

    }

    private void handleThreadId(JsonWriter jsonWriter, long threadId) throws IOException {
        final List<String> recipients = getRecipientsByThread(threadId);
        if (recipients == null || recipients.isEmpty()) {
            return;
        }

        writeRecipientsToWriter(jsonWriter.name(RECIPIENTS), recipients);
        if (!mThreadArchived.containsKey(threadId)) {
            boolean isArchived = isThreadArchived(threadId);
            if (isArchived) {
                jsonWriter.name(Telephony.Threads.ARCHIVED).value(true);
            }
            mThreadArchived.put(threadId, isArchived);
        }
    }

    private static String[] THREAD_ARCHIVED_PROJECTION =
            new String[] { Telephony.Threads.ARCHIVED };
    private static int THREAD_ARCHIVED_IDX = 0;

    private boolean isThreadArchived(long threadId) {
        Uri.Builder builder = Telephony.Threads.CONTENT_URI.buildUpon();
        builder.appendPath(String.valueOf(threadId)).appendPath("recipients");
        Uri uri = builder.build();

        try (Cursor cursor = getContentResolver().query(uri, THREAD_ARCHIVED_PROJECTION, null, null,
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getInt(THREAD_ARCHIVED_IDX) == 1;
            }
        }
        return false;
    }

    private static void writeRecipientsToWriter(JsonWriter jsonWriter, List<String> recipients)
            throws IOException {
        jsonWriter.beginArray();
        if (recipients != null) {
            for (String s : recipients) {
                jsonWriter.value(s);
            }
        }
        jsonWriter.endArray();
    }

    private ContentValues readSmsValuesFromReader(JsonReader jsonReader)
            throws IOException {
        ContentValues values = new ContentValues(6+sDefaultValuesSms.size());
        values.putAll(sDefaultValuesSms);
        long threadId = -1;
        boolean isArchived = false;
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
                case Telephony.Sms.READ:
                    values.put(name, jsonReader.nextString());
                    break;
                case RECIPIENTS:
                    threadId = getOrCreateThreadId(getRecipients(jsonReader));
                    values.put(Telephony.Sms.THREAD_ID, threadId);
                    break;
                case Telephony.Threads.ARCHIVED:
                    isArchived = jsonReader.nextBoolean();
                    break;
                case SELF_PHONE_KEY:
                    final String selfPhone = jsonReader.nextString();
                    if (mPhone2subId.containsKey(selfPhone)) {
                        values.put(Telephony.Sms.SUBSCRIPTION_ID, mPhone2subId.get(selfPhone));
                    }
                    break;
                default:
                    if (DEBUG) {
                        Log.w(TAG, "readSmsValuesFromReader Unknown name:" + name);
                    } else {
                        Log.w(TAG, "readSmsValuesFromReader encountered unknown name.");
                    }
                    jsonReader.skipValue();
                    break;
            }
        }
        jsonReader.endObject();
        archiveThread(threadId, isArchived);
        return values;
    }

    private static Set<String> getRecipients(JsonReader jsonReader) throws IOException {
        Set<String> recipients = new ArraySet<String>();
        jsonReader.beginArray();
        while (jsonReader.hasNext()) {
            recipients.add(jsonReader.nextString());
        }
        jsonReader.endArray();
        return recipients;
    }

    private void writeMmsToWriter(JsonWriter jsonWriter, Cursor cursor,
            BackupChunkInformation chunk) throws IOException {
        final int mmsId = cursor.getInt(ID_IDX);
        final MmsBody body = getMmsBody(mmsId);
        // We backup any message that contains text, but only backup the text part.
        if (body == null || body.text == null) {
            return;
        }

        boolean subjectNull = true;
        jsonWriter.beginObject();
        for (int i=0; i<cursor.getColumnCount(); ++i) {
            final String name = cursor.getColumnName(i);
            final String value = cursor.getString(i);
            if (DEBUG) {
                Log.d(TAG, "writeMmsToWriter name: " + name + " value: " + value);
            }
            if (value == null) {
                continue;
            }
            switch (name) {
                case Telephony.Mms.SUBSCRIPTION_ID:
                    final int subId = cursor.getInt(i);
                    final String selfNumber = mSubId2phone.get(subId);
                    if (selfNumber != null) {
                        jsonWriter.name(SELF_PHONE_KEY).value(selfNumber);
                    }
                    break;
                case Telephony.Mms.THREAD_ID:
                    final long threadId = cursor.getLong(i);
                    handleThreadId(jsonWriter, threadId);
                    break;
                case Telephony.Mms._ID:
                case Telephony.Mms.SUBJECT_CHARSET:
                    break;
                case Telephony.Mms.DATE:
                case Telephony.Mms.DATE_SENT:
                    chunk.timestamp = findNewestValue(chunk.timestamp, value);
                    jsonWriter.name(name).value(value);
                    break;
                case Telephony.Mms.SUBJECT:
                    subjectNull = false;
                default:
                    jsonWriter.name(name).value(value);
                    break;
            }
        }
        // Addresses.
        writeMmsAddresses(jsonWriter.name(MMS_ADDRESSES_KEY), mmsId);
        // Body (text of the message).
        jsonWriter.name(MMS_BODY_KEY).value(body.text);
        // Charset of the body text.
        jsonWriter.name(MMS_BODY_CHARSET_KEY).value(body.charSet);

        if (!subjectNull) {
            // Subject charset.
            writeStringToWriter(jsonWriter, cursor, Telephony.Mms.SUBJECT_CHARSET);
        }
        jsonWriter.endObject();
        chunk.count++;
    }

    private Mms readMmsFromReader(JsonReader jsonReader) throws IOException {
        Mms mms = new Mms();
        mms.values = new ContentValues(5+sDefaultValuesMms.size());
        mms.values.putAll(sDefaultValuesMms);
        jsonReader.beginObject();
        String bodyText = null;
        long threadId = -1;
        boolean isArchived = false;
        int bodyCharset = CharacterSets.DEFAULT_CHARSET;
        while (jsonReader.hasNext()) {
            String name = jsonReader.nextName();
            if (DEBUG) {
                Log.d(TAG, "readMmsFromReader " + name);
            }
            switch (name) {
                case SELF_PHONE_KEY:
                    final String selfPhone = jsonReader.nextString();
                    if (mPhone2subId.containsKey(selfPhone)) {
                        mms.values.put(Telephony.Mms.SUBSCRIPTION_ID, mPhone2subId.get(selfPhone));
                    }
                    break;
                case MMS_ADDRESSES_KEY:
                    getMmsAddressesFromReader(jsonReader, mms);
                    break;
                case MMS_ATTACHMENTS_KEY:
                    getMmsAttachmentsFromReader(jsonReader, mms);
                    break;
                case MMS_SMIL_KEY:
                    mms.smil = jsonReader.nextString();
                    break;
                case MMS_BODY_KEY:
                    bodyText = jsonReader.nextString();
                    break;
                case MMS_BODY_CHARSET_KEY:
                    bodyCharset = jsonReader.nextInt();
                    break;
                case RECIPIENTS:
                    threadId = getOrCreateThreadId(getRecipients(jsonReader));
                    mms.values.put(Telephony.Sms.THREAD_ID, threadId);
                    break;
                case Telephony.Threads.ARCHIVED:
                    isArchived = jsonReader.nextBoolean();
                    break;
                case Telephony.Mms.SUBJECT:
                case Telephony.Mms.SUBJECT_CHARSET:
                case Telephony.Mms.DATE:
                case Telephony.Mms.DATE_SENT:
                case Telephony.Mms.MESSAGE_TYPE:
                case Telephony.Mms.MMS_VERSION:
                case Telephony.Mms.MESSAGE_BOX:
                case Telephony.Mms.CONTENT_LOCATION:
                case Telephony.Mms.TRANSACTION_ID:
                case Telephony.Mms.READ:
                    mms.values.put(name, jsonReader.nextString());
                    break;
                default:
                    Log.d(TAG, "Unknown JSON element name:" + name);
                    jsonReader.skipValue();
                    break;
            }
        }
        jsonReader.endObject();

        if (bodyText != null) {
            mms.body = new MmsBody(bodyText, bodyCharset);
        }
        // Set the text_only flag
        mms.values.put(Telephony.Mms.TEXT_ONLY, (mms.attachments == null
                || mms.attachments.size() == 0) && bodyText != null ? 1 : 0);

        // Set default charset for subject.
        if (mms.values.get(Telephony.Mms.SUBJECT) != null &&
                mms.values.get(Telephony.Mms.SUBJECT_CHARSET) == null) {
            mms.values.put(Telephony.Mms.SUBJECT_CHARSET, CharacterSets.DEFAULT_CHARSET);
        }

        archiveThread(threadId, isArchived);

        return mms;
    }

    private static final String ARCHIVE_THREAD_SELECTION = Telephony.Threads._ID + "=?";

    private void archiveThread(long threadId, boolean isArchived) {
        if (threadId < 0 || !isArchived) {
            return;
        }
        final ContentValues values = new ContentValues(1);
        values.put(Telephony.Threads.ARCHIVED, 1);
        if (mContentResolver.update(
                Telephony.Threads.CONTENT_URI,
                values,
                ARCHIVE_THREAD_SELECTION,
                new String[] { Long.toString(threadId)}) != 1) {
            Log.e(TAG, "archiveThread: failed to update database");
        }
    }

    private MmsBody getMmsBody(int mmsId) {
        Uri MMS_PART_CONTENT_URI = Telephony.Mms.CONTENT_URI.buildUpon()
                .appendPath(String.valueOf(mmsId)).appendPath("part").build();

        String body = null;
        int charSet = 0;

        try (Cursor cursor = mContentResolver.query(MMS_PART_CONTENT_URI, MMS_TEXT_PROJECTION,
                Telephony.Mms.Part.CONTENT_TYPE + "=?", new String[]{ContentType.TEXT_PLAIN},
                ORDER_BY_ID)) {
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    String text = cursor.getString(MMS_TEXT_IDX);
                    if (text != null) {
                        body = (body == null ? text : body.concat(text));
                        charSet = cursor.getInt(MMS_TEXT_CHARSET_IDX);
                    }
                } while (cursor.moveToNext());
            }
        }
        return (body == null ? null : new MmsBody(body, charSet));
    }

    private void writeMmsAddresses(JsonWriter jsonWriter, int mmsId) throws IOException {
        Uri.Builder builder = Telephony.Mms.CONTENT_URI.buildUpon();
        builder.appendPath(String.valueOf(mmsId)).appendPath("addr");
        Uri uriAddrPart = builder.build();

        jsonWriter.beginArray();
        try (Cursor cursor = mContentResolver.query(uriAddrPart, MMS_ADDR_PROJECTION,
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
            ContentValues addrValues = new ContentValues(sDefaultValuesAddr);
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
                        Log.d(TAG, "Unknown JSON Element name:" + name);
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

    private static void getMmsAttachmentsFromReader(JsonReader jsonReader, Mms mms)
            throws IOException {
        if (DEBUG) {
            Log.d(TAG, "Add getMmsAttachmentsFromReader");
        }
        mms.attachments = new ArrayList<ContentValues>();
        jsonReader.beginArray();
        while (jsonReader.hasNext()) {
            jsonReader.beginObject();
            ContentValues attachmentValues = new ContentValues(sDefaultValuesAttachments);
            while (jsonReader.hasNext()) {
                final String name = jsonReader.nextName();
                switch (name) {
                    case MMS_MIME_TYPE:
                    case MMS_ATTACHMENT_FILENAME:
                        attachmentValues.put(name, jsonReader.nextString());
                        break;
                    default:
                        Log.d(TAG, "getMmsAttachmentsFromReader Unknown name:" + name);
                        jsonReader.skipValue();
                        break;
                }
            }
            jsonReader.endObject();
            if (attachmentValues.containsKey(MMS_ATTACHMENT_FILENAME)) {
                mms.attachments.add(attachmentValues);
            } else {
                Log.d(TAG, "Attachment json with no filenames");
            }
        }
        jsonReader.endArray();
    }

    private void addMmsMessage(Mms mms) {
        if (DEBUG) {
            Log.d(TAG, "Add mms:\n" + mms);
        }
        final long dummyId = System.currentTimeMillis(); // Dummy ID of the msg.
        final Uri partUri = Telephony.Mms.CONTENT_URI.buildUpon()
                .appendPath(String.valueOf(dummyId)).appendPath("part").build();

        final String srcName = String.format(Locale.US, "text.%06d.txt", 0);
        { // Insert SMIL part.
            final String smilBody = String.format(sSmilTextPart, srcName);
            final String smil = TextUtils.isEmpty(mms.smil) ?
                    String.format(sSmilTextOnly, smilBody) : mms.smil;
            final ContentValues values = new ContentValues(7);
            values.put(Telephony.Mms.Part.MSG_ID, dummyId);
            values.put(Telephony.Mms.Part.SEQ, -1);
            values.put(Telephony.Mms.Part.CONTENT_TYPE, ContentType.APP_SMIL);
            values.put(Telephony.Mms.Part.NAME, "smil.xml");
            values.put(Telephony.Mms.Part.CONTENT_ID, "<smil>");
            values.put(Telephony.Mms.Part.CONTENT_LOCATION, "smil.xml");
            values.put(Telephony.Mms.Part.TEXT, smil);
            if (mContentResolver.insert(partUri, values) == null) {
                Log.e(TAG, "Could not insert SMIL part");
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

            values.put(
                    Telephony.Mms.Part.CHARSET,
                    mms.body == null ? CharacterSets.DEFAULT_CHARSET : mms.body.charSet);
            values.put(Telephony.Mms.Part.TEXT, mms.body == null ? "" : mms.body.text);

            if (mContentResolver.insert(partUri, values) == null) {
                Log.e(TAG, "Could not insert body part");
                return;
            }
        }

        if (mms.attachments != null) {
            // Insert the attachment parts.
            for (ContentValues mmsAttachment : mms.attachments) {
                final ContentValues values = new ContentValues(6);
                values.put(Telephony.Mms.Part.MSG_ID, dummyId);
                values.put(Telephony.Mms.Part.SEQ, 0);
                values.put(Telephony.Mms.Part.CONTENT_TYPE,
                        mmsAttachment.getAsString(MMS_MIME_TYPE));
                String filename = mmsAttachment.getAsString(MMS_ATTACHMENT_FILENAME);
                values.put(Telephony.Mms.Part.CONTENT_ID, "<"+filename+">");
                values.put(Telephony.Mms.Part.CONTENT_LOCATION, filename);
                values.put(Telephony.Mms.Part._DATA,
                        getDataDir() + ATTACHMENT_DATA_PATH + filename);
                Uri newPartUri = mContentResolver.insert(partUri, values);
                if (newPartUri == null) {
                    Log.e(TAG, "Could not insert attachment part");
                    return;
                }
            }
        }

        // Insert mms.
        final Uri mmsUri = mContentResolver.insert(Telephony.Mms.CONTENT_URI, mms.values);
        if (mmsUri == null) {
            Log.e(TAG, "Could not insert mms");
            return;
        }

        final long mmsId = ContentUris.parseId(mmsUri);
        { // Update parts with the right mms id.
            ContentValues values = new ContentValues(1);
            values.put(Telephony.Mms.Part.MSG_ID, mmsId);
            mContentResolver.update(partUri, values, null, null);
        }

        { // Insert addresses into "addr".
            final Uri addrUri = Uri.withAppendedPath(mmsUri, "addr");
            for (ContentValues mmsAddress : mms.addresses) {
                ContentValues values = new ContentValues(mmsAddress);
                values.put(Telephony.Mms.Addr.MSG_ID, mmsId);
                mContentResolver.insert(addrUri, values);
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
        public List<ContentValues> attachments;
        public String smil;
        public MmsBody body;
        @Override
        public String toString() {
            return "Values:" + values.toString() + "\nRecipients:" + addresses.toString()
                    + "\nAttachments:" + (attachments == null ? "none" : attachments.toString())
                    + "\nBody:" + body;
        }
    }

    private JsonWriter getJsonWriter(final String fileName) throws IOException {
        return new JsonWriter(new BufferedWriter(new OutputStreamWriter(new DeflaterOutputStream(
                openFileOutput(fileName, MODE_PRIVATE)), CHARSET_UTF8), WRITER_BUFFER_SIZE));
    }

    private static JsonReader getJsonReader(final FileDescriptor fileDescriptor)
            throws IOException {
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

    private long getOrCreateThreadId(Set<String> recipients) {
        if (recipients == null) {
            recipients = new ArraySet<String>();
        }

        if (recipients.isEmpty()) {
            recipients.add(UNKNOWN_SENDER);
        }

        if (mCacheGetOrCreateThreadId == null) {
            mCacheGetOrCreateThreadId = new HashMap<>();
        }

        if (!mCacheGetOrCreateThreadId.containsKey(recipients)) {
            long threadId = mUnknownSenderThreadId;
            try {
                threadId = Telephony.Threads.getOrCreateThreadId(this, recipients);
            } catch (RuntimeException e) {
                Log.e(TAG, "Problem obtaining thread.", e);
            }
            mCacheGetOrCreateThreadId.put(recipients, threadId);
            return threadId;
        }

        return mCacheGetOrCreateThreadId.get(recipients);
    }

    @VisibleForTesting
    static final Uri THREAD_ID_CONTENT_URI = Uri.parse("content://mms-sms/threadID");

    // Mostly copied from packages/apps/Messaging/src/com/android/messaging/sms/MmsUtils.java.
    private List<String> getRecipientsByThread(final long threadId) {
        if (mCacheRecipientsByThread == null) {
            mCacheRecipientsByThread = new HashMap<>();
        }

        if (!mCacheRecipientsByThread.containsKey(threadId)) {
            final String spaceSepIds = getRawRecipientIdsForThread(threadId);
            if (!TextUtils.isEmpty(spaceSepIds)) {
                mCacheRecipientsByThread.put(threadId, getAddresses(spaceSepIds));
            } else {
                mCacheRecipientsByThread.put(threadId, new ArrayList<String>());
            }
        }

        return mCacheRecipientsByThread.get(threadId);
    }

    @VisibleForTesting
    static final Uri ALL_THREADS_URI =
            Telephony.Threads.CONTENT_URI.buildUpon().
                    appendQueryParameter("simple", "true").build();
    private static final int RECIPIENT_IDS  = 1;

    // Copied from packages/apps/Messaging/src/com/android/messaging/sms/MmsUtils.java.
    // NOTE: There are phones on which you can't get the recipients from the thread id for SMS
    // until you have a message in the conversation!
    private String getRawRecipientIdsForThread(final long threadId) {
        if (threadId <= 0) {
            return null;
        }
        final Cursor thread = mContentResolver.query(
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

    @VisibleForTesting
    static final Uri SINGLE_CANONICAL_ADDRESS_URI =
            Uri.parse("content://mms-sms/canonical-address");

    // Copied from packages/apps/Messaging/src/com/android/messaging/sms/MmsUtils.java.
    private List<String> getAddresses(final String spaceSepIds) {
        final List<String> numbers = new ArrayList<String>();
        final String[] ids = spaceSepIds.split(" ");
        for (final String id : ids) {
            long longId;

            try {
                longId = Long.parseLong(id);
                if (longId < 0) {
                    Log.e(TAG, "getAddresses: invalid id " + longId);
                    continue;
                }
            } catch (final NumberFormatException ex) {
                Log.e(TAG, "getAddresses: invalid id " + ex, ex);
                // skip this id
                continue;
            }

            // TODO: build a single query where we get all the addresses at once.
            Cursor c = null;
            try {
                c = mContentResolver.query(
                        ContentUris.withAppendedId(SINGLE_CANONICAL_ADDRESS_URI, longId),
                        null, null, null, null);
            } catch (final Exception e) {
                Log.e(TAG, "getAddresses: query failed for id " + longId, e);
            }

            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        final String number = c.getString(0);
                        if (!TextUtils.isEmpty(number)) {
                            numbers.add(number);
                        } else {
                            Log.d(TAG, "Canonical MMS/SMS address is empty for id: " + longId);
                        }
                    }
                } finally {
                    c.close();
                }
            }
        }
        if (numbers.isEmpty()) {
            Log.d(TAG, "No MMS addresses found from ids string [" + spaceSepIds + "]");
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

    public static boolean getIsRestoring() {
        return sIsRestoring;
    }

    private static class BackupChunkInformation {
        // Timestamp of the recent message in the file
        private long timestamp;

        // The number of messages in the backup file
        private int count = 0;
    }
}
