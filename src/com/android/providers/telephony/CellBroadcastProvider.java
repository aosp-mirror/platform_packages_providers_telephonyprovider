/*
 * Copyright (C) 2019 The Android Open Source Project
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
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;
import android.provider.Telephony.CellBroadcasts;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Arrays;

/**
 * The content provider that provides access of cell broadcast message to application.
 * Permission {@link android.permission.READ_CELL_BROADCASTS} is required for querying the cell
 * broadcast message. Only phone process has the permission to write/update the database via this
 * provider.
 */
public class CellBroadcastProvider extends ContentProvider {
    /** Interface for read/write permission check. */
    public interface PermissionChecker {
        /** Return {@code True} if the caller has the permission to write/update the database. */
        boolean hasWritePermission();

        /** Return {@code True} if the caller has the permission to query the database. */
        boolean hasReadPermission();
    }

    private static final String TAG = CellBroadcastProvider.class.getSimpleName();

    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);

    /** Database name. */
    private static final String DATABASE_NAME = "cellbroadcasts.db";

    /** Database version. */
    private static final int DATABASE_VERSION = 1;

    /** URI matcher for ContentProvider queries. */
    private static final UriMatcher sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    /** URI matcher type to get all cell broadcasts. */
    private static final int ALL = 0;

    /** MIME type for the list of all cell broadcasts. */
    private static final String LIST_TYPE = "vnd.android.cursor.dir/cellbroadcast";

    /** Table name of cell broadcast message. */
    @VisibleForTesting
    public static final String CELL_BROADCASTS_TABLE_NAME = "cell_broadcasts";

    /** Authority string for content URIs. */
    @VisibleForTesting
    public static final String AUTHORITY = "cellbroadcasts_fwk";

    /** Content uri of this provider. */
    public static final Uri CONTENT_URI = Uri.parse("content://cellbroadcasts_fwk");

    @VisibleForTesting
    public PermissionChecker mPermissionChecker;

    /** The database helper for this content provider. */
    @VisibleForTesting
    public SQLiteOpenHelper mDbHelper;

    static {
        sUriMatcher.addURI(AUTHORITY, null, ALL);
    }

    public CellBroadcastProvider() {}

    @VisibleForTesting
    public CellBroadcastProvider(PermissionChecker permissionChecker) {
        mPermissionChecker = permissionChecker;
    }

    @Override
    public boolean onCreate() {
        mDbHelper = new CellBroadcastDatabaseHelper(getContext());
        mPermissionChecker = new CellBroadcastPermissionChecker();
        setAppOps(AppOpsManager.OP_READ_CELL_BROADCASTS, AppOpsManager.OP_NONE);
        return true;
    }

    /**
     * Return the MIME type of the data at the specified URI.
     *
     * @param uri the URI to query.
     * @return a MIME type string, or null if there is no type.
     */
    @Override
    public String getType(Uri uri) {
        int match = sUriMatcher.match(uri);
        switch (match) {
            case ALL:
                return LIST_TYPE;
            default:
                return null;
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        checkReadPermission();

        if (DBG) {
            Log.d(TAG, "query:"
                    + " uri = " + uri
                    + " projection = " + Arrays.toString(projection)
                    + " selection = " + selection
                    + " selectionArgs = " + Arrays.toString(selectionArgs)
                    + " sortOrder = " + sortOrder);
        }

        String orderBy;
        if (!TextUtils.isEmpty(sortOrder)) {
            orderBy = sortOrder;
        } else {
            orderBy = CellBroadcasts.RECEIVED_TIME + " DESC";
        }

        int match = sUriMatcher.match(uri);
        switch (match) {
            case ALL:
                return getReadableDatabase().query(
                        CELL_BROADCASTS_TABLE_NAME, projection, selection, selectionArgs,
                        null /* groupBy */, null /* having */, orderBy);
            default:
                throw new IllegalArgumentException(
                        "Query method doesn't support this uri = " + uri);
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        checkWritePermission();

        if (DBG) {
            Log.d(TAG, "insert:"
                    + " uri = " + uri
                    + " contentValue = " + values);
        }

        switch (sUriMatcher.match(uri)) {
            case ALL:
                long row = getWritableDatabase().insertOrThrow(CELL_BROADCASTS_TABLE_NAME, null,
                        values);
                if (row > 0) {
                    Uri newUri = ContentUris.withAppendedId(CONTENT_URI, row);
                    getContext().getContentResolver()
                            .notifyChange(CONTENT_URI, null /* observer */);
                    return newUri;
                } else {
                    Log.e(TAG, "Insert record failed because of unknown reason, uri = " + uri);
                    return null;
                }
            default:
                throw new IllegalArgumentException(
                        "Insert method doesn't support this uri = " + uri);
        }
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        checkWritePermission();

        if (DBG) {
            Log.d(TAG, "delete:"
                    + " uri = " + uri
                    + " selection = " + selection
                    + " selectionArgs = " + Arrays.toString(selectionArgs));
        }

        switch (sUriMatcher.match(uri)) {
            case ALL:
                return getWritableDatabase().delete(CELL_BROADCASTS_TABLE_NAME,
                        selection, selectionArgs);
            default:
                throw new IllegalArgumentException(
                        "Delete method doesn't support this uri = " + uri);
        }
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        checkWritePermission();

        if (DBG) {
            Log.d(TAG, "update:"
                    + " uri = " + uri
                    + " values = {" + values + "}"
                    + " selection = " + selection
                    + " selectionArgs = " + Arrays.toString(selectionArgs));
        }

        switch (sUriMatcher.match(uri)) {
            case ALL:
                int rowCount = getWritableDatabase().update(
                        CELL_BROADCASTS_TABLE_NAME,
                        values,
                        selection,
                        selectionArgs);
                if (rowCount > 0) {
                    getContext().getContentResolver().notifyChange(uri, null /* observer */);
                }
                return rowCount;
            default:
                throw new IllegalArgumentException(
                        "Update method doesn't support this uri = " + uri);
        }
    }

    @VisibleForTesting
    public static String getStringForCellBroadcastTableCreation(String tableName) {
        return "CREATE TABLE " + tableName + " ("
                + CellBroadcasts._ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + CellBroadcasts.SUB_ID + " INTEGER,"
                + CellBroadcasts.GEOGRAPHICAL_SCOPE + " INTEGER,"
                + CellBroadcasts.PLMN + " TEXT,"
                + CellBroadcasts.LAC + " INTEGER,"
                + CellBroadcasts.CID + " INTEGER,"
                + CellBroadcasts.SERIAL_NUMBER + " INTEGER,"
                + CellBroadcasts.SERVICE_CATEGORY + " INTEGER,"
                + CellBroadcasts.LANGUAGE_CODE + " TEXT,"
                + CellBroadcasts.MESSAGE_BODY + " TEXT,"
                + CellBroadcasts.MESSAGE_FORMAT + " INTEGER,"
                + CellBroadcasts.MESSAGE_PRIORITY + " INTEGER,"
                + CellBroadcasts.ETWS_WARNING_TYPE + " INTEGER,"
                + CellBroadcasts.CMAS_MESSAGE_CLASS + " INTEGER,"
                + CellBroadcasts.CMAS_CATEGORY + " INTEGER,"
                + CellBroadcasts.CMAS_RESPONSE_TYPE + " INTEGER,"
                + CellBroadcasts.CMAS_SEVERITY + " INTEGER,"
                + CellBroadcasts.CMAS_URGENCY + " INTEGER,"
                + CellBroadcasts.CMAS_CERTAINTY + " INTEGER,"
                + CellBroadcasts.RECEIVED_TIME + " BIGINT,"
                + CellBroadcasts.MESSAGE_BROADCASTED + " BOOLEAN DEFAULT 0,"
                + CellBroadcasts.GEOMETRIES + " TEXT,"
                + CellBroadcasts.MAXIMUM_WAIT_TIME + " INTEGER);";
    }

    private SQLiteDatabase getWritableDatabase() {
        return mDbHelper.getWritableDatabase();
    }

    private SQLiteDatabase getReadableDatabase() {
        return mDbHelper.getReadableDatabase();
    }

    private void checkWritePermission() {
        if (!mPermissionChecker.hasWritePermission()) {
            throw new SecurityException(
                    "No permission to write CellBroadcast provider");
        }
    }

    private void checkReadPermission() {
        if (!mPermissionChecker.hasReadPermission()) {
            throw new SecurityException(
                    "No permission to read CellBroadcast provider");
        }
    }

    private class CellBroadcastDatabaseHelper extends SQLiteOpenHelper {
        CellBroadcastDatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null /* factory */, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL(getStringForCellBroadcastTableCreation(CELL_BROADCASTS_TABLE_NAME));
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}
    }

    private class CellBroadcastPermissionChecker implements PermissionChecker {
        @Override
        public boolean hasWritePermission() {
            // Only the phone process has the write permission to modify this provider. 
            return Binder.getCallingUid() == Process.PHONE_UID;
        }

        @Override
        public boolean hasReadPermission() {
            // Only the phone process has the read permission to query data from this provider. 
            return Binder.getCallingUid() == Process.PHONE_UID;
        }
    }
}
