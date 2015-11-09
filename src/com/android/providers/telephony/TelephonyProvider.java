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
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
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
import java.util.List;
import java.util.Map;

public class TelephonyProvider extends ContentProvider
{
    private static final String DATABASE_NAME = "telephony.db";
    private static final boolean DBG = true;
    private static final boolean VDBG = false; // STOPSHIP if true

    private static final int DATABASE_VERSION = 17 << 16;
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

    private static final String PREF_FILE_FULL_APN = "preferred-full-apn";
    private static final String DB_VERSION_KEY = "version";

    private static final String BUILD_ID_FILE = "build-id";
    private static final String RO_BUILD_ID = "ro_build_id";

    private static final String PARTNER_APNS_PATH = "etc/apns-conf.xml";
    private static final String OEM_APNS_PATH = "telephony/apns-conf.xml";
    private static final String OTA_UPDATED_APNS_PATH = "misc/apns-conf.xml";
    private static final String OLD_APNS_PATH = "etc/old-apns-conf.xml";

    private static final UriMatcher s_urlMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    private static final ContentValues s_currentNullMap;
    private static final ContentValues s_currentSetMap;

    private static final int INVALID_APN_ID = -1;
    private static final List<String> CARRIERS_UNIQUE_FIELDS = new ArrayList<String>();

    static {
        // Columns not included in UNIQUE constraint: name, current, edited, user, server, password,
        // authtype, type, protocol, roaming_protocol, sub_id, modem_cognitive, max_conns, wait_time,
        // max_conns_time, mtu, bearer_bitmask, user_visible
        CARRIERS_UNIQUE_FIELDS.add(Telephony.Carriers.NUMERIC);
        CARRIERS_UNIQUE_FIELDS.add(Telephony.Carriers.MCC);
        CARRIERS_UNIQUE_FIELDS.add(Telephony.Carriers.MNC);
        CARRIERS_UNIQUE_FIELDS.add(Telephony.Carriers.APN);
        CARRIERS_UNIQUE_FIELDS.add(Telephony.Carriers.PROXY);
        CARRIERS_UNIQUE_FIELDS.add(Telephony.Carriers.PORT);
        CARRIERS_UNIQUE_FIELDS.add(Telephony.Carriers.MMSPROXY);
        CARRIERS_UNIQUE_FIELDS.add(Telephony.Carriers.MMSPORT);
        CARRIERS_UNIQUE_FIELDS.add(Telephony.Carriers.MMSC);
        CARRIERS_UNIQUE_FIELDS.add(Telephony.Carriers.CARRIER_ENABLED);
        CARRIERS_UNIQUE_FIELDS.add(Telephony.Carriers.BEARER);
        CARRIERS_UNIQUE_FIELDS.add(Telephony.Carriers.MVNO_TYPE);
        CARRIERS_UNIQUE_FIELDS.add(Telephony.Carriers.MVNO_MATCH_DATA);
        CARRIERS_UNIQUE_FIELDS.add(Telephony.Carriers.PROFILE_ID);
    }

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
                    + SubscriptionManager.MNC + " INTEGER DEFAULT 0,"
                    + SubscriptionManager.CB_EXTREME_THREAT_ALERT + " INTEGER DEFAULT 1,"
                    + SubscriptionManager.CB_SEVERE_THREAT_ALERT + " INTEGER DEFAULT 1,"
                    + SubscriptionManager.CB_AMBER_ALERT + " INTEGER DEFAULT 1,"
                    + SubscriptionManager.CB_EMERGENCY_ALERT + " INTEGER DEFAULT 1,"
                    + SubscriptionManager.CB_ALERT_SOUND_DURATION + " INTEGER DEFAULT 4,"
                    + SubscriptionManager.CB_ALERT_REMINDER_INTERVAL + " INTEGER DEFAULT 0,"
                    + SubscriptionManager.CB_ALERT_VIBRATE + " INTEGER DEFAULT 1,"
                    + SubscriptionManager.CB_ALERT_SPEECH + " INTEGER DEFAULT 1,"
                    + SubscriptionManager.CB_ETWS_TEST_ALERT + " INTEGER DEFAULT 0,"
                    + SubscriptionManager.CB_CHANNEL_50_ALERT + " INTEGER DEFAULT 1,"
                    + SubscriptionManager.CB_CMAS_TEST_ALERT + " INTEGER DEFAULT 0,"
                    + SubscriptionManager.CB_OPT_OUT_DIALOG + " INTEGER DEFAULT 1"
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
                    "bearer_bitmask INTEGER DEFAULT 0," +
                    "mvno_type TEXT DEFAULT ''," +
                    "mvno_match_data TEXT DEFAULT ''," +
                    "sub_id INTEGER DEFAULT " + SubscriptionManager.INVALID_SUBSCRIPTION_ID + "," +
                    "profile_id INTEGER DEFAULT 0," +
                    "modem_cognitive BOOLEAN DEFAULT 0," +
                    "max_conns INTEGER DEFAULT 0," +
                    "wait_time INTEGER DEFAULT 0," +
                    "max_conns_time INTEGER DEFAULT 0," +
                    "mtu INTEGER DEFAULT 0," +
                    "edited INTEGER DEFAULT " + Telephony.Carriers.UNEDITED + "," +
                    "user_visible BOOLEAN DEFAULT 1," +
                    // Uniqueness collisions are used to trigger merge code so if a field is listed
                    // here it means we will accept both (user edited + new apn_conf definition)
                    // Columns not included in UNIQUE constraint: name, current, edited,
                    // user, server, password, authtype, type, protocol, roaming_protocol, sub_id,
                    // modem_cognitive, max_conns, wait_time, max_conns_time, mtu, bearer_bitmask,
                    // user_visible
                    "UNIQUE (numeric, mcc, mnc, apn, proxy, port, mmsproxy, mmsport, mmsc," +
                    "carrier_enabled, bearer, mvno_type, mvno_match_data, profile_id));");
            if (DBG) log("dbh.createCarriersTable:-");
        }

        /**
         *  This function adds APNs from xml file(s) to db. The db may or may not be empty to begin
         *  with.
         */
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
            File updatedConfFile = new File(Environment.getDataDirectory(), OTA_UPDATED_APNS_PATH);
            confFile = getNewerFile(confFile, oemConfFile);
            confFile = getNewerFile(confFile, updatedConfFile);

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
                // Get rid of user/carrier deleted entries that are not present in apn xml file.
                // Those entries have edited value USER_DELETED/CARRIER_DELETED.
                if (VDBG) {
                    log("initDatabase: deleting USER_DELETED and replacing "
                            + "DELETED_BUT_PRESENT_IN_XML with DELETED");
                }

                // Delete USER_DELETED
                db.delete(CARRIERS_TABLE, "edited=" + Telephony.Carriers.USER_DELETED + " or " +
                        "edited=" + Telephony.Carriers.CARRIER_DELETED, null);

                // Change USER_DELETED_BUT_PRESENT_IN_XML to USER_DELETED
                ContentValues cv = new ContentValues();
                cv.put(Telephony.Carriers.EDITED, Telephony.Carriers.USER_DELETED);
                db.update(CARRIERS_TABLE, cv, "edited=" + Telephony.Carriers.USER_DELETED_BUT_PRESENT_IN_XML,
                        null);

                // Change CARRIER_DELETED_BUT_PRESENT_IN_XML to CARRIER_DELETED
                cv = new ContentValues();
                cv.put(Telephony.Carriers.EDITED, Telephony.Carriers.CARRIER_DELETED);
                db.update(CARRIERS_TABLE, cv, "edited=" + Telephony.Carriers.CARRIER_DELETED_BUT_PRESENT_IN_XML,
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

        private File getNewerFile(File sysApnFile, File altApnFile) {
            if (altApnFile.exists()) {
                // Alternate file exists. Use the newer one.
                long altFileTime = altApnFile.lastModified();
                long currFileTime = sysApnFile.lastModified();
                if (DBG) log("APNs Timestamp: altFileTime = " + altFileTime + " currFileTime = "
                        + currFileTime);

                // To get the latest version from OEM or System image
                if (altFileTime > currFileTime) {
                    if (DBG) log("APNs Timestamp: Alternate image " + altApnFile.getPath() +
                            " is greater than System image");
                    return altApnFile;
                }
            } else {
                // No Apn in alternate image, so load it from system image.
                if (DBG) log("No APNs in OEM image = " + altApnFile.getPath() +
                        " Load APNs from system image");
            }
            return sysApnFile;
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
                // Do nothing. This is to avoid recreating table twice. Table is anyway recreated
                // for next version and that takes care of updates for this version as well.
                // This version added a new column user_edited to carriers db.
            }
            if (oldVersion < (15 << 16 | 6)) {
                // Most devices should be upgrading from version 13. On upgrade new db will be
                // populated from the xml included in OTA but user and carrier edited/added entries
                // need to be preserved. This new version also adds new columns EDITED and
                // BEARER_BITMASK to the table. Upgrade steps from version 13 are:
                // 1. preserve user and carrier added/edited APNs (by comparing against
                // old-apns-conf.xml included in OTA) - done in preserveUserAndCarrierApns()
                // 2. add new columns EDITED and BEARER_BITMASK (create a new table for that) - done
                // in createCarriersTable()
                // 3. copy over preserved APNs from old table to new table - done in
                // copyPreservedApnsToNewTable()
                // The only exception if upgrading from version 14 is that EDITED field is already
                // present (but is called USER_EDITED)
                /*********************************************************************************
                 * IMPORTANT NOTE: SINCE CARRIERS TABLE IS RECREATED HERE, IT WILL BE THE LATEST
                 * VERSION AFTER THIS. AS A RESULT ANY SUBSEQUENT UPDATES TO THE TABLE WILL FAIL
                 * (DUE TO COLUMN-ALREADY-EXISTS KIND OF EXCEPTION). ALL SUBSEQUENT UPDATES SHOULD
                 * HANDLE THAT GRACEFULLY.
                 *********************************************************************************/
                Cursor c;
                String[] proj = {"_id"};
                if (VDBG) {
                    c = db.query(CARRIERS_TABLE, proj, null, null, null, null, null);
                    log("dbh.onUpgrade:- before upgrading total number of rows: " + c.getCount());
                }

                // Compare db with old apns xml file so that any user or carrier edited/added
                // entries can be preserved across upgrade
                preserveUserAndCarrierApns(db);

                c = db.query(CARRIERS_TABLE, null, null, null, null, null, null);

                if (VDBG) {
                    log("dbh.onUpgrade:- after preserveUserAndCarrierApns() total number of " +
                            "rows: " + ((c == null) ? 0 : c.getCount()));
                }

                createCarriersTable(db, CARRIERS_TABLE_TMP);

                copyPreservedApnsToNewTable(db, c);
                c.close();

                db.execSQL("DROP TABLE IF EXISTS " + CARRIERS_TABLE);

                db.execSQL("ALTER TABLE " + CARRIERS_TABLE_TMP + " rename to " + CARRIERS_TABLE +
                        ";");

                if (VDBG) {
                    c = db.query(CARRIERS_TABLE, proj, null, null, null, null, null);
                    log("dbh.onUpgrade:- after upgrading total number of rows: " + c.getCount());
                    c.close();
                    c = db.query(CARRIERS_TABLE, proj, "edited=" + Telephony.Carriers.UNEDITED,
                            null, null, null, null);
                    log("dbh.onUpgrade:- after upgrading total number of rows with edited="
                            + Telephony.Carriers.UNEDITED + ": " + c.getCount());
                    c.close();
                    c = db.query(CARRIERS_TABLE, proj, "edited!=" + Telephony.Carriers.UNEDITED,
                            null, null, null, null);
                    log("dbh.onUpgrade:- after upgrading total number of rows with edited!="
                            + Telephony.Carriers.UNEDITED + ": " + c.getCount());
                    c.close();
                }

                oldVersion = 15 << 16 | 6;
            }
            if (oldVersion < (16 << 16 | 6)) {
                try {
                    // Try to update the siminfo table. It might not be there.
                    // These columns may already be present in which case execSQL will throw an
                    // exception
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + SubscriptionManager.CB_EXTREME_THREAT_ALERT + " INTEGER DEFAULT 1;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + SubscriptionManager.CB_SEVERE_THREAT_ALERT + " INTEGER DEFAULT 1;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + SubscriptionManager.CB_AMBER_ALERT + " INTEGER DEFAULT 1;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + SubscriptionManager.CB_EMERGENCY_ALERT + " INTEGER DEFAULT 1;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + SubscriptionManager.CB_ALERT_SOUND_DURATION + " INTEGER DEFAULT 4;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + SubscriptionManager.CB_ALERT_REMINDER_INTERVAL + " INTEGER DEFAULT 0;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + SubscriptionManager.CB_ALERT_VIBRATE + " INTEGER DEFAULT 1;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + SubscriptionManager.CB_ALERT_SPEECH + " INTEGER DEFAULT 1;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + SubscriptionManager.CB_ETWS_TEST_ALERT + " INTEGER DEFAULT 0;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + SubscriptionManager.CB_CHANNEL_50_ALERT + " INTEGER DEFAULT 1;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + SubscriptionManager.CB_CMAS_TEST_ALERT + " INTEGER DEFAULT 0;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + SubscriptionManager.CB_OPT_OUT_DIALOG + " INTEGER DEFAULT 1;");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + SIMINFO_TABLE + " upgrade. " +
                                " The table will get created in onOpen.");
                    }
                }
                oldVersion = 16 << 16 | 6;
            }
            if (oldVersion < (17 << 16 | 6)) {
                Cursor c = null;
                try {
                    c = db.query(CARRIERS_TABLE, null, null, null, null, null, null,
                            String.valueOf(1));
                    if (c == null || c.getColumnIndex(Telephony.Carriers.USER_VISIBLE) == -1) {
                        db.execSQL("ALTER TABLE " + CARRIERS_TABLE + " ADD COLUMN " +
                                Telephony.Carriers.USER_VISIBLE + " BOOLEAN DEFAULT 1;");
                    } else {
                        if (DBG) {
                            log("onUpgrade skipping " + CARRIERS_TABLE + " upgrade.  Column " +
                                    Telephony.Carriers.USER_VISIBLE + " already exists.");
                        }
                    }
                } finally {
                    if (c != null) {
                        c.close();
                    }
                }
                oldVersion = 17 << 16 | 6;
            }
            if (DBG) {
                log("dbh.onUpgrade:- db=" + db + " oldV=" + oldVersion + " newV=" + newVersion);
            }
        }

        private void preserveUserAndCarrierApns(SQLiteDatabase db) {
            if (VDBG) log("preserveUserAndCarrierApns");
            XmlPullParser confparser;
            File confFile = new File(Environment.getRootDirectory(), OLD_APNS_PATH);
            FileReader confreader = null;
            try {
                confreader = new FileReader(confFile);
                confparser = Xml.newPullParser();
                confparser.setInput(confreader);
                XmlUtils.beginDocument(confparser, "apns");

                deleteMatchingApns(db, confparser);
            } catch (FileNotFoundException e) {
                // This function is called only when upgrading db to version 15. Details about the
                // upgrade are mentioned in onUpgrade(). This file missing means user/carrier added
                // APNs cannot be preserved. Throw an exception so that OEMs know they need to
                // include old apns file for comparison.
                loge("preserveUserAndCarrierApns: FileNotFoundException");
                throw new RuntimeException("preserveUserAndCarrierApns: " + OLD_APNS_PATH +
                        " not found. It is needed to upgrade from older versions of APN " +
                        "db while preserving user/carrier added/edited entries.");
            } catch (Exception e) {
                loge("preserveUserAndCarrierApns: Exception while parsing '" +
                        confFile.getAbsolutePath() + "'" + e);
            } finally {
                if (confreader != null) {
                    try {
                        confreader.close();
                    } catch (IOException e) {
                        // do nothing
                    }
                }
            }
        }

        private void deleteMatchingApns(SQLiteDatabase db, XmlPullParser parser) {
            if (VDBG) log("deleteMatchingApns");
            if (parser != null) {
                if (VDBG) log("deleteMatchingApns: parser != null");
                try {
                    XmlUtils.nextElement(parser);
                    while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                        ContentValues row = getRow(parser);
                        if (row == null) {
                            throw new XmlPullParserException("Expected 'apn' tag", parser, null);
                        }
                        deleteRow(db, row);
                        XmlUtils.nextElement(parser);
                    }
                } catch (XmlPullParserException e) {
                    loge("deleteMatchingApns: Got XmlPullParserException while deleting apns." + e);
                } catch (IOException e) {
                    loge("deleteMatchingApns: Got IOException while deleting apns." + e);
                } catch (SQLException e) {
                    loge("deleteMatchingApns: Got SQLException while deleting apns." + e);
                }
            }
        }

        private void deleteRow(SQLiteDatabase db, ContentValues values) {
            if (VDBG) log("deleteRow");
            String where = "numeric=? and mcc=? and mnc=? and name=? and " +
                    "(apn=? or apn is null) and " +
                    "(user=? or user is null) and (server=? or server is null) and " +
                    "(password=? or password is null) and (proxy=? or proxy is null) and " +
                    "(port=? or port is null) and (mmsproxy=? or mmsproxy is null) and " +
                    "(mmsport=? or mmsport is null) and (mmsc=? or mmsc is null) and " +
                    "(authtype=? or authtype is null) and (type=? or type is null) and " +
                    "(protocol=? or protocol is null) and " +
                    "(roaming_protocol=? or roaming_protocol is null) and " +
                    "(carrier_enabled=? or carrier_enabled=? or carrier_enabled is null) and " +
                    "(bearer=? or bearer is null) and (mvno_type=? or mvno_type is null) and " +
                    "(mvno_match_data=? or mvno_match_data is null) and " +
                    "(profile_id=? or profile_id is null) and " +
                    "(modem_cognitive=? or modem_cognitive=? or modem_cognitive is null) and " +
                    "(max_conns=? or max_conns is null) and " +
                    "(wait_time=? or wait_time is null) and " +
                    "(max_conns_time=? or max_conns_time is null) and (mtu=? or mtu is null)";
            String[] whereArgs = new String[29];
            int i = 0;
            whereArgs[i++] = values.getAsString(Telephony.Carriers.NUMERIC);
            whereArgs[i++] = values.getAsString(Telephony.Carriers.MCC);
            whereArgs[i++] = values.getAsString(Telephony.Carriers.MNC);
            whereArgs[i++] = values.getAsString(Telephony.Carriers.NAME);
            whereArgs[i++] = values.containsKey(Telephony.Carriers.APN) ?
                    values.getAsString(Telephony.Carriers.APN) : "";
            whereArgs[i++] = values.containsKey(Telephony.Carriers.USER) ?
                    values.getAsString(Telephony.Carriers.USER) : "";
            whereArgs[i++] = values.containsKey(Telephony.Carriers.SERVER) ?
                    values.getAsString(Telephony.Carriers.SERVER) : "";
            whereArgs[i++] = values.containsKey(Telephony.Carriers.PASSWORD) ?
                    values.getAsString(Telephony.Carriers.PASSWORD) : "";
            whereArgs[i++] = values.containsKey(Telephony.Carriers.PROXY) ?
                    values.getAsString(Telephony.Carriers.PROXY) : "";
            whereArgs[i++] = values.containsKey(Telephony.Carriers.PORT) ?
                    values.getAsString(Telephony.Carriers.PORT) : "";
            whereArgs[i++] = values.containsKey(Telephony.Carriers.MMSPROXY) ?
                    values.getAsString(Telephony.Carriers.MMSPROXY) : "";
            whereArgs[i++] = values.containsKey(Telephony.Carriers.MMSPORT) ?
                    values.getAsString(Telephony.Carriers.MMSPORT) : "";
            whereArgs[i++] = values.containsKey(Telephony.Carriers.MMSC) ?
                    values.getAsString(Telephony.Carriers.MMSC) : "";
            whereArgs[i++] = values.containsKey(Telephony.Carriers.AUTH_TYPE) ?
                    values.getAsString(Telephony.Carriers.AUTH_TYPE) : "-1";
            whereArgs[i++] = values.containsKey(Telephony.Carriers.TYPE) ?
                    values.getAsString(Telephony.Carriers.TYPE) : "";
            whereArgs[i++] = values.containsKey(Telephony.Carriers.PROTOCOL) ?
                    values.getAsString(Telephony.Carriers.PROTOCOL) : "IP";
            whereArgs[i++] = values.containsKey(Telephony.Carriers.ROAMING_PROTOCOL) ?
                    values.getAsString(Telephony.Carriers.ROAMING_PROTOCOL) : "IP";

            if (values.containsKey(Telephony.Carriers.CARRIER_ENABLED) &&
                    (values.getAsString(Telephony.Carriers.CARRIER_ENABLED).
                            equalsIgnoreCase("false") ||
                            values.getAsString(Telephony.Carriers.CARRIER_ENABLED).equals("0"))) {
                whereArgs[i++] = "false";
                whereArgs[i++] = "0";
            } else {
                whereArgs[i++] = "true";
                whereArgs[i++] = "1";
            }

            whereArgs[i++] = values.containsKey(Telephony.Carriers.BEARER) ?
                    values.getAsString(Telephony.Carriers.BEARER) : "0";
            whereArgs[i++] = values.containsKey(Telephony.Carriers.MVNO_TYPE) ?
                    values.getAsString(Telephony.Carriers.MVNO_TYPE) : "";
            whereArgs[i++] = values.containsKey(Telephony.Carriers.MVNO_MATCH_DATA) ?
                    values.getAsString(Telephony.Carriers.MVNO_MATCH_DATA) : "";
            whereArgs[i++] = values.containsKey(Telephony.Carriers.PROFILE_ID) ?
                    values.getAsString(Telephony.Carriers.PROFILE_ID) : "0";

            if (values.containsKey(Telephony.Carriers.MODEM_COGNITIVE) &&
                    (values.getAsString(Telephony.Carriers.MODEM_COGNITIVE).
                            equalsIgnoreCase("true") ||
                            values.getAsString(Telephony.Carriers.MODEM_COGNITIVE).equals("1"))) {
                whereArgs[i++] = "true";
                whereArgs[i++] = "1";
            } else {
                whereArgs[i++] = "false";
                whereArgs[i++] = "0";
            }

            whereArgs[i++] = values.containsKey(Telephony.Carriers.MAX_CONNS) ?
                    values.getAsString(Telephony.Carriers.MAX_CONNS) : "0";
            whereArgs[i++] = values.containsKey(Telephony.Carriers.WAIT_TIME) ?
                    values.getAsString(Telephony.Carriers.WAIT_TIME) : "0";
            whereArgs[i++] = values.containsKey(Telephony.Carriers.MAX_CONNS_TIME) ?
                    values.getAsString(Telephony.Carriers.MAX_CONNS_TIME) : "0";
            whereArgs[i++] = values.containsKey(Telephony.Carriers.MTU) ?
                    values.getAsString(Telephony.Carriers.MTU) : "0";

            if (VDBG) {
                log("deleteRow: where: " + where);

                StringBuilder builder = new StringBuilder();
                for (String s : whereArgs) {
                    builder.append(s + ", ");
                }

                log("deleteRow: whereArgs: " + builder.toString());
            }
            db.delete(CARRIERS_TABLE, where, whereArgs);
        }

        private void copyPreservedApnsToNewTable(SQLiteDatabase db, Cursor c) {
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
                    getStringValueFromCursor(cv, c, Telephony.Carriers.NAME);
                    getStringValueFromCursor(cv, c, Telephony.Carriers.NUMERIC);
                    getStringValueFromCursor(cv, c, Telephony.Carriers.MCC);
                    getStringValueFromCursor(cv, c, Telephony.Carriers.MNC);
                    getStringValueFromCursor(cv, c, Telephony.Carriers.APN);
                    getStringValueFromCursor(cv, c, Telephony.Carriers.USER);
                    getStringValueFromCursor(cv, c, Telephony.Carriers.SERVER);
                    getStringValueFromCursor(cv, c, Telephony.Carriers.PASSWORD);
                    getStringValueFromCursor(cv, c, Telephony.Carriers.PROXY);
                    getStringValueFromCursor(cv, c, Telephony.Carriers.PORT);
                    getStringValueFromCursor(cv, c, Telephony.Carriers.MMSPROXY);
                    getStringValueFromCursor(cv, c, Telephony.Carriers.MMSPORT);
                    getStringValueFromCursor(cv, c, Telephony.Carriers.MMSC);
                    getStringValueFromCursor(cv, c, Telephony.Carriers.TYPE);
                    getStringValueFromCursor(cv, c, Telephony.Carriers.PROTOCOL);
                    getStringValueFromCursor(cv, c, Telephony.Carriers.ROAMING_PROTOCOL);
                    getStringValueFromCursor(cv, c, Telephony.Carriers.MVNO_TYPE);
                    getStringValueFromCursor(cv, c, Telephony.Carriers.MVNO_MATCH_DATA);

                    // bool/int vals
                    getIntValueFromCursor(cv, c, Telephony.Carriers.AUTH_TYPE);
                    getIntValueFromCursor(cv, c, Telephony.Carriers.CURRENT);
                    getIntValueFromCursor(cv, c, Telephony.Carriers.CARRIER_ENABLED);
                    getIntValueFromCursor(cv, c, Telephony.Carriers.BEARER);
                    getIntValueFromCursor(cv, c, Telephony.Carriers.SUBSCRIPTION_ID);
                    getIntValueFromCursor(cv, c, Telephony.Carriers.PROFILE_ID);
                    getIntValueFromCursor(cv, c, Telephony.Carriers.MODEM_COGNITIVE);
                    getIntValueFromCursor(cv, c, Telephony.Carriers.MAX_CONNS);
                    getIntValueFromCursor(cv, c, Telephony.Carriers.WAIT_TIME);
                    getIntValueFromCursor(cv, c, Telephony.Carriers.MAX_CONNS_TIME);
                    getIntValueFromCursor(cv, c, Telephony.Carriers.MTU);

                    // Change bearer to a bitmask
                    String bearerStr = c.getString(c.getColumnIndex(Telephony.Carriers.BEARER));
                    if (!TextUtils.isEmpty(bearerStr)) {
                        int bearer_bitmask = ServiceState.getBitmaskForTech(
                                Integer.parseInt(bearerStr));
                        cv.put(Telephony.Carriers.BEARER_BITMASK, bearer_bitmask);
                    }

                    int userEditedColumnIdx = c.getColumnIndex("user_edited");
                    if (userEditedColumnIdx != -1) {
                        String user_edited = c.getString(userEditedColumnIdx);
                        if (!TextUtils.isEmpty(user_edited)) {
                            cv.put(Telephony.Carriers.EDITED, new Integer(user_edited));
                        }
                    } else {
                        cv.put(Telephony.Carriers.EDITED, Telephony.Carriers.USER_EDITED);
                    }

                    // New EDITED column. Default value (Telephony.Carriers.UNEDITED) will
                    // be used for all rows except for non-mvno entries for plmns indicated
                    // by resource: those will be set to CARRIER_EDITED to preserve
                    // their current values
                    val = c.getString(c.getColumnIndex(Telephony.Carriers.NUMERIC));
                    for (String s : persistApnsForPlmns) {
                        if (!TextUtils.isEmpty(val) && val.equals(s) &&
                                (!cv.containsKey(Telephony.Carriers.MVNO_TYPE) ||
                                        TextUtils.isEmpty(cv.getAsString(Telephony.Carriers.
                                                MVNO_TYPE)))) {
                            if (userEditedColumnIdx == -1) {
                                cv.put(Telephony.Carriers.EDITED,
                                        Telephony.Carriers.CARRIER_EDITED);
                            } else { // if (oldVersion == 14) -- if db had user_edited column
                                if (cv.getAsInteger(Telephony.Carriers.EDITED) ==
                                        Telephony.Carriers.USER_EDITED) {
                                    cv.put(Telephony.Carriers.EDITED,
                                            Telephony.Carriers.CARRIER_EDITED);
                                }
                            }

                            break;
                        }
                    }

                    try {
                        db.insertWithOnConflict(CARRIERS_TABLE_TMP, null, cv,
                                SQLiteDatabase.CONFLICT_ABORT);
                        if (VDBG) {
                            log("dbh.copyPreservedApnsToNewTable: db.insert returned >= 0; " +
                                    "insert successful for cv " + cv);
                        }
                    } catch (SQLException e) {
                        if (VDBG)
                            log("dbh.copyPreservedApnsToNewTable insertWithOnConflict exception " +
                                    e + " for cv " + cv);
                        // Insertion failed which could be due to a conflict. Check if that is
                        // the case and merge the entries
                        Cursor oldRow = DatabaseHelper.selectConflictingRow(db,
                                CARRIERS_TABLE_TMP, cv);
                        if (oldRow != null) {
                            ContentValues mergedValues = new ContentValues();
                            mergeFieldsAndUpdateDb(db, CARRIERS_TABLE_TMP, oldRow, cv,
                                    mergedValues, true, mContext);
                            oldRow.close();
                        }
                    }
                }
            }
        }

        private void getStringValueFromCursor(ContentValues cv, Cursor c, String key) {
            String fromCursor = c.getString(c.getColumnIndex(key));
            if (!TextUtils.isEmpty(fromCursor)) {
                cv.put(key, fromCursor);
            }
        }

        private void getIntValueFromCursor(ContentValues cv, Cursor c, String key) {
            String fromCursor = c.getString(c.getColumnIndex(key));
            if (!TextUtils.isEmpty(fromCursor)) {
                try {
                    cv.put(key, new Integer(fromCursor));
                } catch (NumberFormatException nfe) {
                    // do nothing
                }
            }
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
            addStringAttribute(parser, "apn", map, Telephony.Carriers.APN);
            addStringAttribute(parser, "user", map, Telephony.Carriers.USER);
            addStringAttribute(parser, "server", map, Telephony.Carriers.SERVER);
            addStringAttribute(parser, "password", map, Telephony.Carriers.PASSWORD);
            addStringAttribute(parser, "proxy", map, Telephony.Carriers.PROXY);
            addStringAttribute(parser, "port", map, Telephony.Carriers.PORT);
            addStringAttribute(parser, "mmsproxy", map, Telephony.Carriers.MMSPROXY);
            addStringAttribute(parser, "mmsport", map, Telephony.Carriers.MMSPORT);
            addStringAttribute(parser, "mmsc", map, Telephony.Carriers.MMSC);
            addStringAttribute(parser, "type", map, Telephony.Carriers.TYPE);
            addStringAttribute(parser, "protocol", map, Telephony.Carriers.PROTOCOL);
            addStringAttribute(parser, "roaming_protocol", map, Telephony.Carriers.ROAMING_PROTOCOL);

            addIntAttribute(parser, "authtype", map, Telephony.Carriers.AUTH_TYPE);
            addIntAttribute(parser, "bearer", map, Telephony.Carriers.BEARER);
            addIntAttribute(parser, "profile_id", map, Telephony.Carriers.PROFILE_ID);
            addIntAttribute(parser, "max_conns", map, Telephony.Carriers.MAX_CONNS);
            addIntAttribute(parser, "wait_time", map, Telephony.Carriers.WAIT_TIME);
            addIntAttribute(parser, "max_conns_time", map, Telephony.Carriers.MAX_CONNS_TIME);
            addIntAttribute(parser, "mtu", map, Telephony.Carriers.MTU);


            addBoolAttribute(parser, "carrier_enabled", map, Telephony.Carriers.CARRIER_ENABLED);
            addBoolAttribute(parser, "modem_cognitive", map, Telephony.Carriers.MODEM_COGNITIVE);
            addBoolAttribute(parser, "user_visible", map, Telephony.Carriers.USER_VISIBLE);

            String bearerList = parser.getAttributeValue(null, "bearer_bitmask");
            if (bearerList != null) {
                int bearerBitmask = ServiceState.getBitmaskFromString(bearerList);
                map.put(Telephony.Carriers.BEARER_BITMASK, bearerBitmask);
            }

            String mvno_type = parser.getAttributeValue(null, "mvno_type");
            if (mvno_type != null) {
                String mvno_match_data = parser.getAttributeValue(null, "mvno_match_data");
                if (mvno_match_data != null) {
                    map.put(Telephony.Carriers.MVNO_TYPE, mvno_type);
                    map.put(Telephony.Carriers.MVNO_MATCH_DATA, mvno_match_data);
                }
            }

            return map;
        }

        private void addStringAttribute(XmlPullParser parser, String att,
                                        ContentValues map, String key) {
            String val = parser.getAttributeValue(null, att);
            if (val != null) {
                map.put(key, val);
            }
        }

        private void addIntAttribute(XmlPullParser parser, String att,
                                     ContentValues map, String key) {
            String val = parser.getAttributeValue(null, att);
            if (val != null) {
                map.put(key, Integer.parseInt(val));
            }
        }

        private void addBoolAttribute(XmlPullParser parser, String att,
                                      ContentValues map, String key) {
            String val = parser.getAttributeValue(null, att);
            if (val != null) {
                map.put(key, Boolean.parseBoolean(val));
            }
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
                // update edited field accordingly.
                // Search for the exact same entry and update edited field.
                // If it is USER_EDITED/CARRIER_EDITED change it to UNEDITED,
                // and if USER/CARRIER_DELETED change it to USER/CARRIER_DELETED_BUT_PRESENT_IN_XML.
                Cursor oldRow = selectConflictingRow(db, CARRIERS_TABLE, row);
                if (oldRow != null) {
                    // Update the row
                    ContentValues mergedValues = new ContentValues();
                    int edited = oldRow.getInt(oldRow.getColumnIndex(
                            Telephony.Carriers.EDITED));
                    int old_edited = edited;
                    if (edited != Telephony.Carriers.UNEDITED) {
                        if (edited == Telephony.Carriers.USER_DELETED) {
                            // USER_DELETED_BUT_PRESENT_IN_XML indicates entry has been deleted
                            // by user but present in apn xml file.
                            edited = Telephony.Carriers.USER_DELETED_BUT_PRESENT_IN_XML;
                        } else if (edited == Telephony.Carriers.CARRIER_DELETED) {
                            // CARRIER_DELETED_BUT_PRESENT_IN_XML indicates entry has been deleted
                            // by user but present in apn xml file.
                            edited = Telephony.Carriers.CARRIER_DELETED_BUT_PRESENT_IN_XML;
                        }
                        mergedValues.put(Telephony.Carriers.EDITED, edited);
                    }

                    mergeFieldsAndUpdateDb(db, CARRIERS_TABLE, oldRow, row, mergedValues, false,
                            mContext);

                    if (VDBG) log("dbh.insertAddingDefaults: old edited = " + old_edited
                            + " new edited = " + edited);

                    oldRow.close();
                }
            }
        }

        public static void mergeFieldsAndUpdateDb(SQLiteDatabase db, String table, Cursor oldRow,
                                                  ContentValues newRow, ContentValues mergedValues,
                                                  boolean onUpgrade, Context context) {
            if (newRow.containsKey(Telephony.Carriers.TYPE)) {
                // Merge the types
                String oldType = oldRow.getString(oldRow.getColumnIndex(Telephony.Carriers.TYPE));
                String newType = newRow.getAsString(Telephony.Carriers.TYPE);

                if (!oldType.equalsIgnoreCase(newType)) {
                    if (oldType.equals("") || newType.equals("")) {
                        newRow.put(Telephony.Carriers.TYPE, "");
                    } else {
                        String[] oldTypes = oldType.toLowerCase().split(",");
                        String[] newTypes = newType.toLowerCase().split(",");

                        if (VDBG) {
                            log("mergeFieldsAndUpdateDb: Calling separateRowsNeeded() oldType=" +
                                    oldType + " old bearer=" + oldRow.getInt(oldRow.getColumnIndex(
                                    Telephony.Carriers.BEARER_BITMASK)) +
                                    " old profile_id=" + oldRow.getInt(oldRow.getColumnIndex(
                                    Telephony.Carriers.PROFILE_ID)) +
                                    " newRow " + newRow);
                        }

                        // If separate rows are needed, do not need to merge any further
                        if (separateRowsNeeded(db, table, oldRow, newRow, context, oldTypes,
                                newTypes)) {
                            if (VDBG) log("mergeFieldsAndUpdateDb: separateRowsNeeded() returned " +
                                    "true");
                            return;
                        }

                        // Merge the 2 types
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

            if (newRow.containsKey(Telephony.Carriers.BEARER_BITMASK)) {
                int oldBearer = oldRow.getInt(oldRow.getColumnIndex(Telephony.Carriers.
                        BEARER_BITMASK));
                int newBearer = newRow.getAsInteger(Telephony.Carriers.BEARER_BITMASK);
                if (oldBearer != newBearer) {
                    if (oldBearer == 0 || newBearer == 0) {
                        newRow.put(Telephony.Carriers.BEARER_BITMASK, 0);
                    } else {
                        newRow.put(Telephony.Carriers.BEARER_BITMASK, (oldBearer | newBearer));
                    }
                }
                mergedValues.put(Telephony.Carriers.BEARER_BITMASK, newRow.getAsInteger(
                        Telephony.Carriers.BEARER_BITMASK));
            }

            if (!onUpgrade) {
                mergedValues.putAll(newRow);
            }

            if (mergedValues.size() > 0) {
                db.update(table, mergedValues, "_id=" + oldRow.getInt(oldRow.getColumnIndex("_id")),
                        null);
            }
        }

        private static boolean separateRowsNeeded(SQLiteDatabase db, String table, Cursor oldRow,
                                                  ContentValues newRow, Context context,
                                                  String[] oldTypes, String[] newTypes) {
            // If this APN falls under persist_apns_for_plmn, and the
            // only difference between old type and new type is that one has dun, and
            // the APNs have profile_id 0 or not set, then set the profile_id to 1 for
            // the dun APN/remove dun from type. This will ensure both oldRow and newRow exist
            // separately in db.

            boolean match = false;

            // Check if APN falls under persist_apns_for_plmn
            String[] persistApnsForPlmns = context.getResources().getStringArray(
                    R.array.persist_apns_for_plmn);
            for (String s : persistApnsForPlmns) {
                if (s.equalsIgnoreCase(newRow.getAsString(Telephony.Carriers.
                        NUMERIC))) {
                    match = true;
                    break;
                }
            }

            if (!match) return false;

            // APN falls under persist_apns_for_plmn
            // Check if only difference between old type and new type is that
            // one has dun
            ArrayList<String> oldTypesAl = new ArrayList<String>(
                    Arrays.asList(oldTypes));
            ArrayList<String> newTypesAl = new ArrayList<String>(
                    Arrays.asList(newTypes));
            ArrayList<String> listWithDun = null;
            ArrayList<String> listWithoutDun = null;
            boolean dunInOld = false;
            if (oldTypesAl.size() == newTypesAl.size() + 1) {
                listWithDun = oldTypesAl;
                listWithoutDun = newTypesAl;
                dunInOld = true;
            } else if (oldTypesAl.size() + 1 == newTypesAl.size()) {
                listWithDun = newTypesAl;
                listWithoutDun = oldTypesAl;
            } else {
                return false;
            }

            if (listWithDun.contains("dun") &&
                    !listWithoutDun.contains("dun")) {
                listWithoutDun.add("dun");
                if (!listWithDun.containsAll(listWithoutDun)) {
                    return false;
                }

                // Only difference between old type and new type is that
                // one has dun
                // Check if profile_id is 0/not set
                if (oldRow.getInt(oldRow.getColumnIndex(Telephony.Carriers.
                        PROFILE_ID)) == 0) {
                    if (dunInOld) {
                        // Update oldRow to remove dun from its type field
                        ContentValues updateOldRow = new ContentValues();
                        StringBuilder sb = new StringBuilder();
                        boolean first = true;
                        for (String s : listWithDun) {
                            if (!s.equalsIgnoreCase("dun")) {
                                sb.append(first ? s : "," + s);
                                first = false;
                            }
                        }
                        String updatedType = sb.toString();
                        if (VDBG) {
                            log("separateRowsNeeded: updating type in oldRow to " +
                                    updatedType);
                        }
                        updateOldRow.put(Telephony.Carriers.TYPE, updatedType);
                        db.update(table, updateOldRow,
                                "_id=" + oldRow.getInt(oldRow.getColumnIndex("_id")), null);
                        return true;
                    } else {
                        if (VDBG) log("separateRowsNeeded: adding profile id 1 to newRow");
                        // Update newRow to set profile_id to 1
                        newRow.put(Telephony.Carriers.PROFILE_ID,
                                new Integer(1));
                    }
                } else {
                    return false;
                }

                // If match was found, both oldRow and newRow need to exist
                // separately in db. Add newRow to db.
                try {
                    db.insertWithOnConflict(table, null, newRow,
                            SQLiteDatabase.CONFLICT_REPLACE);
                    if (VDBG) log("separateRowsNeeded: added newRow with profile id 1 to db");
                    return true;
                } catch (SQLException e) {
                    loge("Exception on trying to add new row after " +
                            "updating profile_id");
                }
            }

            return false;
        }

        public static Cursor selectConflictingRow(SQLiteDatabase db, String table,
                                                  ContentValues row) {
            // Conflict is possible only when numeric, mcc, mnc (fields without any default value)
            // are set in the new row
            if (!row.containsKey(Telephony.Carriers.NUMERIC) ||
                    !row.containsKey(Telephony.Carriers.MCC) ||
                    !row.containsKey(Telephony.Carriers.MNC)) {
                loge("dbh.selectConflictingRow: called for non-conflicting row: " + row);
                return null;
            }

            String[] columns = { "_id",
                    Telephony.Carriers.TYPE,
                    Telephony.Carriers.EDITED,
                    Telephony.Carriers.BEARER_BITMASK,
                    Telephony.Carriers.PROFILE_ID };
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
                if (c.getCount() == 1) {
                    if (VDBG) log("dbh.selectConflictingRow: " + c.getCount() + " conflicting " +
                            "row found");
                    if (c.moveToFirst()) {
                        return c;
                    } else {
                        loge("dbh.selectConflictingRow: moveToFirst() failed");
                    }
                } else {
                    loge("dbh.selectConflictingRow: Expected 1 but found " + c.getCount() +
                            " matching rows found for cv " + row);
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

                // Get rid of old preferred apn shared preferences
                SubscriptionManager sm = SubscriptionManager.from(getContext());
                if (sm != null) {
                    List<SubscriptionInfo> subInfoList = sm.getAllSubscriptionInfoList();
                    for (SubscriptionInfo subInfo : subInfoList) {
                        SharedPreferences spPrefFile = getContext().getSharedPreferences(
                                PREF_FILE + subInfo.getSubscriptionId(), Context.MODE_PRIVATE);
                        if (spPrefFile != null) {
                            SharedPreferences.Editor editor = spPrefFile.edit();
                            editor.clear();
                            editor.apply();
                        }
                    }
                }

                // Update APN DB
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
        SharedPreferences sp = getContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        editor.putLong(COLUMN_APN_ID + subId, id != null ? id.longValue() : INVALID_APN_ID);
        editor.apply();
        // remove saved apn if apnId is invalid
        if (id == null || id.longValue() == INVALID_APN_ID) {
            deletePreferredApn(subId);
        }
    }

    private long getPreferredApnId(int subId, boolean checkApnSp) {
        SharedPreferences sp = getContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        long apnId = sp.getLong(COLUMN_APN_ID + subId, INVALID_APN_ID);
        if (apnId == INVALID_APN_ID && checkApnSp) {
            apnId = getPreferredApnIdFromApn(subId);
            if (apnId != INVALID_APN_ID) {
                setPreferredApnId(apnId, subId);
                deletePreferredApn(subId);
            }
        }
        return apnId;
    }

    private void deletePreferredApnId() {
        SharedPreferences sp = getContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        // before deleting, save actual preferred apns (not the ids) in a separate SP
        Map<String, ?> allPrefApnId = sp.getAll();
        for (String key : allPrefApnId.keySet()) {
            // extract subId from key by removing COLUMN_APN_ID
            try {
                int subId = Integer.parseInt(key.replace(COLUMN_APN_ID, ""));
                long apnId = getPreferredApnId(subId, false);
                if (apnId != INVALID_APN_ID) {
                    setPreferredApn(apnId, subId);
                }
            } catch (Exception e) {
                loge("Skipping over key " + key + " due to exception " + e);
            }
        }
        SharedPreferences.Editor editor = sp.edit();
        editor.clear();
        editor.apply();
    }

    private void setPreferredApn(Long id, int subId) {
        log("setPreferredApn: _id " + id + " subId " + subId);
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        // query all unique fields from id
        String[] proj = CARRIERS_UNIQUE_FIELDS.toArray(new String[CARRIERS_UNIQUE_FIELDS.size()]);
        Cursor c = db.query(CARRIERS_TABLE, proj, "_id=" + id, null, null, null, null);
        if (c != null) {
            if (c.getCount() == 1) {
                c.moveToFirst();
                SharedPreferences sp = getContext().getSharedPreferences(PREF_FILE_FULL_APN,
                        Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = sp.edit();
                // store values of all unique fields to SP
                for (String key : CARRIERS_UNIQUE_FIELDS) {
                    editor.putString(key + subId, c.getString(c.getColumnIndex(key)));
                }
                // also store the version number
                editor.putString(DB_VERSION_KEY + subId, "" + DATABASE_VERSION);
                editor.apply();
            } else {
                log("setPreferredApn: # matching APNs found " + c.getCount());
            }
            c.close();
        } else {
            log("setPreferredApn: No matching APN found");
        }
    }

    private long getPreferredApnIdFromApn(int subId) {
        log("getPreferredApnIdFromApn: for subId " + subId);
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        String where = TextUtils.join("=? and ", CARRIERS_UNIQUE_FIELDS) + "=?";
        String[] whereArgs = new String[CARRIERS_UNIQUE_FIELDS.size()];
        SharedPreferences sp = getContext().getSharedPreferences(PREF_FILE_FULL_APN,
                Context.MODE_PRIVATE);
        long apnId = INVALID_APN_ID;
        int i = 0;
        for (String key : CARRIERS_UNIQUE_FIELDS) {
            whereArgs[i] = sp.getString(key + subId, null);
            if (whereArgs[i] == null) {
                return INVALID_APN_ID;
            }
            i++;
        }
        Cursor c = db.query(CARRIERS_TABLE, new String[]{"_id"}, where, whereArgs, null, null, null);
        if (c != null) {
            if (c.getCount() == 1) {
                c.moveToFirst();
                apnId = c.getInt(c.getColumnIndex("_id"));
            } else {
                log("getPreferredApnIdFromApn: returning INVALID. # matching APNs found " +
                        c.getCount());
            }
            c.close();
        } else {
            log("getPreferredApnIdFromApn: returning INVALID. No matching APN found");
        }
        return apnId;
    }

    private void deletePreferredApn(int subId) {
        log("deletePreferredApn: for subId " + subId);
        SharedPreferences sp = getContext().getSharedPreferences(PREF_FILE_FULL_APN,
                Context.MODE_PRIVATE);
        if (sp.contains(DB_VERSION_KEY + subId)) {
            log("deletePreferredApn: apn is stored. Deleting it now for subId " + subId);
            SharedPreferences.Editor editor = sp.edit();
            editor.remove(DB_VERSION_KEY + subId);
            for (String key : CARRIERS_UNIQUE_FIELDS) {
                editor.remove(key + subId);
            }
            editor.remove(DB_VERSION_KEY + subId);
            editor.apply();
        }
    }

    @Override
    public synchronized Cursor query(Uri url, String[] projectionIn, String selection,
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
                qb.appendWhere("_id = " + getPreferredApnId(subId, true));
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
                selection += "edited!=" + Telephony.Carriers.USER_DELETED + " and edited!="
                        + Telephony.Carriers.USER_DELETED_BUT_PRESENT_IN_XML + " and edited!="
                        + Telephony.Carriers.CARRIER_DELETED + " and edited!="
                        + Telephony.Carriers.CARRIER_DELETED_BUT_PRESENT_IN_XML;
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
    public synchronized Uri insert(Uri url, ContentValues initialValues)
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
                if (!values.containsKey(Telephony.Carriers.EDITED)) {
                    values.put(Telephony.Carriers.EDITED, Telephony.Carriers.USER_EDITED);
                }

                try {
                    // Replace on conflict so that if same APN is present in db with edited
                    // as Telephony.Carriers.UNEDITED or USER/CARRIER_DELETED, it is replaced with
                    // edited USER/CARRIER_EDITED
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
                                mergedValues, false, getContext());
                        oldRow.close();
                        notify = true;
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
    public synchronized int delete(Uri url, String where, String[] whereArgs)
    {
        int count = 0;
        int subId = SubscriptionManager.getDefaultSubId();
        String userOrCarrierEdited = ") and (" +
                Telephony.Carriers.EDITED + "=" + Telephony.Carriers.USER_EDITED +  " or " +
                Telephony.Carriers.EDITED + "=" + Telephony.Carriers.CARRIER_EDITED + ")";
        String notUserOrCarrierEdited = ") and (" +
                Telephony.Carriers.EDITED + "!=" + Telephony.Carriers.USER_EDITED +  " and " +
                Telephony.Carriers.EDITED + "!=" + Telephony.Carriers.CARRIER_EDITED + ")";
        ContentValues cv = new ContentValues();
        cv.put(Telephony.Carriers.EDITED, Telephony.Carriers.USER_DELETED);

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
                // Delete user/carrier edited entries
                count = db.delete(CARRIERS_TABLE, "(" + where + userOrCarrierEdited, whereArgs);
                // Otherwise mark as user deleted instead of deleting
                count += db.update(CARRIERS_TABLE, cv, "(" + where + notUserOrCarrierEdited,
                        whereArgs);
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
                // Delete user/carrier edited entries
                count = db.delete(CARRIERS_TABLE, "(" + where + userOrCarrierEdited, whereArgs);
                // Otherwise mark as user deleted instead of deleting
                count += db.update(CARRIERS_TABLE, cv, "(" + where + notUserOrCarrierEdited,
                        whereArgs);
                break;
            }

            case URL_ID:
            {
                // Delete user/carrier edited entries
                count = db.delete(CARRIERS_TABLE,
                        "(" + Telephony.Carriers._ID + "=?" + userOrCarrierEdited,
                        new String[] { url.getLastPathSegment() });
                // Otherwise mark as user deleted instead of deleting
                count += db.update(CARRIERS_TABLE, cv,
                        "(" + Telephony.Carriers._ID + "=?" + notUserOrCarrierEdited,
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
    public synchronized int update(Uri url, ContentValues values, String where, String[] whereArgs)
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
                if (!values.containsKey(Telephony.Carriers.EDITED)) {
                    values.put(Telephony.Carriers.EDITED, Telephony.Carriers.USER_EDITED);
                }

                // Replace on conflict so that if same APN is present in db with edited
                // as Telephony.Carriers.UNEDITED or USER/CARRIER_DELETED, it is replaced with
                // edited USER/CARRIER_EDITED
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
                if (!values.containsKey(Telephony.Carriers.EDITED)) {
                    values.put(Telephony.Carriers.EDITED, Telephony.Carriers.USER_EDITED);
                }
                // Replace on conflict so that if same APN is present in db with edited
                // as Telephony.Carriers.UNEDITED or USER/CARRIER_DELETED, it is replaced with
                // edited USER/CARRIER_EDITED
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
                if (!values.containsKey(Telephony.Carriers.EDITED)) {
                    values.put(Telephony.Carriers.EDITED, Telephony.Carriers.USER_EDITED);
                }
                // Replace on conflict so that if same APN is present in db with edited
                // as Telephony.Carriers.UNEDITED or USER/CARRIER_DELETED, it is replaced with
                // edited USER/CARRIER_EDITED
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

    private synchronized void updateApnDb() {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        // Delete preferred APN for all subIds
        deletePreferredApnId();

        // Delete entries in db
        try {
            if (VDBG) log("updateApnDb: deleting edited=Telephony.Carriers.UNEDITED entries");
            db.delete(CARRIERS_TABLE, "edited=" + Telephony.Carriers.UNEDITED, null);
        } catch (SQLException e) {
            loge("got exception when deleting to update: " + e);
        }

        mOpenHelper.initDatabase(db);

        // Notify listereners of DB change since DB has been updated
        getContext().getContentResolver().notifyChange(
                Telephony.Carriers.CONTENT_URI, null, true, UserHandle.USER_ALL);

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
