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
import android.provider.Telephony;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.Xml;

import com.android.internal.telephony.BaseCommands;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.lang.NumberFormatException;

public class TelephonyProvider extends ContentProvider
{
    private static final String DATABASE_NAME = "telephony.db";
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    private static final int DATABASE_VERSION = 12 << 16;
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

    private static final String TAG = "TelephonyProvider";
    private static final String CARRIERS_TABLE = "carriers";
    private static final String SIMINFO_TABLE = "siminfo";

    private static final String PREF_FILE = "preferred-apn";
    private static final String COLUMN_APN_ID = "apn_id";

    private static final String PARTNER_APNS_PATH = "etc/apns-conf.xml";

    private static final UriMatcher s_urlMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    private static final ContentValues s_currentNullMap;
    private static final ContentValues s_currentSetMap;

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


        s_currentNullMap = new ContentValues(1);
        s_currentNullMap.put("current", (Long) null);

        s_currentSetMap = new ContentValues(1);
        s_currentSetMap.put("current", "1");
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
            createCarriersTable(db);
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
                    createCarriersTable(db);
                }
            }
            if (VDBG) log("dbh.onOpen:- db=" + db);
        }

        private void createSimInfoTable(SQLiteDatabase db) {
            if (DBG) log("dbh.createSimInfoTable:+");
            db.execSQL("CREATE TABLE " + SIMINFO_TABLE + "("
                    + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + SubscriptionManager.ICC_ID + " TEXT NOT NULL,"
                    + SubscriptionManager.SIM_ID + " INTEGER DEFAULT " + SubscriptionManager.SIM_NOT_INSERTED + ","
                    + SubscriptionManager.DISPLAY_NAME + " TEXT,"
                    + SubscriptionManager.NAME_SOURCE + " INTEGER DEFAULT " + SubscriptionManager.NAME_SOURCE_DEFAULT_SOURCE + ","
                    + SubscriptionManager.COLOR + " INTEGER DEFAULT " + SubscriptionManager.COLOR_DEFAULT + ","
                    + SubscriptionManager.NUMBER + " TEXT,"
                    + SubscriptionManager.DISPLAY_NUMBER_FORMAT + " INTEGER NOT NULL DEFAULT " + SubscriptionManager.DISLPAY_NUMBER_DEFAULT + ","
                    + SubscriptionManager.DATA_ROAMING + " INTEGER DEFAULT " + SubscriptionManager.DATA_ROAMING_DEFAULT + ","
                    + SubscriptionManager.MCC + " INTEGER DEFAULT 0,"
                    + SubscriptionManager.MNC + " INTEGER DEFAULT 0"
                    + ");");
            if (DBG) log("dbh.createSimInfoTable:-");
        }

        private void createCarriersTable(SQLiteDatabase db) {
            // Set up the database schema
            if (DBG) log("dbh.createCarriersTable:+");
            db.execSQL("CREATE TABLE " + CARRIERS_TABLE +
                "(_id INTEGER PRIMARY KEY," +
                    "name TEXT," +
                    "numeric TEXT," +
                    "mcc TEXT," +
                    "mnc TEXT," +
                    "apn TEXT," +
                    "user TEXT," +
                    "server TEXT," +
                    "password TEXT," +
                    "proxy TEXT," +
                    "port TEXT," +
                    "mmsproxy TEXT," +
                    "mmsport TEXT," +
                    "mmsc TEXT," +
                    "authtype INTEGER," +
                    "type TEXT," +
                    "current INTEGER," +
                    "protocol TEXT," +
                    "roaming_protocol TEXT," +
                    "carrier_enabled BOOLEAN," +
                    "bearer INTEGER," +
                    "mvno_type TEXT," +
                    "mvno_match_data TEXT," +
                    "sub_id LONG DEFAULT -1," +
                    "profile_id INTEGER default 0," +
                    "modem_cognitive BOOLEAN default 0," +
                    "max_conns INTEGER default 0," +
                    "wait_time INTEGER default 0," +
                    "max_conns_time INTEGER default 0," +
                    "mtu INTEGER);");
             /* FIXME Currenlty sub_id is column is not used for query purpose.
             This would be modified to more appropriate default value later. */
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
            FileReader confreader = null;
            try {
                confreader = new FileReader(confFile);
                confparser = Xml.newPullParser();
                confparser.setInput(confreader);
                XmlUtils.beginDocument(confparser, "apns");

                // Sanity check. Force internal version and confidential versions to agree
                int confversion = Integer.parseInt(confparser.getAttributeValue(null, "version"));
                if (publicversion != confversion) {
                    throw new IllegalStateException("Internal APNS file version doesn't match "
                            + confFile.getAbsolutePath());
                }

                loadApns(db, confparser);
            } catch (FileNotFoundException e) {
                // It's ok if the file isn't found. It means there isn't a confidential file
                // Log.e(TAG, "File not found: '" + confFile.getAbsolutePath() + "'");
            } catch (Exception e) {
                loge("Exception while parsing '" + confFile.getAbsolutePath() + "'" + e);
            } finally {
                try { if (confreader != null) confreader.close(); } catch (IOException e) { }
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
                        " ADD COLUMN sub_id LONG DEFAULT -1;");
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
            if (DBG) {
                log("dbh.onUpgrade:- db=" + db + " oldV=" + oldVersion + " newV=" + newVersion);
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

            map.put(Telephony.Carriers.NUMERIC,numeric);
            map.put(Telephony.Carriers.MCC, mcc);
            map.put(Telephony.Carriers.MNC, mnc);
            map.put(Telephony.Carriers.NAME, parser.getAttributeValue(null, "carrier"));
            map.put(Telephony.Carriers.APN, parser.getAttributeValue(null, "apn"));
            map.put(Telephony.Carriers.USER, parser.getAttributeValue(null, "user"));
            map.put(Telephony.Carriers.SERVER, parser.getAttributeValue(null, "server"));
            map.put(Telephony.Carriers.PASSWORD, parser.getAttributeValue(null, "password"));

            // do not add NULL to the map so that insert() will set the default value
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
            map.put(Telephony.Carriers.MMSC, parser.getAttributeValue(null, "mmsc"));
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
                        insertAddingDefaults(db, CARRIERS_TABLE, row);
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
            if (!values.containsKey(Telephony.Carriers.NAME)) {
                values.put(Telephony.Carriers.NAME, "");
            }
            if (!values.containsKey(Telephony.Carriers.APN)) {
                values.put(Telephony.Carriers.APN, "");
            }
            if (!values.containsKey(Telephony.Carriers.PORT)) {
                values.put(Telephony.Carriers.PORT, "");
            }
            if (!values.containsKey(Telephony.Carriers.PROXY)) {
                values.put(Telephony.Carriers.PROXY, "");
            }
            if (!values.containsKey(Telephony.Carriers.USER)) {
                values.put(Telephony.Carriers.USER, "");
            }
            if (!values.containsKey(Telephony.Carriers.SERVER)) {
                values.put(Telephony.Carriers.SERVER, "");
            }
            if (!values.containsKey(Telephony.Carriers.PASSWORD)) {
                values.put(Telephony.Carriers.PASSWORD, "");
            }
            if (!values.containsKey(Telephony.Carriers.MMSPORT)) {
                values.put(Telephony.Carriers.MMSPORT, "");
            }
            if (!values.containsKey(Telephony.Carriers.MMSPROXY)) {
                values.put(Telephony.Carriers.MMSPROXY, "");
            }
            if (!values.containsKey(Telephony.Carriers.AUTH_TYPE)) {
                values.put(Telephony.Carriers.AUTH_TYPE, -1);
            }
            if (!values.containsKey(Telephony.Carriers.PROTOCOL)) {
                values.put(Telephony.Carriers.PROTOCOL, "IP");
            }
            if (!values.containsKey(Telephony.Carriers.ROAMING_PROTOCOL)) {
                values.put(Telephony.Carriers.ROAMING_PROTOCOL, "IP");
            }
            if (!values.containsKey(Telephony.Carriers.CARRIER_ENABLED)) {
                values.put(Telephony.Carriers.CARRIER_ENABLED, true);
            }
            if (!values.containsKey(Telephony.Carriers.BEARER)) {
                values.put(Telephony.Carriers.BEARER, 0);
            }
            if (!values.containsKey(Telephony.Carriers.MVNO_TYPE)) {
                values.put(Telephony.Carriers.MVNO_TYPE, "");
            }
            if (!values.containsKey(Telephony.Carriers.MVNO_MATCH_DATA)) {
                values.put(Telephony.Carriers.MVNO_MATCH_DATA, "");
            }

            long subId = SubscriptionManager.getDefaultSubId();
            if (!values.containsKey(Telephony.Carriers.SUB_ID)) {
                values.put(Telephony.Carriers.SUB_ID, subId);
            }

            if (!values.containsKey(Telephony.Carriers.PROFILE_ID)) {
                values.put(Telephony.Carriers.PROFILE_ID, 0);
            }
            if (!values.containsKey(Telephony.Carriers.MODEM_COGNITIVE)) {
                values.put(Telephony.Carriers.MODEM_COGNITIVE, false);
            }
            if (!values.containsKey(Telephony.Carriers.MAX_CONNS)) {
                values.put(Telephony.Carriers.MAX_CONNS, 0);
            }
            if (!values.containsKey(Telephony.Carriers.WAIT_TIME)) {
                values.put(Telephony.Carriers.WAIT_TIME, 0);
            }
            if (!values.containsKey(Telephony.Carriers.MAX_CONNS_TIME)) {
                values.put(Telephony.Carriers.MAX_CONNS_TIME, 0);
            }

            return values;
        }

        private void insertAddingDefaults(SQLiteDatabase db, String table, ContentValues row) {
            row = setDefaultValue(row);
            db.insert(CARRIERS_TABLE, null, row);
        }
    }

    @Override
    public boolean onCreate() {
        if (VDBG) log("onCreate:+");
        mOpenHelper = new DatabaseHelper(getContext());
        if (VDBG) log("onCreate:- ret true");
        return true;
    }

    private void setPreferredApnId(Long id, long subId) {
        SharedPreferences sp = getContext().getSharedPreferences(
                PREF_FILE + subId, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        editor.putLong(COLUMN_APN_ID, id != null ? id.longValue() : -1);
        editor.apply();
    }

    private long getPreferredApnId(long subId) {
        SharedPreferences sp = getContext().getSharedPreferences(
                PREF_FILE + subId, Context.MODE_PRIVATE);
        return sp.getLong(COLUMN_APN_ID, -1);
    }

    @Override
    public Cursor query(Uri url, String[] projectionIn, String selection,
            String[] selectionArgs, String sort) {
        TelephonyManager mTelephonyManager =
                (TelephonyManager)getContext().getSystemService(Context.TELEPHONY_SERVICE);
        long subId = SubscriptionManager.getDefaultSubId();
        String subIdString;
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setStrict(true); // a little protection from injection attacks
        qb.setTables("carriers");

        int match = s_urlMatcher.match(url);
        switch (match) {
            case URL_TELEPHONY_USING_SUBID: {
                subIdString = url.getLastPathSegment();
                try {
                    subId = Long.parseLong(subIdString);
                } catch (NumberFormatException e) {
                    loge("NumberFormatException" + e);
                    return null;
                }
                if (DBG) log("subIdString = " + subIdString + " subId = " + subId);
                qb.appendWhere("numeric = '" + mTelephonyManager.getSimOperator(subId)+"'");
                // FIXME alter the selection to pass subId
                // selection = selection + "and subId = "
            }
            //intentional fall through from above case
            // do nothing
            case URL_TELEPHONY: {
                break;
            }

            case URL_CURRENT_USING_SUBID: {
                subIdString = url.getLastPathSegment();
                try {
                    subId = Long.parseLong(subIdString);
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
                    subId = Long.parseLong(subIdString);
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
        long subId = SubscriptionManager.getDefaultSubId();

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
                    subId = Long.parseLong(subIdString);
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

                long rowID = db.insert(CARRIERS_TABLE, null, values);
                if (rowID > 0)
                {
                    result = ContentUris.withAppendedId(Telephony.Carriers.CONTENT_URI, rowID);
                    notify = true;
                }

                if (VDBG) log("inserted " + values.toString() + " rowID = " + rowID);
                break;
            }

            case URL_CURRENT_USING_SUBID:
            {
                String subIdString = url.getLastPathSegment();
                try {
                    subId = Long.parseLong(subIdString);
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
                // null out the previous operator
                db.update("carriers", s_currentNullMap, "current IS NOT NULL", null);

                String numeric = initialValues.getAsString("numeric");
                int updated = db.update("carriers", s_currentSetMap,
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
                    subId = Long.parseLong(subIdString);
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
            getContext().getContentResolver().notifyChange(Telephony.Carriers.CONTENT_URI, null);
        }

        return result;
    }

    @Override
    public int delete(Uri url, String where, String[] whereArgs)
    {
        int count = 0;
        long subId = SubscriptionManager.getDefaultSubId();

        checkPermission();

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int match = s_urlMatcher.match(url);
        switch (match)
        {
            case URL_TELEPHONY_USING_SUBID:
            {
                 String subIdString = url.getLastPathSegment();
                 try {
                     subId = Long.parseLong(subIdString);
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
                count = db.delete(CARRIERS_TABLE, where, whereArgs);
                break;
            }

            case URL_CURRENT_USING_SUBID: {
                String subIdString = url.getLastPathSegment();
                try {
                    subId = Long.parseLong(subIdString);
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
                count = db.delete(CARRIERS_TABLE, where, whereArgs);
                break;
            }

            case URL_ID:
            {
                count = db.delete(CARRIERS_TABLE, Telephony.Carriers._ID + "=?",
                        new String[] { url.getLastPathSegment() });
                break;
            }

            case URL_RESTOREAPN_USING_SUBID: {
                String subIdString = url.getLastPathSegment();
                try {
                    subId = Long.parseLong(subIdString);
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
                    subId = Long.parseLong(subIdString);
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

            default: {
                throw new UnsupportedOperationException("Cannot delete that URL: " + url);
            }
        }

        if (count > 0) {
            getContext().getContentResolver().notifyChange(Telephony.Carriers.CONTENT_URI, null);
        }

        return count;
    }

    @Override
    public int update(Uri url, ContentValues values, String where, String[] whereArgs)
    {
        int count = 0;
        int uriType = URL_UNKNOWN;
        long subId = SubscriptionManager.getDefaultSubId();

        checkPermission();

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int match = s_urlMatcher.match(url);
        switch (match)
        {
            case URL_TELEPHONY_USING_SUBID:
            {
                 String subIdString = url.getLastPathSegment();
                 try {
                     subId = Long.parseLong(subIdString);
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
                count = db.update(CARRIERS_TABLE, values, where, whereArgs);
                break;
            }

            case URL_CURRENT_USING_SUBID:
            {
                String subIdString = url.getLastPathSegment();
                try {
                    subId = Long.parseLong(subIdString);
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
                count = db.update(CARRIERS_TABLE, values, where, whereArgs);
                break;
            }

            case URL_ID:
            {
                if (where != null || whereArgs != null) {
                    throw new UnsupportedOperationException(
                            "Cannot update URL " + url + " with a where clause");
                }
                count = db.update(CARRIERS_TABLE, values, Telephony.Carriers._ID + "=?",
                        new String[] { url.getLastPathSegment() });
                break;
            }

            case URL_PREFERAPN_USING_SUBID:
            case URL_PREFERAPN_NO_UPDATE_USING_SUBID:
            {
                String subIdString = url.getLastPathSegment();
                try {
                    subId = Long.parseLong(subIdString);
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
                            SubscriptionManager.CONTENT_URI, null);
                    break;
                default:
                    getContext().getContentResolver().notifyChange(
                            Telephony.Carriers.CONTENT_URI, null);
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

    private void restoreDefaultAPN(long subId) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        try {
            db.delete(CARRIERS_TABLE, null, null);
        } catch (SQLException e) {
            loge("got exception when deleting to restore: " + e);
        }
        setPreferredApnId((long)-1, subId);
        mOpenHelper.initDatabase(db);
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
