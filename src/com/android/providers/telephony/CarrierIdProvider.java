/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.UriMatcher;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.FileUtils;
import android.os.SystemProperties;
import android.provider.Telephony.CarrierId;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.util.TelephonyUtils;
import com.android.providers.telephony.nano.CarrierIdProto;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This class provides the ability to query the Carrier Identification databases
 * (A.K.A. cid) which is stored in a SQLite database.
 *
 * Each row in carrier identification db consists of matching rule (e.g., MCCMNC, GID1, GID2, PLMN)
 * and its matched carrier id & carrier name. Each carrier either MNO or MVNO could be
 * identified by multiple matching rules but is assigned with a unique ID (cid).
 *
 *
 * This class provides the ability to retrieve the cid of the current subscription.
 * This is done atomically through a query.
 *
 * This class also provides a way to update carrier identifying attributes of an existing entry.
 * Insert entries for new carriers or an existing carrier.
 */
public class CarrierIdProvider extends ContentProvider {

    private static final boolean VDBG = false; // STOPSHIP if true
    private static final String TAG = CarrierIdProvider.class.getSimpleName();

    private static final String DATABASE_NAME = "carrierIdentification.db";
    private static final int DATABASE_VERSION = 5;

    private static final String ASSETS_PB_FILE = "carrier_list.pb";
    private static final String VERSION_KEY = "version";
    // The version number is offset by SDK level, the MSB 8 bits is reserved for SDK.
    private static final int VERSION_BITMASK = 0x00FFFFFF;
    private static final String OTA_UPDATED_PB_PATH = "misc/carrierid/" + ASSETS_PB_FILE;
    private static final String PREF_FILE = CarrierIdProvider.class.getSimpleName();

    private static final UriMatcher s_urlMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    private static final int URL_ALL                = 1;
    private static final int URL_ALL_UPDATE_FROM_PB = 2;
    private static final int URL_ALL_GET_VERSION    = 3;

    /**
     * index 0: {@link CarrierId.All#MCCMNC}
     */
    private static final int MCCMNC_INDEX                = 0;
    /**
     * index 1: {@link CarrierId.All#IMSI_PREFIX_XPATTERN}
     */
    private static final int IMSI_PREFIX_INDEX           = 1;
    /**
     * index 2: {@link CarrierId.All#GID1}
     */
    private static final int GID1_INDEX                  = 2;
    /**
     * index 3: {@link CarrierId.All#GID2}
     */
    private static final int GID2_INDEX                  = 3;
    /**
     * index 4: {@link CarrierId.All#PLMN}
     */
    private static final int PLMN_INDEX                  = 4;
    /**
     * index 5: {@link CarrierId.All#SPN}
     */
    private static final int SPN_INDEX                   = 5;
    /**
     * index 6: {@link CarrierId.All#APN}
     */
    private static final int APN_INDEX                   = 6;
    /**
    * index 7: {@link CarrierId.All#ICCID_PREFIX}
    */
    private static final int ICCID_PREFIX_INDEX          = 7;

    /**
     * index 8: {@link CarrierId.All#PRIVILEGE_ACCESS_RULE}
     */
    private static final int PRIVILEGE_ACCESS_RULE       = 8;
    /**
     * ending index of carrier attribute list.
     */
    private static final int CARRIER_ATTR_END_IDX        = PRIVILEGE_ACCESS_RULE;
    /**
     * The authority string for the CarrierIdProvider
     */
    @VisibleForTesting
    public static final String AUTHORITY = "carrier_id";

    public static final String CARRIER_ID_TABLE = "carrier_id";

    private static final List<String> CARRIERS_ID_UNIQUE_FIELDS = new ArrayList<>(Arrays.asList(
            CarrierId.All.MCCMNC,
            CarrierId.All.GID1,
            CarrierId.All.GID2,
            CarrierId.All.PLMN,
            CarrierId.All.IMSI_PREFIX_XPATTERN,
            CarrierId.All.SPN,
            CarrierId.All.APN,
            CarrierId.All.ICCID_PREFIX,
            CarrierId.All.PRIVILEGE_ACCESS_RULE,
            CarrierId.PARENT_CARRIER_ID));

    private CarrierIdDatabaseHelper mDbHelper;

    /**
     * Stores carrier id information for the current active subscriptions.
     * Key is the active subId and entryValue is carrier id(int), mno carrier id (int) and
     * carrier name(String).
     */
    private final Map<Integer, ContentValues> mCurrentSubscriptionMap =
            new ConcurrentHashMap<>();

    @VisibleForTesting
    public static String getStringForCarrierIdTableCreation(String tableName) {
        return "CREATE TABLE " + tableName
                + "(_id INTEGER PRIMARY KEY,"
                + CarrierId.All.MCCMNC + " TEXT NOT NULL,"
                + CarrierId.All.GID1 + " TEXT,"
                + CarrierId.All.GID2 + " TEXT,"
                + CarrierId.All.PLMN + " TEXT,"
                + CarrierId.All.IMSI_PREFIX_XPATTERN + " TEXT,"
                + CarrierId.All.SPN + " TEXT,"
                + CarrierId.All.APN + " TEXT,"
                + CarrierId.All.ICCID_PREFIX + " TEXT,"
                + CarrierId.All.PRIVILEGE_ACCESS_RULE + " TEXT,"
                + CarrierId.CARRIER_NAME + " TEXT,"
                + CarrierId.CARRIER_ID + " INTEGER DEFAULT -1,"
                + CarrierId.PARENT_CARRIER_ID + " INTEGER DEFAULT -1,"
                + "UNIQUE (" + TextUtils.join(", ", CARRIERS_ID_UNIQUE_FIELDS) + "));";
    }

    @VisibleForTesting
    public static String getStringForIndexCreation(String tableName) {
        return "CREATE INDEX IF NOT EXISTS mccmncIndex ON " + tableName + " ("
                + CarrierId.All.MCCMNC + ");";
    }

    @Override
    public boolean onCreate() {
        Log.d(TAG, "onCreate");
        mDbHelper = new CarrierIdDatabaseHelper(getContext());
        mDbHelper.getReadableDatabase();
        s_urlMatcher.addURI(AUTHORITY, "all", URL_ALL);
        s_urlMatcher.addURI(AUTHORITY, "all/update_db", URL_ALL_UPDATE_FROM_PB);
        s_urlMatcher.addURI(AUTHORITY, "all/get_version", URL_ALL_GET_VERSION);
        updateDatabaseFromPb(mDbHelper.getWritableDatabase());
        return true;
    }

    @Override
    public String getType(Uri uri) {
        Log.d(TAG, "getType");
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projectionIn, String selection,
                        String[] selectionArgs, String sortOrder) {
        if (VDBG) {
            Log.d(TAG, "query:"
                    + " uri=" + uri
                    + " values=" + Arrays.toString(projectionIn)
                    + " selection=" + selection
                    + " selectionArgs=" + Arrays.toString(selectionArgs));
        }

        final int match = s_urlMatcher.match(uri);
        switch (match) {
            case URL_ALL_GET_VERSION:
                checkReadPermission();
                final MatrixCursor cursor = new MatrixCursor(new String[] {VERSION_KEY});
                cursor.addRow(new Object[] {getAppliedVersion()});
                return cursor;
            case URL_ALL:
                checkReadPermission();
                SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
                qb.setTables(CARRIER_ID_TABLE);

                SQLiteDatabase db = getReadableDatabase();
                return qb.query(db, projectionIn, selection, selectionArgs, null, null, sortOrder);
            default:
                return queryCarrierIdForCurrentSubscription(uri, projectionIn);
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        checkWritePermission();
        final int match = s_urlMatcher.match(uri);
        switch (match) {
            case URL_ALL:
                final long row = getWritableDatabase().insertOrThrow(CARRIER_ID_TABLE, null,
                        values);
                if (row > 0) {
                    final Uri newUri = ContentUris.withAppendedId(
                            CarrierId.All.CONTENT_URI, row);
                    getContext().getContentResolver().notifyChange(
                            CarrierId.All.CONTENT_URI, null);
                    return newUri;
                }
                return null;
            default:
                throw new IllegalArgumentException("Cannot insert that URL: " + uri);
        }
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        checkWritePermission();
        if (VDBG) {
            Log.d(TAG, "delete:"
                    + " uri=" + uri
                    + " selection={" + selection + "}"
                    + " selection=" + selection
                    + " selectionArgs=" + Arrays.toString(selectionArgs));
        }
        final int match = s_urlMatcher.match(uri);
        switch (match) {
            case URL_ALL:
                final int count = getWritableDatabase().delete(CARRIER_ID_TABLE, selection,
                        selectionArgs);
                Log.d(TAG, "  delete.count=" + count);
                if (count > 0) {
                    getContext().getContentResolver().notifyChange(
                            CarrierId.All.CONTENT_URI, null);
                }
                return count;
            default:
                throw new IllegalArgumentException("Cannot delete that URL: " + uri);
        }
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        checkWritePermission();
        if (VDBG) {
            Log.d(TAG, "update:"
                    + " uri=" + uri
                    + " values={" + values + "}"
                    + " selection=" + selection
                    + " selectionArgs=" + Arrays.toString(selectionArgs));
        }

        final int match = s_urlMatcher.match(uri);
        switch (match) {
            case URL_ALL_UPDATE_FROM_PB:
                return updateDatabaseFromPb(getWritableDatabase());
            case URL_ALL:
                final int count = getWritableDatabase().update(CARRIER_ID_TABLE, values, selection,
                        selectionArgs);
                Log.d(TAG, "  update.count=" + count);
                if (count > 0) {
                    getContext().getContentResolver().notifyChange(CarrierId.All.CONTENT_URI, null);
                }
                return count;
            default:
                return updateCarrierIdForCurrentSubscription(uri, values);

        }
    }

    /**
     * These methods can be overridden in a subclass for testing CarrierIdProvider using an
     * in-memory database.
     */
    SQLiteDatabase getReadableDatabase() {
        return mDbHelper.getReadableDatabase();
    }
    SQLiteDatabase getWritableDatabase() {
        return mDbHelper.getWritableDatabase();
    }

    private class CarrierIdDatabaseHelper extends SQLiteOpenHelper {
        private final String TAG = CarrierIdDatabaseHelper.class.getSimpleName();

        /**
         * CarrierIdDatabaseHelper carrier identification database helper class.
         * @param context of the user.
         */
        public CarrierIdDatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
            Log.d(TAG, "CarrierIdDatabaseHelper: " + DATABASE_VERSION);
            setWriteAheadLoggingEnabled(false);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            Log.d(TAG, "onCreate");
            db.execSQL(getStringForCarrierIdTableCreation(CARRIER_ID_TABLE));
            db.execSQL(getStringForIndexCreation(CARRIER_ID_TABLE));
        }

        public void createCarrierTable(SQLiteDatabase db) {
            db.execSQL(getStringForCarrierIdTableCreation(CARRIER_ID_TABLE));
            db.execSQL(getStringForIndexCreation(CARRIER_ID_TABLE));
        }

        public void dropCarrierTable(SQLiteDatabase db) {
            db.execSQL("DROP TABLE IF EXISTS " + CARRIER_ID_TABLE + ";");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.d(TAG, "dbh.onUpgrade:+ db=" + db + " oldV=" + oldVersion + " newV=" + newVersion);
            if (oldVersion < DATABASE_VERSION) {
                dropCarrierTable(db);
                createCarrierTable(db);
                // force rewrite carrier id db
                setAppliedVersion(0);
                updateDatabaseFromPb(db);
            }
        }
    }

    /**
     * Parse and persist pb file as database default values.
     * Use version number to detect file update.
     * Update database with data from assets or ota only if version jumps.
     */
    private int updateDatabaseFromPb(SQLiteDatabase db) {
        Log.d(TAG, "update database from pb file");
        int rows = 0;
        CarrierIdProto.CarrierList carrierList = getUpdateCarrierList();
        // No update is needed
        if (carrierList == null) return rows;

        ContentValues cv;
        List<ContentValues> cvs;
        try {
            // Batch all insertions in a single transaction to improve efficiency.
            db.beginTransaction();
            db.delete(CARRIER_ID_TABLE, null, null);
            for (CarrierIdProto.CarrierId id : carrierList.carrierId) {
                for (CarrierIdProto.CarrierAttribute attr : id.carrierAttribute) {
                    cv = new ContentValues();
                    cv.put(CarrierId.CARRIER_ID, id.canonicalId);
                    cv.put(CarrierId.CARRIER_NAME, id.carrierName);
                    // 0 is the default proto value. if parentCanonicalId is unset, apply default
                    // unknown carrier id -1.
                    if (id.parentCanonicalId > 0) {
                        cv.put(CarrierId.PARENT_CARRIER_ID, id.parentCanonicalId);
                    }
                    cvs = new ArrayList<>();
                    convertCarrierAttrToContentValues(cv, cvs, attr, 0);
                    for (ContentValues contentVal : cvs) {
                        // When a constraint violation occurs, the row that contains the violation
                        // is not inserted. But the command continues executing normally.
                        if (db.insertWithOnConflict(CARRIER_ID_TABLE, null, contentVal,
                                SQLiteDatabase.CONFLICT_IGNORE) > 0) {
                            rows++;
                        } else {
                            Log.e(TAG, "updateDatabaseFromPB insertion failure, row: "
                                    + rows + "carrier id: " + id.canonicalId);
                            // TODO metrics
                        }
                    }
                }
            }
            Log.d(TAG, "update database from pb. inserted rows = " + rows);
            if (rows > 0) {
                // Notify listener of DB change
                getContext().getContentResolver().notifyChange(CarrierId.All.CONTENT_URI, null);
            }
            setAppliedVersion(carrierList.version);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return rows;
    }

    /**
     * Recursively loop through carrier attribute list to get all combinations.
     */
    private void convertCarrierAttrToContentValues(ContentValues cv, List<ContentValues> cvs,
            CarrierIdProto.CarrierAttribute attr, int index) {
        if (index > CARRIER_ATTR_END_IDX) {
            ContentValues carrier = new ContentValues(cv);
            if (!cvs.contains(carrier))
            cvs.add(carrier);
            return;
        }
        boolean found = false;
        switch (index) {
            case MCCMNC_INDEX:
                for (String str : attr.mccmncTuple) {
                    cv.put(CarrierId.All.MCCMNC, str);
                    convertCarrierAttrToContentValues(cv, cvs, attr, index + 1);
                    cv.remove(CarrierId.All.MCCMNC);
                    found = true;
                }
                break;
            case IMSI_PREFIX_INDEX:
                for (String str : attr.imsiPrefixXpattern) {
                    cv.put(CarrierId.All.IMSI_PREFIX_XPATTERN, str);
                    convertCarrierAttrToContentValues(cv, cvs, attr, index + 1);
                    cv.remove(CarrierId.All.IMSI_PREFIX_XPATTERN);
                    found = true;
                }
                break;
            case GID1_INDEX:
                for (String str : attr.gid1) {
                    cv.put(CarrierId.All.GID1, str.toLowerCase());
                    convertCarrierAttrToContentValues(cv, cvs, attr, index + 1);
                    cv.remove(CarrierId.All.GID1);
                    found = true;
                }
                break;
            case GID2_INDEX:
                for (String str : attr.gid2) {
                    cv.put(CarrierId.All.GID2, str.toLowerCase());
                    convertCarrierAttrToContentValues(cv, cvs, attr, index + 1);
                    cv.remove(CarrierId.All.GID2);
                    found = true;
                }
                break;
            case PLMN_INDEX:
                for (String str : attr.plmn) {
                    cv.put(CarrierId.All.PLMN, str);
                    convertCarrierAttrToContentValues(cv, cvs, attr, index + 1);
                    cv.remove(CarrierId.All.PLMN);
                    found = true;
                }
                break;
            case SPN_INDEX:
                for (String str : attr.spn) {
                    cv.put(CarrierId.All.SPN, str.toLowerCase());
                    convertCarrierAttrToContentValues(cv, cvs, attr, index + 1);
                    cv.remove(CarrierId.All.SPN);
                    found = true;
                }
                break;
            case APN_INDEX:
                for (String str : attr.preferredApn) {
                    cv.put(CarrierId.All.APN, str);
                    convertCarrierAttrToContentValues(cv, cvs, attr, index + 1);
                    cv.remove(CarrierId.All.APN);
                    found = true;
                }
                break;
            case ICCID_PREFIX_INDEX:
                for (String str : attr.iccidPrefix) {
                    cv.put(CarrierId.All.ICCID_PREFIX, str);
                    convertCarrierAttrToContentValues(cv, cvs, attr, index + 1);
                    cv.remove(CarrierId.All.ICCID_PREFIX);
                    found = true;
                }
                break;
            case PRIVILEGE_ACCESS_RULE:
                for (String str : attr.privilegeAccessRule) {
                    cv.put(CarrierId.All.PRIVILEGE_ACCESS_RULE, str);
                    convertCarrierAttrToContentValues(cv, cvs, attr, index + 1);
                    cv.remove(CarrierId.All.PRIVILEGE_ACCESS_RULE);
                    found = true;
                }
                break;
            default:
                Log.e(TAG, "unsupported index: " + index);
                break;
        }
        // if attribute at index is empty, move forward to the next attribute
        if (!found) {
            convertCarrierAttrToContentValues(cv, cvs, attr, index + 1);
        }
    }

    /**
     * Return the update carrierList.
     * Get the latest version from the last applied, assets and ota file. if the latest version
     * is newer than the last applied, update is required. Otherwise no update is required and
     * the returned carrierList will be null.
     */
    private CarrierIdProto.CarrierList getUpdateCarrierList() {
        int version = getAppliedVersion();
        CarrierIdProto.CarrierList carrierList = null;
        CarrierIdProto.CarrierList assets = null;
        CarrierIdProto.CarrierList ota = null;
        InputStream is = null;

        try {
            is = getContext().getAssets().open(ASSETS_PB_FILE);
            assets = CarrierIdProto.CarrierList.parseFrom(readInputStreamToByteArray(is));
        } catch (IOException ex) {
            Log.e(TAG, "read carrier list from assets pb failure: " + ex);
        } finally {
            FileUtils.closeQuietly(is);
        }
        try {
            is = new FileInputStream(new File(Environment.getDataDirectory(), OTA_UPDATED_PB_PATH));
            ota = CarrierIdProto.CarrierList.parseFrom(readInputStreamToByteArray(is));
        } catch (IOException ex) {
            Log.e(TAG, "read carrier list from ota pb failure: " + ex);
        } finally {
            FileUtils.closeQuietly(is);
        }

        // compare version
        if (assets != null && assets.version > version) {
            carrierList = assets;
            version = assets.version;
        }
        // bypass version check for ota carrier id test
        if (ota != null && ((TelephonyUtils.IS_DEBUGGABLE && SystemProperties.getBoolean(
                "persist.telephony.test.carrierid.ota", false))
                || (ota.version > version))) {
            carrierList = ota;
            version = ota.version;
        }
        Log.d(TAG, "latest version: " + version + " need update: " + (carrierList != null));
        return carrierList;
    }

    private int getAppliedVersion() {
        final SharedPreferences sp = getContext().getSharedPreferences(PREF_FILE,
                Context.MODE_PRIVATE);
        return sp.getInt(VERSION_KEY, -1);
    }

    private void setAppliedVersion(int version) {
        int relative_version = version & VERSION_BITMASK;
        Log.d(TAG, "update version number: " +  Integer.toHexString(version)
                + " relative version: " + relative_version);
        final SharedPreferences sp = getContext().getSharedPreferences(PREF_FILE,
                Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        editor.putInt(VERSION_KEY, version);
        editor.apply();
    }

    /**
     * Util function to convert inputStream to byte array before parsing proto data.
     */
    private static byte[] readInputStreamToByteArray(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        int size = 16 * 1024; // Read 16k chunks
        byte[] data = new byte[size];
        while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        buffer.flush();
        return buffer.toByteArray();
    }

    private int updateCarrierIdForCurrentSubscription(Uri uri, ContentValues cv) {
        // Parse the subId
        int subId;
        try {
            subId = Integer.parseInt(uri.getLastPathSegment());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid subid in provided uri " + uri);
        }
        Log.d(TAG, "updateCarrierIdForSubId: " + subId);

        // Handle DEFAULT_SUBSCRIPTION_ID
        if (subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
            subId = SubscriptionManager.getDefaultSubscriptionId();
        }

        SubscriptionManager sm = (SubscriptionManager) getContext().getSystemService(
            Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        if (!sm.isActiveSubscriptionId(subId)) {
            // Remove absent subId from the currentSubscriptionMap.
            List activeSubscriptions = new ArrayList<>();
            final List<SubscriptionInfo> subscriptionInfoList =
                sm.getCompleteActiveSubscriptionInfoList();
            if (subscriptionInfoList != null) {
                for (SubscriptionInfo subInfo : subscriptionInfoList) {
                    activeSubscriptions.add(subInfo.getSubscriptionId());
                }
            }
            int count = 0;
            for (int subscription : mCurrentSubscriptionMap.keySet()) {
                if (!activeSubscriptions.contains(subscription)) {
                    count++;
                    Log.d(TAG, "updateCarrierIdForSubId: " + subscription);
                    mCurrentSubscriptionMap.remove(subscription);
                    getContext().getContentResolver().notifyChange(CarrierId.CONTENT_URI, null);
                }
            }
            return count;
        } else {
            mCurrentSubscriptionMap.put(subId, new ContentValues(cv));
            getContext().getContentResolver().notifyChange(CarrierId.CONTENT_URI, null);
            return 1;
        }
    }

    private Cursor queryCarrierIdForCurrentSubscription(Uri uri, String[] projectionIn) {
        // Parse the subId, using the default subId if subId is not provided
        int subId = SubscriptionManager.getDefaultSubscriptionId();
        if (!TextUtils.isEmpty(uri.getLastPathSegment())) {
            try {
                subId = Integer.parseInt(uri.getLastPathSegment());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid subid in provided uri" + uri);
            }
        }
        Log.d(TAG, "queryCarrierIdForSubId: " + subId);

        // Handle DEFAULT_SUBSCRIPTION_ID
        if (subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
            subId = SubscriptionManager.getDefaultSubscriptionId();
        }

        if (!mCurrentSubscriptionMap.containsKey(subId)) {
            // Return an empty cursor if subId is not belonging to current subscriptions.
            return new MatrixCursor(projectionIn, 0);
        }
        final MatrixCursor c = new MatrixCursor(projectionIn, 1);
        final MatrixCursor.RowBuilder row = c.newRow();
        for (int i = 0; i < c.getColumnCount(); i++) {
            final String columnName = c.getColumnName(i);
            if (CarrierId.CARRIER_ID.equals(columnName)) {
                row.add(mCurrentSubscriptionMap.get(subId).get(CarrierId.CARRIER_ID));
            } else if (CarrierId.CARRIER_NAME.equals(columnName)) {
                row.add(mCurrentSubscriptionMap.get(subId).get(CarrierId.CARRIER_NAME));
            } else {
                throw new IllegalArgumentException("Invalid column " + projectionIn[i]);
            }
        }
        return c;
    }

    private void checkReadPermission() {
        int status = getContext().checkCallingOrSelfPermission(
                "android.permission.READ_PRIVILEGED_PHONE_STATE");
        if (status == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        throw new SecurityException("No permission to read CarrierId provider");
    }

    private void checkWritePermission() {
        int status = getContext().checkCallingOrSelfPermission(
                "android.permission.MODIFY_PHONE_STATE");
        if (status == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        throw new SecurityException("No permission to write CarrierId provider");
    }
}
