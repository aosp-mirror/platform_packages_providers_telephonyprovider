/*
 * Copyright (C) 2023 The Android Open Source Project
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
import android.provider.Telephony;

public class SatelliteDatabaseHelper extends SQLiteOpenHelper {
    private static final String TAG = "SatelliteDatabaseHelper";
    private static final String DATABASE_NAME = "satellite.db";
    private static final int DATABASE_VERSION = 1;

    /**
     * SatelliteDatabaseHelper satellite datagrams database helper class.
     *
     * @param context of the user.
     */
    public SatelliteDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        setWriteAheadLoggingEnabled(false);
    }

    public static String getStringForDatagramTableCreation(String tableName) {
        return "CREATE TABLE " + tableName + "("
                + Telephony.SatelliteDatagrams.COLUMN_UNIQUE_KEY_DATAGRAM_ID
                + " INTEGER PRIMARY KEY,"
                + Telephony.SatelliteDatagrams.COLUMN_DATAGRAM + " BLOB DEFAULT ''" +
                ");";
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createDatagramsTable(db);
    }

    public void createDatagramsTable(SQLiteDatabase db) {
        db.execSQL(getStringForDatagramTableCreation(Telephony.SatelliteDatagrams.TABLE_NAME));
    }

    public void dropDatagramsTable(SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS " + Telephony.SatelliteDatagrams.TABLE_NAME + ";");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // do nothing
    }
}
