/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.telephony.SubscriptionManager;
import android.util.Log;

/**
 * Database open helper responsible for Mms/Sms tables in the Device-Encrypted storage.
 */
public class DeviceEncryptedMmsSmsDatabaseHelper extends SQLiteOpenHelper {
  private static final String TAG = "MmsSmsDeDatabaseHelper";

    private static DeviceEncryptedMmsSmsDatabaseHelper sInstance = null;


    private DeviceEncryptedMmsSmsDatabaseHelper(Context context) {
      super(
          context, MmsSmsDatabaseHelper.DATABASE_NAME, null, MmsSmsDatabaseHelper.DATABASE_VERSION);
    }

    /**
     * Return a singleton helper for the combined MMS and SMS
     * database.
     */
    /* package */ static synchronized DeviceEncryptedMmsSmsDatabaseHelper getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new DeviceEncryptedMmsSmsDatabaseHelper(context);
        }
        return sInstance;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createSmsTables(db);
    }

    private void createSmsTables(SQLiteDatabase db) {
        /**
         * This table is used by the SMS dispatcher to hold
         * incomplete partial messages until all the parts arrive.
         */
        db.execSQL("CREATE TABLE raw (" +
                   "_id INTEGER PRIMARY KEY," +
                   "date INTEGER," +
                   "reference_number INTEGER," + // one per full message
                   "count INTEGER," + // the number of parts
                   "sequence INTEGER," + // the part number of this message
                   "destination_port INTEGER," +
                   "address TEXT," +
                   "sub_id INTEGER DEFAULT " + SubscriptionManager.INVALID_SUBSCRIPTION_ID + ", " +
                   "pdu TEXT);"); // the raw PDU for this part
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int currentVersion) {
        Log.w(TAG, "Upgrading database from version " + oldVersion
                + " to " + currentVersion + ".");
        Log.e(TAG, "Destroying all old data.");
        dropAll(db);
        onCreate(db);
    }

    private void dropAll(SQLiteDatabase db) {
        // Clean the database out in order to start over from scratch.
        // We don't need to drop our triggers here because SQLite automatically
        // drops a trigger when its attached database is dropped.
        db.execSQL("DROP TABLE IF EXISTS raw");
    }

    @Override
    public synchronized SQLiteDatabase getWritableDatabase() {
      return super.getWritableDatabase();
    }
}
