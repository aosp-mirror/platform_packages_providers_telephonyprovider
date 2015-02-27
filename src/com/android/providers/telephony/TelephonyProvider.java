/* //device/content/providers/telephony/TelephonyProvider.java
**
** Copyright 2006, The Android Open Source Project
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

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.UriMatcher;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.Binder;
import android.os.Environment;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Telephony;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Xml;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.lang.NumberFormatException;
import java.util.ArrayList;
import java.util.Arrays;

public class TelephonyProvider extends ContentProvider
{
    private static final String DATABASE_NAME = "telephony.db";
    private static final boolean DBG = true;
    private static final boolean VDBG = false; // STOPSHIP if true

    private static final int DATABASE_VERSION = 14 << 16;
    private static final int URL_UNKNOWN = 0;
    private static final int URL_TELEPHONY = 1;
    private static final int URL_CURRENT = 2;
    private static final int URL_ID = 3;
    private static final int URL_RESTOREAPN = 4;
    private static final int URL_PREFERAPN = 5;
    private static final int URL_PREFERAPN_NO_UPDATE = 6;
    private static final int URL_SIMINFO = 7;
    private static final int URL_TELEPHONY_USING_SUBID = 8;
    private static final int URL_CURRENT_USING_SUBID = 9;
    private static final int URL_RESTOREAPN_USING_SUBID = 10;
    private static final int URL_PREFERAPN_USING_SUBID = 11;
    private static final int URL_PREFERAPN_NO_UPDATE_USING_SUBID = 12;
    private static final int URL_SIMINFO_USING_SUBID = 13;
    private static final int URL_UPDATE_DB = 14;

    private static final String TAG = "TelephonyProvider";
    private static final String CARRIERS_TABLE = "carriers";
    private static final String CARRIERS_TABLE_TMP = "carriers_tmp";
    private static final String SIMINFO_TABLE = "siminfo";

    private static final String PREF_FILE = "preferred-apn";
    private static final String COLUMN_APN_ID = "apn_id";

    private static final String BUILD_ID_FILE = "build-id";
    private static final String RO_BUILD_ID = "ro_build_id";

    private static final String PARTNER_APNS_PATH = "etc/apns-conf.xml";
    private static final String OEM_APNS_PATH = "telephony/apns-conf.xml";

    private static final UriMatcher s_urlMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    private static final ContentValues s_currentNullMap;
    private static final ContentValues s_currentSetMap;

    private static final int USER_EDITED_UNTOUCHED = 0;
    private static final int USER_EDITED_EDITED = 1;
    private static final int USER_EDITED_DELETED = 2;
        // DELETED_BUT_PRESENT is an intermediate value used to indicate that an entry deleted
        // by the user is still present in the new APN database and therefore must remain tagged
        // as user deleted rather than completely removed from the database
    private static final int USER_EDITED_DELETED_BUT_PRESENT = 3;

    static {
        s_urlMatcher.addURI("telephony", "carriers", URL_TELEPHONY);
        s_urlMatcher.addURI("telephony", "carriers/current", URL_CURRENT);
        s_urlMatcher.addURI("telephony", "carriers/#", URL_ID);
        s_urlMatcher.addURI("telephony", "carriers/restore", URL_RESTOREAPN);
        s_urlMatcher.addURI("telephony", "carriers/preferapn", URL_PREFERAPN);
        s_urlMatcher.addURI("telephony", "carriers/preferapn_no_update", URL_PREFERAPN_NO_UPDATE);

        s_urlMatcher.addURI("telephony", "siminfo", URL_SIMINFO);

        s_urlMatcher.addURI("telephony", "carriers/subId/*", URL_TELEPHONY_USING_SUBID);
        s_urlMatcher.addURI("telephony", "carriers/current/subId/*", URL_CURRENT_USING_SUBID);
        s_urlMatcher.addURI("telephony", "carriers/restore/subId/*", URL_RESTOREAPN_USING_SUBID);
        s_urlMatcher.addURI("telephony", "carriers/preferapn/subId/*", URL_PREFERAPN_USING_SUBID);
        s_urlMatcher.addURI("telephony", "carriers/preferapn_no_update/subId/*",
                URL_PREFERAPN_NO_UPDATE_USING_SUBID);

        s_urlMatcher.addURI("telephony", "carriers/update_db", URL_UPDATE_DB);

        s_currentNullMap = new ContentValues(1);
        s_currentNullMap.put(Telephony.Carriers.CURRENT, "0");

        s_currentSetMap = new ContentValues(1);
        s_currentSetMap.put(Telephony.Carriers.CURRENT, "1");
    }

    private static class DatabaseHelper extends SQLiteOpenHelper {
        // Context to access resources with
        private Context mContext;

        /**
         * DatabaseHelper helper class for loading apns into a database.
         *
         * @param context of the user.
         */
        public DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, getVersion(context));
            mContext = context;
        }

        private static int getVersion(Context context) {
            if (VDBG) log("getVersion:+");
            // Get the database version, combining a static schema version and the XML version
            Resources r = context.getResources();
            XmlResourceParser parser = r.getXml(com.android.internal.R.xml.apns);
            try {
                XmlUtils.beginDocument(parser, "apns");
                int publicversion = Integer.parseInt(parser.getAttributeValue(null, "version"));
                int version = DATABASE_VERSION | publicversion;
                if (VDBG) log("getVersion:- version=0x" + Integer.toHexString(version));
                return version;
            } catch (Exception e) {
                loge("Can't get version of APN database" + e + " return version=" +
                        Integer.toHexString(DATABASE_VERSION));
                return DATABASE_VERSION;
            } finally {
                parser.close();
            }
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            if (DBG) log("dbh.onCreate:+ db=" + db);
            createSimInfoTable(db);
            createCarriersTable(db, CARRIERS_TABLE);
            initDatabase(db);
            if (DBG) log("dbh.onCreate:- db=" + db);
        }

        @Override
        public void onOpen(SQLiteDatabase db) {
            if (VDBG) log("dbh.onOpen:+ db=" + db);
            try {
                // Try to access the table and create it if "no such table"
                db.query(SIMINFO_TABLE, null, null, null, null, null, null);
                if (DBG) log("dbh.onOpen: ok, queried table=" + SIMINFO_TABLE);
            } catch (SQLiteException e) {
                loge("Exception " + SIMINFO_TABLE + "e=" + e);
                if (e.getMessage().startsWith("no such table")) {
                    createSimInfoTable(db);
                }
            }
            try {
                db.query(CARRIERS_TABLE, null, null, null, null, null, null);
                if (DBG) log("dbh.onOpen: ok, queried table=" + CARRIERS_TABLE);
            } catch (SQLiteException e) {
                loge("Exception " + CARRIERS_TABLE + " e=" + e);
                if (e.getMessage().startsWith("no such table")) {
                    createCarriersTable(db, CARRIERS_TABLE);
                }
            }
            if (VDBG) log("dbh.onOpen:- db=" + db);
        }

        private void createSimInfoTable(SQLiteDatabase db) {
            if (DBG) log("dbh.createSimInfoTable:+");
            db.execSQL("CREATE TABLE " + SIMINFO_TABLE + "("
                    + SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + SubscriptionManager.ICC_ID + " TEXT NOT NULL,"
                    + SubscriptionManager.SIM_SLOT_INDEX + " INTEGER DEFAULT " + SubscriptionManager.SIM_NOT_INSERTED + ","
                    + SubscriptionManager.DISPLAY_NAME + " TEXT,"
                    + SubscriptionManager.CARRIER_NAME + " TEXT,"
                    + SubscriptionManager.NAME_SOURCE + " INTEGER DEFAULT " + SubscriptionManager.NAME_SOURCE_DEFAULT_SOURCE + ","
                    + SubscriptionManager.COLOR + " INTEGER DEFAULT " + SubscriptionManager.COLOR_DEFAULT + ","
                    + SubscriptionManager.NUMBER + " TEXT,"
                    + SubscriptionManager.DISPLAY_NUMBER_FORMAT + " INTEGER NOT NULL DEFAULT " + SubscriptionManager.DISPLAY_NUMBER_DEFAULT + ","
                    + SubscriptionManager.DATA_ROAMING + " INTEGER DEFAULT " + SubscriptionManager.DATA_ROAMING_DEFAULT + ","
                    + SubscriptionManager.MCC + " INTEGER DEFAULT 0,"
                    + SubscriptionManager.MNC + " INTEGER DEFAULT 0"
                    + ");");
            if (DBG) log("dbh.createSimInfoTable:-");
        }

        private void createCarriersTable(SQLiteDatabase db, String tableName) {
            // Set up the database schema
            if (DBG) log("dbh.createCarriersTable: " + tableName);
            db.execSQL("CREATE TABLE " + tableName +
                "(_id INTEGER PRIMARY KEY," +
                    "name TEXT DEFAULT ''," +
                    "numeric TEXT DEFAULT ''," +
                    "mcc TEXT DEFAULT ''," +
                    "mnc TEXT DEFAULT ''," +
                    "apn TEXT DEFAULT ''," +
                    "user TEXT DEFAULT ''," +
                    "server TEXT DEFAULT ''," +
                    "password TEXT DEFAULT ''," +
                    "proxy TEXT DEFAULT ''," +
                    "port TEXT DEFAULT ''," +
                    "mmsproxy TEXT DEFAULT ''," +
                    "mmsport TEXT DEFAULT ''," +
                    "mmsc TEXT DEFAULT ''," +
                    "authtype INTEGER DEFAULT -1," +
                    "type TEXT DEFAULT ''," +
                    "current INTEGER," +
                    "protocol TEXT DEFAULT 'IP'," +
                    "roaming_protocol TEXT DEFAULT 'IP'," +
                    "carrier_enabled BOOLEAN DEFAULT 1," +
                    "bearer INTEGER DEFAULT 0," +
                    "mvno_type TEXT DEFAULT ''," +
                    "mvno_match_data TEXT DEFAULT ''," +
                    "sub_id INTEGER DEFAULT " + SubscriptionManager.INVALID_SUBSCRIPTION_ID + "," +
                    "profile_id INTEGER DEFAULT 0," +
                    "modem_cognitive BOOLEAN DEFAULT 0," +
                    "max_conns INTEGER DEFAULT 0," +
                    "wait_time INTEGER DEFAULT 0," +
                    "max_conns_time INTEGER DEFAULT 0," +
                    "mtu INTEGER DEFAULT 0," +
                    "user_edited INTEGER DEFAULT " + USER_EDITED_UNTOUCHED + "," +
                    // Uniqueness collisions are used to trigger merge code so
                    // if a field is listed
                    // here it means we will accept both (user edited + new apn_conf definition)
                    // Columns not included in UNIQUE constraint: name, current, user_edited,
                    // user, server, password, authtype, type, protocol, roaming_protocol, sub_id,
                    // modem_cognitive, max_conns, wait_time, max_conns_time, mtu
                    "UNIQUE (numeric, mcc, mnc, apn, proxy, port, mmsproxy, mmsport, mmsc," +
                    "carrier_enabled, bearer, mvno_type, mvno_match_data, profile_id));");
            if (DBG) log("dbh.createCarriersTable:-");
        }

        private void initDatabase(SQLiteDatabase db) {
            if (VDBG) log("dbh.initDatabase:+ db=" + db);
            // Read internal APNS data
            Resources r = mContext.getResources();
            XmlResourceParser parser = r.getXml(com.android.internal.R.xml.apns);
            int publicversion = -1;
            try {
                XmlUtils.beginDocument(parser, "apns");
                publicversion = Integer.parseInt(parser.getAttributeValue(null, "version"));
                loadApns(db, parser);
            } catch (Exception e) {
                loge("Got exception while loading APN database." + e);
            } finally {
                parser.close();
            }

            // Read external APNS data (partner-provided)
            XmlPullParser confparser = null;
            // Environment.getRootDirectory() is a fancy way of saying ANDROID_ROOT or "/system".
            File confFile = new File(Environment.getRootDirectory(), PARTNER_APNS_PATH);
            File oemConfFile =  new File(Environment.getOemDirectory(), OEM_APNS_PATH);
            if (oemConfFile.exists()) {
                // OEM image exist APN xml, get the timestamp from OEM & System image for comparison
                long oemApnTime = oemConfFile.lastModified();
                long sysApnTime = confFile.lastModified();
                if (DBG) log("APNs Timestamp: oemTime = " + oemApnTime + " sysTime = "
                        + sysApnTime);

                // To get the latest version from OEM or System image
                if (oemApnTime > sysApnTime) {
                    if (DBG) log("APNs Timestamp: OEM image is greater than System image");
                    confFile = oemConfFile;
                }
            } else {
                // No Apn in OEM image, so load it from system image.
                if (DBG) log("No APNs in OEM image = " + oemConfFile.getPath() +
                        " Load APNs from system image");
            }

            FileReader confreader = null;
            if (DBG) log("confFile = " + confFile);
            try {
                confreader = new FileReader(confFile);
                confparser = Xml.newPullParser();
                confparser.setInput(confreader);
                XmlUtils.beginDocument(confparser, "apns");

                // Sanity check. Force internal version and confidential versions to agree
                int confversion = Integer.parseInt(confparser.getAttributeValue(null, "version"));
                if (publicversion != confversion) {
                    log("initDatabase: throwing exception due to version mismatch");
                    throw new IllegalStateException("Internal APNS file version doesn't match "
                            + confFile.getAbsolutePath());
                }

                loadApns(db, confparser);
            } catch (FileNotFoundException e) {
                // It's ok if the file isn't found. It means there isn't a confidential file
                // Log.e(TAG, "File not found: '" + confFile.getAbsolutePath() + "'");
            } catch (Exception e) {
                loge("initDatabase: Exception while parsing '" + confFile.getAbsolutePath() + "'" +
                        e);
            } finally {
                // Get rid of user deleted entries that are not present in apn xml file. Those
                // entries have user_edited value USER_EDITED_DELETED.
                // Update user_edited value from USER_EDITED_DELETED_BUT_PRESENT
                // to USER_EDITED_DELETED.
                // USER_EDITED_DELETED_BUT_PRESENT indicates rows deleted by user but still
                // present in the xml file. Mark them as user deleted (2).
                if (VDBG) {
                    log("initDatabase: deleting USER_EDITED_DELETED and replacing "
                            + "USER_EDITED_DELETED_BUT_PRESENT with USER_EDITED_DELETED");
                }
                db.delete(CARRIERS_TABLE, "user_edited=" + USER_EDITED_DELETED, null);
                ContentValues cv = new ContentValues();
                cv.put(Telephony.Carriers.USER_EDITED, USER_EDITED_DELETED);
                db.update(CARRIERS_TABLE, cv, "user_edited=" + USER_EDITED_DELETED_BUT_PRESENT,
                        null);
                if (confreader != null) {
                    try {
                        confreader.close();
                    } catch (IOException e) {
                        // do nothing
                    }
                }
            }
            if (VDBG) log("dbh.initDatabase:- db=" + db);

        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (DBG) {
                log("dbh.onUpgrade:+ db=" + db + " oldV=" + oldVersion + " newV=" + newVersion);
            }

            if (oldVersion < (5 << 16 | 6)) {
                // 5 << 16 is the Database version and 6 in the xml version.

                // This change adds a new authtype column to the database.
                // The auth type column can have 4 values: 0 (None), 1 (PAP), 2 (CHAP)
                // 3 (PAP or CHAP). To avoid breaking compatibility, with already working
                // APNs, the unset value (-1) will be used. If the value is -1.
                // the authentication will default to 0 (if no user / password) is specified
                // or to 3. Currently, there have been no reported problems with
                // pre-configured APNs and hence it is set to -1 for them. Similarly,
                // if the user, has added a new APN, we set the authentication type
                // to -1.

                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN authtype INTEGER DEFAULT -1;");

                oldVersion = 5 << 16 | 6;
            }
            if (oldVersion < (6 << 16 | 6)) {
                // Add protcol fields to the APN. The XML file does not change.
                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN protocol TEXT DEFAULT IP;");
                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN roaming_protocol TEXT DEFAULT IP;");
                oldVersion = 6 << 16 | 6;
            }
            if (oldVersion < (7 << 16 | 6)) {
                // Add carrier_enabled, bearer fields to the APN. The XML file does not change.
                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN carrier_enabled BOOLEAN DEFAULT 1;");
                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN bearer INTEGER DEFAULT 0;");
                oldVersion = 7 << 16 | 6;
            }
            if (oldVersion < (8 << 16 | 6)) {
                // Add mvno_type, mvno_match_data fields to the APN.
                // The XML file does not change.
                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN mvno_type TEXT DEFAULT '';");
                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN mvno_match_data TEXT DEFAULT '';");
                oldVersion = 8 << 16 | 6;
            }
            if (oldVersion < (9 << 16 | 6)) {
                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN sub_id INTEGER DEFAULT " +
                        SubscriptionManager.INVALID_SUBSCRIPTION_ID + ";");
                oldVersion = 9 << 16 | 6;
            }
            if (oldVersion < (10 << 16 | 6)) {
                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN profile_id INTEGER DEFAULT 0;");
                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN modem_cognitive BOOLEAN DEFAULT 0;");
                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN max_conns INTEGER DEFAULT 0;");
                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN wait_time INTEGER DEFAULT 0;");
                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN max_conns_time INTEGER DEFAULT 0;");
                oldVersion = 10 << 16 | 6;
            }
            if (oldVersion < (11 << 16 | 6)) {
                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN mtu INTEGER DEFAULT 0;");
                oldVersion = 11 << 16 | 6;
            }
            if (oldVersion < (12 << 16 | 6)) {
                try {
                    // Try to update the siminfo table. It might not be there.
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE +
                            " ADD COLUMN " + SubscriptionManager.MCC + " INTEGER DEFAULT 0;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE +
                            " ADD COLUMN " + SubscriptionManager.MNC + " INTEGER DEFAULT 0;");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + SIMINFO_TABLE + " upgrade. " +
                                " The table will get created in onOpen.");
                    }
                }
                oldVersion = 12 << 16 | 6;
            }
            if (oldVersion < (13 << 16 | 6)) {
                try {
                    // Try to update the siminfo table. It might not be there.
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN " +
                            SubscriptionManager.CARRIER_NAME + " TEXT DEFAULT '';");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + SIMINFO_TABLE + " upgrade. " +
                                " The table will get created in onOpen.");
                    }
                }
                oldVersion = 13 << 16 | 6;
            }
            if (oldVersion < (14 << 16 | 6)) {
                Cursor c = db.query(CARRIERS_TABLE, null, null, null, null, null, null);
                if (VDBG) {
                    log("dbh.onUpgrade:- before upgrading total number of rows: " + c.getCount());
                }

                createCarriersTable(db, CARRIERS_TABLE_TMP);

                // Move entries from CARRIERS_TABLE to CARRIERS_TABLE_TMP
                if (c != null) {
                    String[] persistApnsForPlmns = mContext.getResources().getStringArray(
                            R.array.persist_apns_for_plmn);
                    while (c.moveToNext()) {
                        ContentValues cv = new ContentValues();
                        String val;

                        // Include only non-null values in cv so that null values can be replaced
                        // with default if there's a default value for the field

                        // String vals
                        val = getValueFromCursor(c, Telephony.Carriers.NAME);
                        if (val != null) {
                            cv.put(Telephony.Carriers.NAME, val);
                        }
                        val = getValueFromCursor(c, Telephony.Carriers.NUMERIC);
                        if (val != null) {
                            cv.put(Telephony.Carriers.NUMERIC, val);
                        }
                        val = getValueFromCursor(c, Telephony.Carriers.MCC);
                        if (val != null) {
                            cv.put(Telephony.Carriers.MCC, val);
                        }
                        val = getValueFromCursor(c, Telephony.Carriers.MNC);
                        if (val != null) {
                            cv.put(Telephony.Carriers.MNC, val);
                        }
                        val = getValueFromCursor(c, Telephony.Carriers.APN);
                        if (val != null) {
                            cv.put(Telephony.Carriers.APN, val);
                        }
                        val = getValueFromCursor(c, Telephony.Carriers.USER);
                        if (val != null) {
                            cv.put(Telephony.Carriers.USER, val);
                        }
                        val = getValueFromCursor(c, Telephony.Carriers.SERVER);
                        if (val != null) {
                            cv.put(Telephony.Carriers.SERVER, val);
                        }
                        val = getValueFromCursor(c, Telephony.Carriers.PASSWORD);
                        if (val != null) {
                            cv.put(Telephony.Carriers.PASSWORD, val);
                        }
                        val = getValueFromCursor(c, Telephony.Carriers.PROXY);
                        if (val != null) {
                            cv.put(Telephony.Carriers.PROXY, val);
                        }
                        val = getValueFromCursor(c, Telephony.Carriers.PORT);
                        if (val != null) {
                            cv.put(Telephony.Carriers.PORT, val);
                        }
                        val = getValueFromCursor(c, Telephony.Carriers.MMSPROXY);
                        if (val != null) {
                            cv.put(Telephony.Carriers.MMSPROXY, val);
                        }
                        val = getValueFromCursor(c, Telephony.Carriers.MMSPORT);
                        if (val != null) {
                            cv.put(Telephony.Carriers.MMSPORT, val);
                        }
                        val = getValueFromCursor(c, Telephony.Carriers.MMSC);
                        if (val != null) {
                            cv.put(Telephony.Carriers.MMSC, val);
                        }
                        val = getValueFromCursor(c, Telephony.Carriers.TYPE);
                        if (val != null) {
                            cv.put(Telephony.Carriers.TYPE, val);
                        }
                        val = getValueFromCursor(c, Telephony.Carriers.PROTOCOL);
                        if (val != null) {
                            cv.put(Telephony.Carriers.PROTOCOL, val);
                        }
                        val = getValueFromCursor(c, Telephony.Carriers.ROAMING_PROTOCOL);
                        if (val != null) {
                            cv.put(Telephony.Carriers.ROAMING_PROTOCOL, val);
                        }
                        val = getValueFromCursor(c, Telephony.Carriers.MVNO_TYPE);
                        if (val != null) {
                            cv.put(Telephony.Carriers.MVNO_TYPE, val);
                        }
                        val = getValueFromCursor(c, Telephony.Carriers.MVNO_MATCH_DATA);
                        if (val != null) {
                            cv.put(Telephony.Carriers.MVNO_MATCH_DATA, val);
                        }

                        // bool/int vals
                        val = getValueFromCursor(c, Telephony.Carriers.AUTH_TYPE);
                        if (val != null) {
                            cv.put(Telephony.Carriers.AUTH_TYPE, new Integer(val));
                        }
                        val = getValueFromCursor(c, Telephony.Carriers.CURRENT);
                        if (val != null) {
                            cv.put(Telephony.Carriers.CURRENT, new Integer(val));
                        }
                        val = getValueFromCursor(c, Telephony.Carriers.CARRIER_ENABLED);
                        if (val != null) {
                            cv.put(Telephony.Carriers.CARRIER_ENABLED, new Integer(val));
                        }
                        val = getValueFromCursor(c, Telephony.Carriers.BEARER);
                        if (val != null) {
                            cv.put(Telephony.Carriers.BEARER, new Integer(val));
                        }
                        val = getValueFromCursor(c, Telephony.Carriers.SUBSCRIPTION_ID);
                        if (val != null) {
                            cv.put(Telephony.Carriers.SUBSCRIPTION_ID, new Integer(val));
                        }
                        val = getValueFromCursor(c, Telephony.Carriers.PROFILE_ID);
                        if (val != null) {
                            cv.put(Telephony.Carriers.PROFILE_ID, new Integer(val));
                        }
                        val = getValueFromCursor(c, Telephony.Carriers.MODEM_COGNITIVE);
                        if (val != null) {
                            cv.put(Telephony.Carriers.MODEM_COGNITIVE, new Integer(val));
                        }
                        val = getValueFromCursor(c, Telephony.Carriers.MAX_CONNS);
                        if (val != null) {
                            cv.put(Telephony.Carriers.MAX_CONNS, new Integer(val));
                        }
                        val = getValueFromCursor(c, Telephony.Carriers.WAIT_TIME);
                        if (val != null) {
                            cv.put(Telephony.Carriers.WAIT_TIME, new Integer(val));
                        }
                        val = getValueFromCursor(c, Telephony.Carriers.MAX_CONNS_TIME);
                        if (val != null) {
                            cv.put(Telephony.Carriers.MAX_CONNS_TIME, new Integer(val));
                        }
                        val = getValueFromCursor(c, Telephony.Carriers.MTU);
                        if (val != null) {
                            cv.put(Telephony.Carriers.MTU, new Integer(val));
                        }

                        // New USER_EDITED column. Default value (USER_EDITED_UNTOUCHED) will
                        // be used for all rows except for non-mvno entries for plmns indicated
                        // by resource: those will be set to USER_EDITED_EDITED to preserve
                        // their current values
                        val = c.getString(c.getColumnIndex(Telephony.Carriers.NUMERIC));
                        for (String s : persistApnsForPlmns) {
                            if (!TextUtils.isEmpty(val) && val.equals(s) &&
                                    (!cv.containsKey(Telephony.Carriers.MVNO_TYPE) ||
                                            TextUtils.isEmpty(cv.getAsString(Telephony.Carriers.
                                                    MVNO_TYPE)))) {
                                cv.put(Telephony.Carriers.USER_EDITED, USER_EDITED_EDITED);
                                break;
                            }
                        }

                        try {
                            db.insertWithOnConflict(CARRIERS_TABLE_TMP, null, cv,
                                    SQLiteDatabase.CONFLICT_ABORT);
                            if (VDBG) {
                                log("dbh.onUpgrade: db.insert returned >= 0; insert "
                                        + "successful for cv " + cv);
                            }
                        } catch (SQLException e) {
                            if (VDBG) log("dbh.onUpgrade insertWithOnConflict exception " + e);
                            // Insertion failed which could be due to a conflict. Check if that is
                            // the case and merge the entries
                            Cursor oldRow = DatabaseHelper.selectConflictingRow(db,
                                    CARRIERS_TABLE_TMP, cv);
                            if (oldRow != null) {
                                ContentValues mergedValues = new ContentValues();
                                mergeFieldsAndUpdateDb(db, CARRIERS_TABLE_TMP, oldRow, cv,
                                        mergedValues, true);
                                oldRow.close();
                            }
                        }
                    }
                    c.close();
                }

                db.execSQL("DROP TABLE IF EXISTS " + CARRIERS_TABLE);

                db.execSQL("ALTER TABLE " + CARRIERS_TABLE_TMP + " rename to " + CARRIERS_TABLE +
                        ";");

                oldVersion = 14 << 16 | 6;

                if (VDBG) {
                    String[] proj = {"_id"};
                    c = db.query(CARRIERS_TABLE, proj, null, null, null, null, null);
                    log("dbh.onUpgrade:- after upgrading total number of rows: " + c.getCount());
                    c.close();
                    c = db.query(CARRIERS_TABLE, proj, "user_edited=" + USER_EDITED_UNTOUCHED, null,
                            null, null, null);
                    log("dbh.onUpgrade:- after upgrading total number of rows with user_edited="
                            + USER_EDITED_UNTOUCHED + ": " + c.getCount());
                    c.close();
                    c = db.query(CARRIERS_TABLE, proj, "user_edited!=" + USER_EDITED_UNTOUCHED,
                            null, null, null, null);
                    log("dbh.onUpgrade:- after upgrading total number of rows with user_edited!="
                            + USER_EDITED_UNTOUCHED + ": " + c.getCount());
                    c.close();
                }
            }
            if (DBG) {
                log("dbh.onUpgrade:- db=" + db + " oldV=" + oldVersion + " newV=" + newVersion);
            }
        }

        private String getValueFromCursor(Cursor c, String key) {
            String fromCursor = c.getString(c.getColumnIndex(key));
            return !TextUtils.isEmpty(fromCursor) ? fromCursor : null;
        }

        /**
         * Gets the next row of apn values.
         *
         * @param parser the parser
         * @return the row or null if it's not an apn
         */
        private ContentValues getRow(XmlPullParser parser) {
            if (!"apn".equals(parser.getName())) {
                return null;
            }

            ContentValues map = new ContentValues();

            String mcc = parser.getAttributeValue(null, "mcc");
            String mnc = parser.getAttributeValue(null, "mnc");
            String numeric = mcc + mnc;

            map.put(Telephony.Carriers.NUMERIC, numeric);
            map.put(Telephony.Carriers.MCC, mcc);
            map.put(Telephony.Carriers.MNC, mnc);
            map.put(Telephony.Carriers.NAME, parser.getAttributeValue(null, "carrier"));

            // do not add NULL to the map so that default values can be inserted in db
            String apn = parser.getAttributeValue(null, "apn");
            if (apn != null) {
                map.put(Telephony.Carriers.APN, apn);
            }

            String user = parser.getAttributeValue(null, "user");
            if (user != null) {
                map.put(Telephony.Carriers.USER, user);
            }

            String server = parser.getAttributeValue(null, "server");
            if (server != null) {
                map.put(Telephony.Carriers.SERVER, server);
            }

            String password = parser.getAttributeValue(null, "password");
            if (password != null) {
                map.put(Telephony.Carriers.PASSWORD, password);
            }

            String proxy = parser.getAttributeValue(null, "proxy");
            if (proxy != null) {
                map.put(Telephony.Carriers.PROXY, proxy);
            }

            String port = parser.getAttributeValue(null, "port");
            if (port != null) {
                map.put(Telephony.Carriers.PORT, port);
            }

            String mmsproxy = parser.getAttributeValue(null, "mmsproxy");
            if (mmsproxy != null) {
                map.put(Telephony.Carriers.MMSPROXY, mmsproxy);
            }

            String mmsport = parser.getAttributeValue(null, "mmsport");
            if (mmsport != null) {
                map.put(Telephony.Carriers.MMSPORT, mmsport);
            }

            String mmsc = parser.getAttributeValue(null, "mmsc");
            if (mmsc != null) {
                map.put(Telephony.Carriers.MMSC, mmsc);
            }

            String type = parser.getAttributeValue(null, "type");
            if (type != null) {
                map.put(Telephony.Carriers.TYPE, type);
            }

            String auth = parser.getAttributeValue(null, "authtype");
            if (auth != null) {
                map.put(Telephony.Carriers.AUTH_TYPE, Integer.parseInt(auth));
            }

            String protocol = parser.getAttributeValue(null, "protocol");
            if (protocol != null) {
                map.put(Telephony.Carriers.PROTOCOL, protocol);
            }

            String roamingProtocol = parser.getAttributeValue(null, "roaming_protocol");
            if (roamingProtocol != null) {
                map.put(Telephony.Carriers.ROAMING_PROTOCOL, roamingProtocol);
            }

            String carrierEnabled = parser.getAttributeValue(null, "carrier_enabled");
            if (carrierEnabled != null) {
                map.put(Telephony.Carriers.CARRIER_ENABLED, Boolean.parseBoolean(carrierEnabled));
            }

            String bearer = parser.getAttributeValue(null, "bearer");
            if (bearer != null) {
                map.put(Telephony.Carriers.BEARER, Integer.parseInt(bearer));
            }

            String mvno_type = parser.getAttributeValue(null, "mvno_type");
            if (mvno_type != null) {
                String mvno_match_data = parser.getAttributeValue(null, "mvno_match_data");
                if (mvno_match_data != null) {
                    map.put(Telephony.Carriers.MVNO_TYPE, mvno_type);
                    map.put(Telephony.Carriers.MVNO_MATCH_DATA, mvno_match_data);
                }
            }

            String profileId = parser.getAttributeValue(null, "profile_id");
            if (profileId != null) {
                map.put(Telephony.Carriers.PROFILE_ID, Integer.parseInt(profileId));
            }

            String modemCognitive = parser.getAttributeValue(null, "modem_cognitive");
            if (modemCognitive != null) {
                map.put(Telephony.Carriers.MODEM_COGNITIVE, Boolean.parseBoolean(modemCognitive));
            }

            String maxConns = parser.getAttributeValue(null, "max_conns");
            if (maxConns != null) {
                map.put(Telephony.Carriers.MAX_CONNS, Integer.parseInt(maxConns));
            }

            String waitTime = parser.getAttributeValue(null, "wait_time");
            if (waitTime != null) {
                map.put(Telephony.Carriers.WAIT_TIME, Integer.parseInt(waitTime));
            }

            String maxConnsTime = parser.getAttributeValue(null, "max_conns_time");
            if (maxConnsTime != null) {
                map.put(Telephony.Carriers.MAX_CONNS_TIME, Integer.parseInt(maxConnsTime));
            }

            String mtu = parser.getAttributeValue(null, "mtu");
            if (mtu != null) {
                map.put(Telephony.Carriers.MTU, Integer.parseInt(mtu));
            }

            return map;
        }

        /*
         * Loads apns from xml file into the database
         *
         * @param db the sqlite database to write to
         * @param parser the xml parser
         *
         */
        private void loadApns(SQLiteDatabase db, XmlPullParser parser) {
            if (parser != null) {
                try {
                    db.beginTransaction();
                    XmlUtils.nextElement(parser);
                    while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                        ContentValues row = getRow(parser);
                        if (row == null) {
                            throw new XmlPullParserException("Expected 'apn' tag", parser, null);
                        }
                        insertAddingDefaults(db, row);
                        XmlUtils.nextElement(parser);
                    }
                    db.setTransactionSuccessful();
                } catch (XmlPullParserException e) {
                    loge("Got XmlPullParserException while loading apns." + e);
                } catch (IOException e) {
                    loge("Got IOException while loading apns." + e);
                } catch (SQLException e) {
                    loge("Got SQLException while loading apns." + e);
                } finally {
                    db.endTransaction();
                }
            }
        }

        static public ContentValues setDefaultValue(ContentValues values) {
            if (!values.containsKey(Telephony.Carriers.SUBSCRIPTION_ID)) {
                int subId = SubscriptionManager.getDefaultSubId();
                values.put(Telephony.Carriers.SUBSCRIPTION_ID, subId);
            }

            return values;
        }

        private void insertAddingDefaults(SQLiteDatabase db, ContentValues row) {
            row = setDefaultValue(row);
            try {
                db.insertWithOnConflict(CARRIERS_TABLE, null, row,
                        SQLiteDatabase.CONFLICT_ABORT);
                if (VDBG) log("dbh.insertAddingDefaults: db.insert returned >= 0; insert " +
                        "successful for cv " + row);
            } catch (SQLException e) {
                if (VDBG) log("dbh.insertAddingDefaults: exception " + e);
                // Insertion failed which could be due to a conflict. Check if that is the case and
                // update user_edited field accordingly.
                // Search for the exact same entry and update user_edited field.
                // If it is USER_EDITED_EDITED change it to USER_EDITED_UNTOUCHED,
                // and if USER_EDITED_DELETED change it to USER_EDITED_DELETED_BUT_PRESENT.
                Cursor oldRow = selectConflictingRow(db, CARRIERS_TABLE, row);
                if (oldRow != null) {
                    // Update the row
                    ContentValues mergedValues = new ContentValues();
                    int user_edited = oldRow.getInt(oldRow.getColumnIndex(
                            Telephony.Carriers.USER_EDITED));
                    int old_user_edited = user_edited;
                    if (user_edited != USER_EDITED_UNTOUCHED) {
                        if (user_edited == USER_EDITED_EDITED) {
                            user_edited = USER_EDITED_UNTOUCHED;
                        } else if (user_edited == USER_EDITED_DELETED) {
                            // user_edited 3 indicates entry has been deleted by user but present in
                            // apn xml file.
                            user_edited = USER_EDITED_DELETED_BUT_PRESENT;
                        }
                        mergedValues.put(Telephony.Carriers.USER_EDITED, user_edited);
                    }

                    mergeFieldsAndUpdateDb(db, CARRIERS_TABLE, oldRow, row, mergedValues, false);

                    if (VDBG) log("dbh.insertAddingDefaults: old user_edited  = " + old_user_edited
                            + " new user_edited = " + user_edited);

                    oldRow.close();
                }
            }
        }

        static public void mergeFieldsAndUpdateDb(SQLiteDatabase db, String table, Cursor oldRow,
                                                  ContentValues newRow, ContentValues mergedValues,
                                                  boolean useOld) {
            if (newRow.containsKey(Telephony.Carriers.TYPE)) {
                // Merge the types
                String oldType = oldRow.getString(oldRow.getColumnIndex(Telephony.Carriers.TYPE));
                String newType = newRow.getAsString(Telephony.Carriers.TYPE);
                if (!oldType.equalsIgnoreCase(newType)) {
                    if (oldType.equals("") || newType.equals("")) {
                        newRow.put(Telephony.Carriers.TYPE, "");
                    } else {
                        // Merge the 2 types
                        String[] oldTypes = oldType.toLowerCase().split(",");
                        String[] newTypes = newType.toLowerCase().split(",");
                        ArrayList<String> mergedTypes = new ArrayList<String>();
                        mergedTypes.addAll(Arrays.asList(oldTypes));
                        for (String s : newTypes) {
                            if (!mergedTypes.contains(s.trim())) {
                                mergedTypes.add(s);
                            }
                        }
                        StringBuilder mergedType = new StringBuilder();
                        for (int i = 0; i < mergedTypes.size(); i++) {
                            mergedType.append((i == 0 ? "" : ",") + mergedTypes.get(i));
                        }
                        newRow.put(Telephony.Carriers.TYPE, mergedType.toString());
                    }
                }
                mergedValues.put(Telephony.Carriers.TYPE, newRow.getAsString(
                        Telephony.Carriers.TYPE));
            }

            if (!useOld) {
                mergedValues.putAll(newRow);
            }

            db.update(table, mergedValues, "_id=" + oldRow.getInt(oldRow.getColumnIndex("_id")),
                    null);
        }

        static public Cursor selectConflictingRow(SQLiteDatabase db, String table,
                                                  ContentValues row) {
            // Conflict is possible only when numeric, mnnc, mnc (fields without any default value)
            // are set in the new row
            if (row.containsKey(Telephony.Carriers.NUMERIC) ||
                    row.containsKey(Telephony.Carriers.MCC) ||
                    row.containsKey(Telephony.Carriers.MNC)) {
                loge("dbh.selectConflictingRow: called for non-conflicting row: " + row);
                return null;
            }

            String[] columns = { "_id", Telephony.Carriers.TYPE, Telephony.Carriers.USER_EDITED };
            String selection = "numeric=? AND mcc=? AND mnc=? AND apn=? AND proxy=? AND port=? "
                    + "AND mmsproxy=? AND mmsport=? AND mmsc=? AND carrier_enabled=? AND bearer=? "
                    + "AND mvno_type=? AND mvno_match_data=? AND profile_id=?";
            int i = 0;
            String[] selectionArgs = new String[14];
            selectionArgs[i++] = row.getAsString(Telephony.Carriers.NUMERIC);
            selectionArgs[i++] = row.getAsString(Telephony.Carriers.MCC);
            selectionArgs[i++] = row.getAsString(Telephony.Carriers.MNC);
            selectionArgs[i++] = row.containsKey(Telephony.Carriers.APN) ?
                    row.getAsString(Telephony.Carriers.APN) : "";
            selectionArgs[i++] = row.containsKey(Telephony.Carriers.PROXY) ?
                    row.getAsString(Telephony.Carriers.PROXY) : "";
            selectionArgs[i++] = row.containsKey(Telephony.Carriers.PORT) ?
                    row.getAsString(Telephony.Carriers.PORT) : "";
            selectionArgs[i++] = row.containsKey(Telephony.Carriers.MMSPROXY) ?
                    row.getAsString(Telephony.Carriers.MMSPROXY) : "";
            selectionArgs[i++] = row.containsKey(Telephony.Carriers.MMSPORT) ?
                    row.getAsString(Telephony.Carriers.MMSPORT) : "";
            selectionArgs[i++] = row.containsKey(Telephony.Carriers.MMSC) ?
                    row.getAsString(Telephony.Carriers.MMSC) : "";
            selectionArgs[i++] = row.containsKey(Telephony.Carriers.CARRIER_ENABLED) &&
                    (row.getAsString(Telephony.Carriers.CARRIER_ENABLED).equals("0") ||
                            row.getAsString(Telephony.Carriers.CARRIER_ENABLED).equals("false")) ?
                    "0" : "1";
            selectionArgs[i++] = row.containsKey(Telephony.Carriers.BEARER) ?
                    row.getAsString(Telephony.Carriers.BEARER) : "0";
            selectionArgs[i++] = row.containsKey(Telephony.Carriers.MVNO_TYPE) ?
                    row.getAsString(Telephony.Carriers.MVNO_TYPE) : "";
            selectionArgs[i++] = row.containsKey(Telephony.Carriers.MVNO_MATCH_DATA) ?
                    row.getAsString(Telephony.Carriers.MVNO_MATCH_DATA) : "";
            selectionArgs[i++] = row.containsKey(Telephony.Carriers.PROFILE_ID) ?
                    row.getAsString(Telephony.Carriers.PROFILE_ID) : "0";

            Cursor c = db.query(table, columns, selection, selectionArgs, null, null, null);

            if (c != null) {
                if (c.getCount() > 0) {
                    if (VDBG) log("dbh.selectConflictingRow: " + c.getCount() + " conflicting " +
                            "row(s) found");
                    if (c.moveToFirst()) {
                        return c;
                    } else {
                        loge("dbh.selectConflictingRow: moveToFirst() failed");
                    }
                } else {
                    loge("dbh.selectConflictingRow: " + c.getCount() + " matching row found for " +
                            "cv " + row);
                }
                c.close();
            } else {
                loge("dbh.selectConflictingRow: Error - c is null; no matching row found for " +
                        "cv " + row);
            }

            return null;
        }
    }

    @Override
    public boolean onCreate() {
        mOpenHelper = new DatabaseHelper(getContext());

        // Call getReadableDatabase() to make sure onUpgrade is called
        if (VDBG) log("onCreate: calling getReadableDatabase to trigger onUpgrade");
        SQLiteDatabase db = mOpenHelper.getReadableDatabase();

        // Update APN db on build update
        String newBuildId = SystemProperties.get("ro.build.id", null);
        if (!TextUtils.isEmpty(newBuildId)) {
            // Check if build id has changed
            SharedPreferences sp = getContext().getSharedPreferences(BUILD_ID_FILE,
                    Context.MODE_PRIVATE);
            String oldBuildId = sp.getString(RO_BUILD_ID, "");
            if (!newBuildId.equals(oldBuildId)) {
                if (DBG) log("onCreate: build id changed from " + oldBuildId + " to " +
                        newBuildId);
                updateApnDb();
            } else {
                if (VDBG) log("onCreate: build id did not change: " + oldBuildId);
            }
            sp.edit().putString(RO_BUILD_ID, newBuildId).apply();
        } else {
            if (VDBG) log("onCreate: newBuildId is empty");
        }

        if (VDBG) log("onCreate:- ret true");
        return true;
    }

    private void setPreferredApnId(Long id, int subId) {
        //todo: remove old PREF_FILE+subId SharedPreferences
        SharedPreferences sp = getContext().getSharedPreferences(
                PREF_FILE, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        editor.putLong(COLUMN_APN_ID + subId, id != null ? id.longValue() : -1);
        editor.apply();
    }

    private long getPreferredApnId(int subId) {
        SharedPreferences sp = getContext().getSharedPreferences(
                PREF_FILE, Context.MODE_PRIVATE);
        return sp.getLong(COLUMN_APN_ID + subId, -1);
    }

    private void deletePreferredApnId() {
        SharedPreferences sp = getContext().getSharedPreferences(
                PREF_FILE, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        editor.clear();
        editor.apply();
    }

    @Override
    public Cursor query(Uri url, String[] projectionIn, String selection,
            String[] selectionArgs, String sort) {
        if (VDBG) log("query: url=" + url + ", projectionIn=" + projectionIn + ", selection="
            + selection + "selectionArgs=" + selectionArgs + ", sort=" + sort);
        TelephonyManager mTelephonyManager =
                (TelephonyManager)getContext().getSystemService(Context.TELEPHONY_SERVICE);
        int subId = SubscriptionManager.getDefaultSubId();
        String subIdString;
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setStrict(true); // a little protection from injection attacks
        qb.setTables(CARRIERS_TABLE);

        int match = s_urlMatcher.match(url);
        switch (match) {
            case URL_TELEPHONY_USING_SUBID: {
                subIdString = url.getLastPathSegment();
                try {
                    subId = Integer.parseInt(subIdString);
                } catch (NumberFormatException e) {
                    loge("NumberFormatException" + e);
                    return null;
                }
                if (DBG) log("subIdString = " + subIdString + " subId = " + subId);
                qb.appendWhere("numeric = '" + mTelephonyManager.getSimOperator(subId)+"'");
                // FIXME alter the selection to pass subId
                // selection = selection + "and subId = "
            }
            // intentional fall through from above case
            // do nothing
            case URL_TELEPHONY: {
                break;
            }

            case URL_CURRENT_USING_SUBID: {
                subIdString = url.getLastPathSegment();
                try {
                    subId = Integer.parseInt(subIdString);
                } catch (NumberFormatException e) {
                    loge("NumberFormatException" + e);
                    return null;
                }
                if (DBG) log("subIdString = " + subIdString + " subId = " + subId);
                // FIXME alter the selection to pass subId
                // selection = selection + "and subId = "
            }
            //intentional fall through from above case
            case URL_CURRENT: {
                qb.appendWhere("current IS NOT NULL");
                // do not ignore the selection since MMS may use it.
                //selection = null;
                break;
            }

            case URL_ID: {
                qb.appendWhere("_id = " + url.getPathSegments().get(1));
                break;
            }

            case URL_PREFERAPN_USING_SUBID:
            case URL_PREFERAPN_NO_UPDATE_USING_SUBID: {
                subIdString = url.getLastPathSegment();
                try {
                    subId = Integer.parseInt(subIdString);
                } catch (NumberFormatException e) {
                    loge("NumberFormatException" + e);
                    return null;
                }
                if (DBG) log("subIdString = " + subIdString + " subId = " + subId);
            }
            //intentional fall through from above case
            case URL_PREFERAPN:
            case URL_PREFERAPN_NO_UPDATE: {
                qb.appendWhere("_id = " + getPreferredApnId(subId));
                break;
            }

            case URL_SIMINFO: {
                qb.setTables(SIMINFO_TABLE);
                break;
            }

            default: {
                return null;
            }
        }

        if (match != URL_SIMINFO) {
            if (projectionIn != null) {
                for (String column : projectionIn) {
                    if (Telephony.Carriers.TYPE.equals(column) ||
                            Telephony.Carriers.MMSC.equals(column) ||
                            Telephony.Carriers.MMSPROXY.equals(column) ||
                            Telephony.Carriers.MMSPORT.equals(column) ||
                            Telephony.Carriers.APN.equals(column)) {
                        // noop
                    } else {
                        checkPermission();
                        break;
                    }
                }
            } else {
                // null returns all columns, so need permission check
                checkPermission();
            }
        }

        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        Cursor ret = null;
        try {
            // Exclude entries marked deleted
            if (CARRIERS_TABLE.equals(qb.getTables())) {
                if (TextUtils.isEmpty(selection)) {
                    selection = "";
                } else {
                    selection += " and ";
                }
                selection += "user_edited!=" + USER_EDITED_DELETED + " and user_edited!="
                        + USER_EDITED_DELETED_BUT_PRESENT;
                if (VDBG) log("query: selection modified to " + selection);
            }
            ret = qb.query(db, projectionIn, selection, selectionArgs, null, null, sort);
        } catch (SQLException e) {
            loge("got exception when querying: " + e);
        }
        if (ret != null)
            ret.setNotificationUri(getContext().getContentResolver(), url);
        return ret;
    }

    @Override
    public String getType(Uri url)
    {
        switch (s_urlMatcher.match(url)) {
        case URL_TELEPHONY:
        case URL_TELEPHONY_USING_SUBID:
            return "vnd.android.cursor.dir/telephony-carrier";

        case URL_ID:
            return "vnd.android.cursor.item/telephony-carrier";

        case URL_PREFERAPN_USING_SUBID:
        case URL_PREFERAPN_NO_UPDATE_USING_SUBID:
        case URL_PREFERAPN:
        case URL_PREFERAPN_NO_UPDATE:
            return "vnd.android.cursor.item/telephony-carrier";

        default:
            throw new IllegalArgumentException("Unknown URL " + url);
        }
    }

    @Override
    public Uri insert(Uri url, ContentValues initialValues)
    {
        Uri result = null;
        int subId = SubscriptionManager.getDefaultSubId();

        checkPermission();

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int match = s_urlMatcher.match(url);
        boolean notify = false;
        switch (match)
        {
            case URL_TELEPHONY_USING_SUBID:
            {
                String subIdString = url.getLastPathSegment();
                try {
                    subId = Integer.parseInt(subIdString);
                } catch (NumberFormatException e) {
                    loge("NumberFormatException" + e);
                    return result;
                }
                if (DBG) log("subIdString = " + subIdString + " subId = " + subId);
            }
            //intentional fall through from above case

            case URL_TELEPHONY:
            {
                ContentValues values;
                if (initialValues != null) {
                    values = new ContentValues(initialValues);
                } else {
                    values = new ContentValues();
                }

                values = DatabaseHelper.setDefaultValue(values);
                values.put(Telephony.Carriers.USER_EDITED, USER_EDITED_EDITED);

                try {
                    // Replace on conflict so that if same APN is present in db with user_edited
                    // as USER_EDITED_UNTOUCHED or USER_EDITED_DELETED, it is replaced with
                    // user_edited USER_EDITED_EDITED
                    long rowID = db.insertWithOnConflict(CARRIERS_TABLE, null, values,
                            SQLiteDatabase.CONFLICT_REPLACE);
                    if (rowID >= 0) {
                        result = ContentUris.withAppendedId(Telephony.Carriers.CONTENT_URI, rowID);
                        notify = true;
                    }
                    if (VDBG) log("insert: inserted " + values.toString() + " rowID = " + rowID);
                } catch (SQLException e) {
                    log("insert: exception " + e);
                    // Insertion failed which could be due to a conflict. Check if that is the case
                    // and merge the entries
                    Cursor oldRow = DatabaseHelper.selectConflictingRow(db, CARRIERS_TABLE, values);
                    if (oldRow != null) {
                        ContentValues mergedValues = new ContentValues();
                        DatabaseHelper.mergeFieldsAndUpdateDb(db, CARRIERS_TABLE, oldRow, values,
                                mergedValues, false);
                        oldRow.close();
                    }
                }

                break;
            }

            case URL_CURRENT_USING_SUBID:
            {
                String subIdString = url.getLastPathSegment();
                try {
                    subId = Integer.parseInt(subIdString);
                } catch (NumberFormatException e) {
                    loge("NumberFormatException" + e);
                    return result;
                }
                if (DBG) log("subIdString = " + subIdString + " subId = " + subId);
                // FIXME use subId in the query
            }
            //intentional fall through from above case

            case URL_CURRENT:
            {
                // zero out the previous operator
                db.update(CARRIERS_TABLE, s_currentNullMap, "current!=0", null);

                String numeric = initialValues.getAsString(Telephony.Carriers.NUMERIC);
                int updated = db.update(CARRIERS_TABLE, s_currentSetMap,
                        "numeric = '" + numeric + "'", null);

                if (updated > 0)
                {
                    if (VDBG) log("Setting numeric '" + numeric + "' to be the current operator");
                }
                else
                {
                    loge("Failed setting numeric '" + numeric + "' to the current operator");
                }
                break;
            }

            case URL_PREFERAPN_USING_SUBID:
            case URL_PREFERAPN_NO_UPDATE_USING_SUBID:
            {
                String subIdString = url.getLastPathSegment();
                try {
                    subId = Integer.parseInt(subIdString);
                } catch (NumberFormatException e) {
                    loge("NumberFormatException" + e);
                    return result;
                }
                if (DBG) log("subIdString = " + subIdString + " subId = " + subId);
            }
            //intentional fall through from above case

            case URL_PREFERAPN:
            case URL_PREFERAPN_NO_UPDATE:
            {
                if (initialValues != null) {
                    if(initialValues.containsKey(COLUMN_APN_ID)) {
                        setPreferredApnId(initialValues.getAsLong(COLUMN_APN_ID), subId);
                    }
                }
                break;
            }

            case URL_SIMINFO: {
               long id = db.insert(SIMINFO_TABLE, null, initialValues);
               result = ContentUris.withAppendedId(SubscriptionManager.CONTENT_URI, id);
               break;
            }
        }

        if (notify) {
            getContext().getContentResolver().notifyChange(Telephony.Carriers.CONTENT_URI, null,
                    true, UserHandle.USER_ALL);
        }

        return result;
    }

    @Override
    public int delete(Uri url, String where, String[] whereArgs)
    {
        int count = 0;
        int subId = SubscriptionManager.getDefaultSubId();
        ContentValues cv = new ContentValues();
        cv.put(Telephony.Carriers.USER_EDITED, USER_EDITED_DELETED);

        checkPermission();

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int match = s_urlMatcher.match(url);
        switch (match)
        {
            case URL_TELEPHONY_USING_SUBID:
            {
                 String subIdString = url.getLastPathSegment();
                 try {
                     subId = Integer.parseInt(subIdString);
                 } catch (NumberFormatException e) {
                     loge("NumberFormatException" + e);
                     throw new IllegalArgumentException("Invalid subId " + url);
                 }
                 if (DBG) log("subIdString = " + subIdString + " subId = " + subId);
                // FIXME use subId in query
            }
            //intentional fall through from above case

            case URL_TELEPHONY:
            {
                // Mark as user deleted instead of deleting
                count = db.update(CARRIERS_TABLE, cv, where, whereArgs);
                break;
            }

            case URL_CURRENT_USING_SUBID: {
                String subIdString = url.getLastPathSegment();
                try {
                    subId = Integer.parseInt(subIdString);
                } catch (NumberFormatException e) {
                    loge("NumberFormatException" + e);
                    throw new IllegalArgumentException("Invalid subId " + url);
                }
                if (DBG) log("subIdString = " + subIdString + " subId = " + subId);
                // FIXME use subId in query
            }
            //intentional fall through from above case

            case URL_CURRENT:
            {
                // Mark as user deleted instead of deleting
                count = db.update(CARRIERS_TABLE, cv, where, whereArgs);
                break;
            }

            case URL_ID:
            {
                // Mark as user deleted instead of deleting
                count = db.update(CARRIERS_TABLE, cv, Telephony.Carriers._ID + "=?",
                        new String[] { url.getLastPathSegment() });
                break;
            }

            case URL_RESTOREAPN_USING_SUBID: {
                String subIdString = url.getLastPathSegment();
                try {
                    subId = Integer.parseInt(subIdString);
                } catch (NumberFormatException e) {
                    loge("NumberFormatException" + e);
                    throw new IllegalArgumentException("Invalid subId " + url);
                }
                if (DBG) log("subIdString = " + subIdString + " subId = " + subId);
                // FIXME use subId in query
            }
            case URL_RESTOREAPN: {
                count = 1;
                restoreDefaultAPN(subId);
                break;
            }

            case URL_PREFERAPN_USING_SUBID:
            case URL_PREFERAPN_NO_UPDATE_USING_SUBID: {
                String subIdString = url.getLastPathSegment();
                try {
                    subId = Integer.parseInt(subIdString);
                } catch (NumberFormatException e) {
                    loge("NumberFormatException" + e);
                    throw new IllegalArgumentException("Invalid subId " + url);
                }
                if (DBG) log("subIdString = " + subIdString + " subId = " + subId);
            }
            //intentional fall through from above case

            case URL_PREFERAPN:
            case URL_PREFERAPN_NO_UPDATE:
            {
                setPreferredApnId((long)-1, subId);
                if ((match == URL_PREFERAPN) || (match == URL_PREFERAPN_USING_SUBID)) count = 1;
                break;
            }

            case URL_SIMINFO: {
                count = db.delete(SIMINFO_TABLE, where, whereArgs);
                break;
            }

            case URL_UPDATE_DB: {
                updateApnDb();
                count = 1;
                break;
            }

            default: {
                throw new UnsupportedOperationException("Cannot delete that URL: " + url);
            }
        }

        if (count > 0) {
            getContext().getContentResolver().notifyChange(Telephony.Carriers.CONTENT_URI, null,
                    true, UserHandle.USER_ALL);
        }

        return count;
    }

    @Override
    public int update(Uri url, ContentValues values, String where, String[] whereArgs)
    {
        int count = 0;
        int uriType = URL_UNKNOWN;
        int subId = SubscriptionManager.getDefaultSubId();

        checkPermission();

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int match = s_urlMatcher.match(url);
        switch (match)
        {
            case URL_TELEPHONY_USING_SUBID:
            {
                 String subIdString = url.getLastPathSegment();
                 try {
                     subId = Integer.parseInt(subIdString);
                 } catch (NumberFormatException e) {
                     loge("NumberFormatException" + e);
                     throw new IllegalArgumentException("Invalid subId " + url);
                 }
                 if (DBG) log("subIdString = " + subIdString + " subId = " + subId);
                //FIXME use subId in the query
            }
            //intentional fall through from above case

            case URL_TELEPHONY:
            {
                values.put(Telephony.Carriers.USER_EDITED, USER_EDITED_EDITED);

                // Replace on conflict so that if same APN is present in db with user_edited
                // as USER_EDITED_UNTOUCHED or USER_EDITED_DELETED, it is replaced with
                // user_edited USER_EDITED_EDITED
                count = db.updateWithOnConflict(CARRIERS_TABLE, values, where, whereArgs,
                        SQLiteDatabase.CONFLICT_REPLACE);
                break;
            }

            case URL_CURRENT_USING_SUBID:
            {
                String subIdString = url.getLastPathSegment();
                try {
                    subId = Integer.parseInt(subIdString);
                } catch (NumberFormatException e) {
                    loge("NumberFormatException" + e);
                    throw new IllegalArgumentException("Invalid subId " + url);
                }
                if (DBG) log("subIdString = " + subIdString + " subId = " + subId);
                //FIXME use subId in the query
            }
            //intentional fall through from above case

            case URL_CURRENT:
            {
                values.put(Telephony.Carriers.USER_EDITED, USER_EDITED_EDITED);
                // Replace on conflict so that if same APN is present in db with user_edited
                // as USER_EDITED_UNTOUCHED or USER_EDITED_DELETED, it is replaced with
                // user_edited USER_EDITED_EDITED
                count = db.updateWithOnConflict(CARRIERS_TABLE, values, where, whereArgs,
                        SQLiteDatabase.CONFLICT_REPLACE);
                break;
            }

            case URL_ID:
            {
                if (where != null || whereArgs != null) {
                    throw new UnsupportedOperationException(
                            "Cannot update URL " + url + " with a where clause");
                }
                values.put(Telephony.Carriers.USER_EDITED, USER_EDITED_EDITED);
                // Replace on conflict so that if same APN is present in db with user_edited
                // as USER_EDITED_UNTOUCHED or USER_EDITED_DELETED, it is replaced with
                // user_edited USER_EDITED_EDITED
                count = db.updateWithOnConflict(CARRIERS_TABLE, values,
                        Telephony.Carriers._ID + "=?", new String[] { url.getLastPathSegment() },
                        SQLiteDatabase.CONFLICT_REPLACE);
                break;
            }

            case URL_PREFERAPN_USING_SUBID:
            case URL_PREFERAPN_NO_UPDATE_USING_SUBID:
            {
                String subIdString = url.getLastPathSegment();
                try {
                    subId = Integer.parseInt(subIdString);
                } catch (NumberFormatException e) {
                    loge("NumberFormatException" + e);
                    throw new IllegalArgumentException("Invalid subId " + url);
                }
                if (DBG) log("subIdString = " + subIdString + " subId = " + subId);
            }

            case URL_PREFERAPN:
            case URL_PREFERAPN_NO_UPDATE:
            {
                if (values != null) {
                    if (values.containsKey(COLUMN_APN_ID)) {
                        setPreferredApnId(values.getAsLong(COLUMN_APN_ID), subId);
                        if ((match == URL_PREFERAPN) ||
                                (match == URL_PREFERAPN_USING_SUBID)) {
                            count = 1;
                        }
                    }
                }
                break;
            }

            case URL_SIMINFO: {
                count = db.update(SIMINFO_TABLE, values, where, whereArgs);
                uriType = URL_SIMINFO;
                break;
            }

            default: {
                throw new UnsupportedOperationException("Cannot update that URL: " + url);
            }
        }

        if (count > 0) {
            switch (uriType) {
                case URL_SIMINFO:
                    getContext().getContentResolver().notifyChange(
                            SubscriptionManager.CONTENT_URI, null, true, UserHandle.USER_ALL);
                    break;
                default:
                    getContext().getContentResolver().notifyChange(
                            Telephony.Carriers.CONTENT_URI, null, true, UserHandle.USER_ALL);
            }
        }

        return count;
    }

    private void checkPermission() {
        int status = getContext().checkCallingOrSelfPermission(
                "android.permission.WRITE_APN_SETTINGS");
        if (status == PackageManager.PERMISSION_GRANTED) {
            return;
        }

        PackageManager packageManager = getContext().getPackageManager();
        String[] packages = packageManager.getPackagesForUid(Binder.getCallingUid());

        TelephonyManager telephonyManager =
                (TelephonyManager) getContext().getSystemService(Context.TELEPHONY_SERVICE);
        for (String pkg : packages) {
            if (telephonyManager.checkCarrierPrivilegesForPackage(pkg) ==
                    TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS) {
                return;
            }
        }
        throw new SecurityException("No permission to write APN settings");
    }

    private DatabaseHelper mOpenHelper;

    private void restoreDefaultAPN(int subId) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        try {
            db.delete(CARRIERS_TABLE, null, null);
        } catch (SQLException e) {
            loge("got exception when deleting to restore: " + e);
        }
        setPreferredApnId((long)-1, subId);
        mOpenHelper.initDatabase(db);
    }

    private void updateApnDb() {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        try {
            if (VDBG) log("updateApnDb: deleting user_edited=USER_EDITED_UNTOUCHED entries");
            db.delete(CARRIERS_TABLE, "user_edited=" + USER_EDITED_UNTOUCHED, null);
        } catch (SQLException e) {
            loge("got exception when deleting to update: " + e);
        }

        // Delete preferred APN for all subIds
        long currentPreferredApnId = getPreferredApnId(SubscriptionManager.getDefaultSubId());
        Cursor c = db.query(CARRIERS_TABLE, null, "_id=" + currentPreferredApnId, null, null, null,
                null);
        if (VDBG) {
            log("updateApnDb: currentPreferredApnId = " + currentPreferredApnId);
            if (c != null && c.getCount() != 0) {
                c.moveToNext();
                log("updateApnDb: NAME=" + c.getString(c.getColumnIndex(Telephony.Carriers.NAME)));
            }
        }
        deletePreferredApnId();

        mOpenHelper.initDatabase(db);

        // TODO: restore preference APN ID
        c.close();
    }

    /**
     * Log with debug
     *
     * @param s is string log
     */
    private static void log(String s) {
        Log.d(TAG, s);
    }

    private static void loge(String s) {
        Log.e(TAG, s);
    }
}
