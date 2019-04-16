/*
**
** Copyright (C) 2014, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package com.android.providers.telephony;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class CarrierDatabaseHelper extends SQLiteOpenHelper {
    private static final String TAG = "CarrierDatabaseHelper";
    private static final boolean DBG = true;

    private static final String DATABASE_NAME = "CarrierInformation.db";
    public static final String CARRIER_KEY_TABLE = "carrier_key";
    private static final int DATABASE_VERSION = 2;

    /**
     * CarrierDatabaseHelper carrier database helper class.
     * @param context of the user.
     */
    public CarrierDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        setWriteAheadLoggingEnabled(false);
    }

    public static final String KEY_TYPE = "key_type";
    public static final String MCC = "mcc";
    public static final String MNC = "mnc";
    public static final String MVNO_TYPE = "mvno_type";
    public static final String MVNO_MATCH_DATA = "mvno_match_data";
    public static final String PUBLIC_KEY = "public_key";
    public static final String KEY_IDENTIFIER = "key_identifier";
    public static final String EXPIRATION_TIME = "expiration_time";
    public static final String LAST_MODIFIED = "last_modified";

    private static final List<String> CARRIERS_UNIQUE_FIELDS = new ArrayList<String>();

    static {
        CARRIERS_UNIQUE_FIELDS.add(MCC);
        CARRIERS_UNIQUE_FIELDS.add(MNC);
        CARRIERS_UNIQUE_FIELDS.add(KEY_TYPE);
        CARRIERS_UNIQUE_FIELDS.add(MVNO_TYPE);
        CARRIERS_UNIQUE_FIELDS.add(MVNO_MATCH_DATA);
    }

    public static String getStringForCarrierKeyTableCreation(String tableName) {
        return "CREATE TABLE " + tableName +
                "(_id INTEGER PRIMARY KEY," +
                MCC + " TEXT DEFAULT ''," +
                MNC + " TEXT DEFAULT ''," +
                MVNO_TYPE + " TEXT DEFAULT ''," +
                MVNO_MATCH_DATA + " TEXT DEFAULT ''," +
                KEY_TYPE + " TEXT DEFAULT ''," +
                KEY_IDENTIFIER + " TEXT DEFAULT ''," +
                PUBLIC_KEY + " BLOB DEFAULT ''," +
                EXPIRATION_TIME + " INTEGER DEFAULT 0," +
                LAST_MODIFIED + " INTEGER DEFAULT 0," +
                "UNIQUE (" + TextUtils.join(", ", CARRIERS_UNIQUE_FIELDS) + "));";
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(getStringForCarrierKeyTableCreation(CARRIER_KEY_TABLE));
    }

    public void createCarrierTable(SQLiteDatabase db) {
        db.execSQL(getStringForCarrierKeyTableCreation(CARRIER_KEY_TABLE));
    }

    public void dropCarrierTable(SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS " + CARRIER_KEY_TABLE + ";");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.d(TAG, "dbh.onUpgrade:+ db=" + db + " oldV=" + oldVersion + " newV=" + newVersion);
        if (oldVersion < 2) {
            dropCarrierTable(db);
            createCarrierTable(db);
        }
    }
}
