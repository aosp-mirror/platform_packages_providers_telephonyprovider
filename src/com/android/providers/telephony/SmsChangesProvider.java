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
 * limitations under the License.
 */

package com.android.providers.telephony;

import android.app.AppOpsManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.util.Log;

/**
 * This is the ContentProvider for the table sms_changes.
 * This provider is applicable only for Android Auto builds as
 * this table exists only in Android Auto environment.
 *
 * This provider does not notify of changes.
 * Interested observers should instead listen to notification on sms table, instead.
 */
public class SmsChangesProvider extends ContentProvider {
    private final static String TAG = "SmsChangesProvider";

    private static final String TABLE_SMS_CHANGES = "sms_changes";

    // Db open helper for tables stored in CE(Credential Encrypted) storage.
    private SQLiteOpenHelper mCeOpenHelper;

    @Override
    public String getType(Uri url) {
        return null;
    }

    @Override
    public boolean onCreate() {
        setAppOps(AppOpsManager.OP_READ_SMS, AppOpsManager.OP_WRITE_SMS);
        mCeOpenHelper = MmsSmsDatabaseHelper.getInstanceForCe(getContext());
        return true;
    }


    @Override
    public Cursor query(Uri url, String[] projectionIn, String selection,
            String[] selectionArgs, String sort) {
        // return if FEATURE_AUTOMOTIVE is not set
        if (!isAutoFeatureSet()) {
            return null;
        }

        // Only support one type of query
        //  Caller sends content://mms-sms and other params
        if (!isUrlSupported(url)) {
            return null;
        }

        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(TABLE_SMS_CHANGES);
        SQLiteDatabase db = mCeOpenHelper.getReadableDatabase();
        return qb.query(db, projectionIn, selection, selectionArgs,
                null /* groupBy */, null /* having */,
                null /* sortOrder */);
    }

    @Override
    public Uri insert(Uri url, ContentValues initialValues) {
        return null;
    }

    @Override
    public int delete(Uri url, String where, String[] whereArgs) {
        // return if FEATURE_AUTOMOTIVE is not set
        if (!isAutoFeatureSet()) {
            return 0;
        }

        // only support deletion of all data from the table
        if (!isUrlSupported(url)) return 0;

        return mCeOpenHelper.getWritableDatabase().delete(TABLE_SMS_CHANGES,
                null /* whereClause */, null /* whereArgs */);
    }

    private boolean isUrlSupported(Uri url) {
        if (sURLMatcher.match(url) != SMSCHANGES_URL) {
            Log.e(TAG, "Invalid or Unsupported request: " + url);
            return false;
        }
        return true;
    }

    @Override
    public int update(Uri url, ContentValues values, String where, String[] whereArgs) {
        return 0;
    }

    private boolean isAutoFeatureSet() {
        return getContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
    }

    private static final int SMSCHANGES_URL = 0;

    private static final UriMatcher sURLMatcher =
            new UriMatcher(UriMatcher.NO_MATCH);

    static {
        sURLMatcher.addURI("sms-changes", null, SMSCHANGES_URL);
    }
}
