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

import static com.android.providers.telephony.RcsProviderHelper.ID;
import static com.android.providers.telephony.RcsProviderHelper.THREAD_TABLE;

import android.app.AppOpsManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.util.Log;

/**
 * Content provider to handle RCS messages. The functionality here is similar to SmsProvider,
 * MmsProvider etc. This is not meant to be public.
 * @hide
 */
public class RcsProvider extends ContentProvider {
    private final static String TAG = "RcsProvider";
    static final String AUTHORITY = "rcs";
    private static final UriMatcher URL_MATCHER = new UriMatcher(UriMatcher.NO_MATCH);

    private static final int THREAD = 1;

    SQLiteOpenHelper mDbOpenHelper;

    static {
        URL_MATCHER.addURI(AUTHORITY, "thread", THREAD);
    }

    @Override
    public boolean onCreate() {
        setAppOps(AppOpsManager.OP_READ_SMS, AppOpsManager.OP_WRITE_SMS);
        // Use the credential encrypted mmssms.db for RCS messages.
        mDbOpenHelper = MmsSmsDatabaseHelper.getInstanceForCe(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        int match = URL_MATCHER.match(uri);
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        SQLiteDatabase readableDatabase = mDbOpenHelper.getReadableDatabase();

        switch (match) {
            case THREAD:
                RcsProviderHelper.buildThreadQuery(qb);
                break;
            default:
                Log.e(TAG, "Invalid query: " + uri);
        }

        return qb.query(
                readableDatabase, projection, selection, selectionArgs, null, null, sortOrder);
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        int match = URL_MATCHER.match(uri);
        SQLiteDatabase writableDatabase = mDbOpenHelper.getWritableDatabase();

        switch (match) {
            case THREAD:
                writableDatabase.insert(THREAD_TABLE, ID, values);
                break;
            default:
                Log.e(TAG, "Invalid insert: " + uri);
        }

        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        int match = URL_MATCHER.match(uri);
        int deletedCount = 0;
        SQLiteDatabase writableDatabase = mDbOpenHelper.getWritableDatabase();

        switch (match) {
            case THREAD:
                deletedCount = writableDatabase.delete(THREAD_TABLE, selection, selectionArgs);
                break;
            default:
                Log.e(TAG, "Invalid delete: " + uri);
        }

        return deletedCount;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        int match = URL_MATCHER.match(uri);
        int updatedCount = 0;
        SQLiteDatabase writableDatabase = mDbOpenHelper.getWritableDatabase();

        switch (match) {
            case THREAD:
                updatedCount = writableDatabase.update(
                        THREAD_TABLE, values, selection, selectionArgs);
                break;
            default:
                Log.e(TAG, "Invalid update: " + uri);
        }

        return updatedCount;
    }
}
