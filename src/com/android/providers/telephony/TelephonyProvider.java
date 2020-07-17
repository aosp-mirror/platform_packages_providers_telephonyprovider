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

import static android.provider.Telephony.Carriers.APN;
import static android.provider.Telephony.Carriers.APN_SET_ID;
import static android.provider.Telephony.Carriers.AUTH_TYPE;
import static android.provider.Telephony.Carriers.BEARER;
import static android.provider.Telephony.Carriers.BEARER_BITMASK;
import static android.provider.Telephony.Carriers.CARRIER_DELETED;
import static android.provider.Telephony.Carriers.CARRIER_DELETED_BUT_PRESENT_IN_XML;
import static android.provider.Telephony.Carriers.CARRIER_EDITED;
import static android.provider.Telephony.Carriers.CARRIER_ENABLED;
import static android.provider.Telephony.Carriers.CARRIER_ID;
import static android.provider.Telephony.Carriers.CONTENT_URI;
import static android.provider.Telephony.Carriers.CURRENT;
import static android.provider.Telephony.Carriers.DEFAULT_SORT_ORDER;
import static android.provider.Telephony.Carriers.EDITED_STATUS;
import static android.provider.Telephony.Carriers.MAX_CONNECTIONS;
import static android.provider.Telephony.Carriers.TIME_LIMIT_FOR_MAX_CONNECTIONS;
import static android.provider.Telephony.Carriers.MCC;
import static android.provider.Telephony.Carriers.MMSC;
import static android.provider.Telephony.Carriers.MMSPORT;
import static android.provider.Telephony.Carriers.MMSPROXY;
import static android.provider.Telephony.Carriers.MNC;
import static android.provider.Telephony.Carriers.MODEM_PERSIST;
import static android.provider.Telephony.Carriers.MTU;
import static android.provider.Telephony.Carriers.MVNO_MATCH_DATA;
import static android.provider.Telephony.Carriers.MVNO_TYPE;
import static android.provider.Telephony.Carriers.NAME;
import static android.provider.Telephony.Carriers.NETWORK_TYPE_BITMASK;
import static android.provider.Telephony.Carriers.NO_APN_SET_ID;
import static android.provider.Telephony.Carriers.NUMERIC;
import static android.provider.Telephony.Carriers.OWNED_BY;
import static android.provider.Telephony.Carriers.OWNED_BY_DPC;
import static android.provider.Telephony.Carriers.OWNED_BY_OTHERS;
import static android.provider.Telephony.Carriers.PASSWORD;
import static android.provider.Telephony.Carriers.PORT;
import static android.provider.Telephony.Carriers.PROFILE_ID;
import static android.provider.Telephony.Carriers.PROTOCOL;
import static android.provider.Telephony.Carriers.PROXY;
import static android.provider.Telephony.Carriers.ROAMING_PROTOCOL;
import static android.provider.Telephony.Carriers.SERVER;
import static android.provider.Telephony.Carriers.SKIP_464XLAT;
import static android.provider.Telephony.Carriers.SKIP_464XLAT_DEFAULT;
import static android.provider.Telephony.Carriers.SUBSCRIPTION_ID;
import static android.provider.Telephony.Carriers.TYPE;
import static android.provider.Telephony.Carriers.UNEDITED;
import static android.provider.Telephony.Carriers.USER;
import static android.provider.Telephony.Carriers.USER_DELETED;
import static android.provider.Telephony.Carriers.USER_DELETED_BUT_PRESENT_IN_XML;
import static android.provider.Telephony.Carriers.USER_EDITABLE;
import static android.provider.Telephony.Carriers.USER_EDITED;
import static android.provider.Telephony.Carriers.USER_VISIBLE;
import static android.provider.Telephony.Carriers.WAIT_TIME_RETRY;
import static android.provider.Telephony.Carriers._ID;

import android.annotation.NonNull;
import android.app.compat.CompatChanges;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.UriMatcher;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.Binder;
import android.os.Environment;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Telephony;
import android.telephony.Annotation;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.util.XmlUtils;
import android.service.carrier.IApnSourceService;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CheckedInputStream;
import java.util.zip.CRC32;

public class TelephonyProvider extends ContentProvider
{
    private static final String DATABASE_NAME = "telephony.db";
    private static final int IDLE_CONNECTION_TIMEOUT_MS = 30000;
    private static final boolean DBG = true;
    private static final boolean VDBG = false; // STOPSHIP if true

    private static final int DATABASE_VERSION = 45 << 16;
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
    private static final int URL_DELETE = 15;
    private static final int URL_DPC = 16;
    private static final int URL_DPC_ID = 17;
    private static final int URL_FILTERED = 18;
    private static final int URL_FILTERED_ID = 19;
    private static final int URL_ENFORCE_MANAGED = 20;
    // URL_PREFERAPNSET and URL_PREFERAPNSET_USING_SUBID return all APNs for the current
    // carrier which have an apn_set_id equal to the preferred APN
    // (if no preferred APN, or preferred APN has no set id, the query will return null)
    private static final int URL_PREFERAPNSET = 21;
    private static final int URL_PREFERAPNSET_USING_SUBID = 22;
    private static final int URL_SIM_APN_LIST = 23;
    private static final int URL_SIM_APN_LIST_ID = 24;
    private static final int URL_FILTERED_USING_SUBID = 25;
    private static final int URL_SIM_APN_LIST_FILTERED = 26;
    private static final int URL_SIM_APN_LIST_FILTERED_ID = 27;

    /**
     * Default value for mtu if it's not set. Moved from PhoneConstants.
     */
    private static final int UNSPECIFIED_INT = -1;

    private static final String TAG = "TelephonyProvider";
    private static final String CARRIERS_TABLE = "carriers";
    private static final String CARRIERS_TABLE_TMP = "carriers_tmp";
    private static final String SIMINFO_TABLE = "siminfo";
    private static final String SIMINFO_TABLE_TMP = "siminfo_tmp";

    private static final String PREF_FILE_APN = "preferred-apn";
    private static final String COLUMN_APN_ID = "apn_id";
    private static final String EXPLICIT_SET_CALLED = "explicit_set_called";

    private static final String PREF_FILE_FULL_APN = "preferred-full-apn";
    private static final String DB_VERSION_KEY = "version";

    private static final String BUILD_ID_FILE = "build-id";
    private static final String RO_BUILD_ID = "ro_build_id";

    private static final String ENFORCED_FILE = "dpc-apn-enforced";
    private static final String ENFORCED_KEY = "enforced";

    private static final String PREF_FILE = "telephonyprovider";
    private static final String APN_CONF_CHECKSUM = "apn_conf_checksum";

    private static final String PARTNER_APNS_PATH = "etc/apns-conf.xml";
    private static final String OEM_APNS_PATH = "telephony/apns-conf.xml";
    private static final String OTA_UPDATED_APNS_PATH = "misc/apns/apns-conf.xml";
    private static final String OLD_APNS_PATH = "etc/old-apns-conf.xml";

    private static final String DEFAULT_PROTOCOL = "IP";
    private static final String DEFAULT_ROAMING_PROTOCOL = "IP";

    private static final UriMatcher s_urlMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    private static final ContentValues s_currentNullMap;
    private static final ContentValues s_currentSetMap;

    private static final String IS_UNEDITED = EDITED_STATUS + "=" + UNEDITED;
    private static final String IS_EDITED = EDITED_STATUS + "!=" + UNEDITED;
    private static final String IS_USER_EDITED = EDITED_STATUS + "=" + USER_EDITED;
    private static final String IS_NOT_USER_EDITED = EDITED_STATUS + "!=" + USER_EDITED;
    private static final String IS_USER_DELETED = EDITED_STATUS + "=" + USER_DELETED;
    private static final String IS_NOT_USER_DELETED = EDITED_STATUS + "!=" + USER_DELETED;
    private static final String IS_USER_DELETED_BUT_PRESENT_IN_XML =
            EDITED_STATUS + "=" + USER_DELETED_BUT_PRESENT_IN_XML;
    private static final String IS_NOT_USER_DELETED_BUT_PRESENT_IN_XML =
            EDITED_STATUS + "!=" + USER_DELETED_BUT_PRESENT_IN_XML;
    private static final String IS_CARRIER_EDITED = EDITED_STATUS + "=" + CARRIER_EDITED;
    private static final String IS_NOT_CARRIER_EDITED = EDITED_STATUS + "!=" + CARRIER_EDITED;
    private static final String IS_CARRIER_DELETED = EDITED_STATUS + "=" + CARRIER_DELETED;
    private static final String IS_NOT_CARRIER_DELETED = EDITED_STATUS + "!=" + CARRIER_DELETED;
    private static final String IS_CARRIER_DELETED_BUT_PRESENT_IN_XML =
            EDITED_STATUS + "=" + CARRIER_DELETED_BUT_PRESENT_IN_XML;
    private static final String IS_NOT_CARRIER_DELETED_BUT_PRESENT_IN_XML =
            EDITED_STATUS + "!=" + CARRIER_DELETED_BUT_PRESENT_IN_XML;
    private static final String IS_OWNED_BY_DPC = OWNED_BY + "=" + OWNED_BY_DPC;
    private static final String IS_NOT_OWNED_BY_DPC = OWNED_BY + "!=" + OWNED_BY_DPC;

    private static final String ORDER_BY_SUB_ID =
            Telephony.SimInfo.COLUMN_UNIQUE_KEY_SUBSCRIPTION_ID + " ASC";

    private static final int INVALID_APN_ID = -1;
    private static final List<String> CARRIERS_UNIQUE_FIELDS = new ArrayList<String>();
    private static final Set<String> CARRIERS_BOOLEAN_FIELDS = new HashSet<String>();
    private static final Map<String, String> CARRIERS_UNIQUE_FIELDS_DEFAULTS = new HashMap();

    @VisibleForTesting
    static Boolean s_apnSourceServiceExists;

    protected final Object mLock = new Object();
    @GuardedBy("mLock")
    private IApnSourceService mIApnSourceService;
    private Injector mInjector;

    private boolean mManagedApnEnforced;

    /**
     * Available radio technologies for GSM, UMTS and CDMA.
     * Duplicates the constants from hardware/radio/include/ril.h
     * This should only be used by agents working with the ril.  Others
     * should use the equivalent TelephonyManager.NETWORK_TYPE_*
     */
    private static final int RIL_RADIO_TECHNOLOGY_UNKNOWN = 0;
    private static final int RIL_RADIO_TECHNOLOGY_GPRS = 1;
    private static final int RIL_RADIO_TECHNOLOGY_EDGE = 2;
    private static final int RIL_RADIO_TECHNOLOGY_UMTS = 3;
    private static final int RIL_RADIO_TECHNOLOGY_IS95A = 4;
    private static final int RIL_RADIO_TECHNOLOGY_IS95B = 5;
    private static final int RIL_RADIO_TECHNOLOGY_1xRTT = 6;
    private static final int RIL_RADIO_TECHNOLOGY_EVDO_0 = 7;
    private static final int RIL_RADIO_TECHNOLOGY_EVDO_A = 8;
    private static final int RIL_RADIO_TECHNOLOGY_HSDPA = 9;
    private static final int RIL_RADIO_TECHNOLOGY_HSUPA = 10;
    private static final int RIL_RADIO_TECHNOLOGY_HSPA = 11;
    private static final int RIL_RADIO_TECHNOLOGY_EVDO_B = 12;
    private static final int RIL_RADIO_TECHNOLOGY_EHRPD = 13;
    private static final int RIL_RADIO_TECHNOLOGY_LTE = 14;
    private static final int RIL_RADIO_TECHNOLOGY_HSPAP = 15;

    /**
     * GSM radio technology only supports voice. It does not support data.
     */
    private static final int RIL_RADIO_TECHNOLOGY_GSM = 16;
    private static final int RIL_RADIO_TECHNOLOGY_TD_SCDMA = 17;

    /**
     * IWLAN
     */
    private static final int RIL_RADIO_TECHNOLOGY_IWLAN = 18;

    /**
     * LTE_CA
     */
    private static final int RIL_RADIO_TECHNOLOGY_LTE_CA = 19;

    /**
     * NR(New Radio) 5G.
     */
    private static final int  RIL_RADIO_TECHNOLOGY_NR = 20;

    /**
     * The number of the radio technologies.
     */
    private static final int NEXT_RIL_RADIO_TECHNOLOGY = 21;

    private static final Map<String, Integer> MVNO_TYPE_STRING_MAP;

    static {
        // Columns not included in UNIQUE constraint: name, current, edited, user, server, password,
        // authtype, type, protocol, roaming_protocol, sub_id, modem_cognitive, max_conns,
        // wait_time, max_conns_time, mtu, bearer_bitmask, user_visible, network_type_bitmask,
        // skip_464xlat
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(NUMERIC, "");
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(MCC, "");
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(MNC, "");
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(APN, "");
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(PROXY, "");
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(PORT, "");
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(MMSPROXY, "");
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(MMSPORT, "");
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(MMSC, "");
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(CARRIER_ENABLED, "1");
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(BEARER, "0");
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(MVNO_TYPE, "");
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(MVNO_MATCH_DATA, "");
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(PROFILE_ID, "0");
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(PROTOCOL, "IP");
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(ROAMING_PROTOCOL, "IP");
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(USER_EDITABLE, "1");
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(OWNED_BY, String.valueOf(OWNED_BY_OTHERS));
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(APN_SET_ID, String.valueOf(NO_APN_SET_ID));
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(CARRIER_ID,
                String.valueOf(TelephonyManager.UNKNOWN_CARRIER_ID));

        CARRIERS_UNIQUE_FIELDS.addAll(CARRIERS_UNIQUE_FIELDS_DEFAULTS.keySet());

        // SQLite databases store bools as ints but the ContentValues objects passed in through
        // queries use bools. As a result there is some special handling of boolean fields within
        // the TelephonyProvider.
        CARRIERS_BOOLEAN_FIELDS.add(CARRIER_ENABLED);
        CARRIERS_BOOLEAN_FIELDS.add(MODEM_PERSIST);
        CARRIERS_BOOLEAN_FIELDS.add(USER_VISIBLE);
        CARRIERS_BOOLEAN_FIELDS.add(USER_EDITABLE);

        MVNO_TYPE_STRING_MAP = new ArrayMap<String, Integer>();
        MVNO_TYPE_STRING_MAP.put("spn", ApnSetting.MVNO_TYPE_SPN);
        MVNO_TYPE_STRING_MAP.put("imsi", ApnSetting.MVNO_TYPE_IMSI);
        MVNO_TYPE_STRING_MAP.put("gid", ApnSetting.MVNO_TYPE_GID);
        MVNO_TYPE_STRING_MAP.put("iccid", ApnSetting.MVNO_TYPE_ICCID);
    }

    @VisibleForTesting
    public static String getStringForCarrierTableCreation(String tableName) {
        return "CREATE TABLE " + tableName +
                "(_id INTEGER PRIMARY KEY," +
                NAME + " TEXT DEFAULT ''," +
                NUMERIC + " TEXT DEFAULT ''," +
                MCC + " TEXT DEFAULT ''," +
                MNC + " TEXT DEFAULT ''," +
                CARRIER_ID + " INTEGER DEFAULT " + TelephonyManager.UNKNOWN_CARRIER_ID  + "," +
                APN + " TEXT DEFAULT ''," +
                USER + " TEXT DEFAULT ''," +
                SERVER + " TEXT DEFAULT ''," +
                PASSWORD + " TEXT DEFAULT ''," +
                PROXY + " TEXT DEFAULT ''," +
                PORT + " TEXT DEFAULT ''," +
                MMSPROXY + " TEXT DEFAULT ''," +
                MMSPORT + " TEXT DEFAULT ''," +
                MMSC + " TEXT DEFAULT ''," +
                AUTH_TYPE + " INTEGER DEFAULT -1," +
                TYPE + " TEXT DEFAULT ''," +
                CURRENT + " INTEGER," +
                PROTOCOL + " TEXT DEFAULT " + DEFAULT_PROTOCOL + "," +
                ROAMING_PROTOCOL + " TEXT DEFAULT " + DEFAULT_ROAMING_PROTOCOL + "," +
                CARRIER_ENABLED + " BOOLEAN DEFAULT 1," + // SQLite databases store bools as ints
                BEARER + " INTEGER DEFAULT 0," +
                BEARER_BITMASK + " INTEGER DEFAULT 0," +
                NETWORK_TYPE_BITMASK + " INTEGER DEFAULT 0," +
                MVNO_TYPE + " TEXT DEFAULT ''," +
                MVNO_MATCH_DATA + " TEXT DEFAULT ''," +
                SUBSCRIPTION_ID + " INTEGER DEFAULT " +
                SubscriptionManager.INVALID_SUBSCRIPTION_ID + "," +
                PROFILE_ID + " INTEGER DEFAULT 0," +
                MODEM_PERSIST + " BOOLEAN DEFAULT 0," +
                MAX_CONNECTIONS + " INTEGER DEFAULT 0," +
                WAIT_TIME_RETRY + " INTEGER DEFAULT 0," +
                TIME_LIMIT_FOR_MAX_CONNECTIONS + " INTEGER DEFAULT 0," +
                MTU + " INTEGER DEFAULT 0," +
                EDITED_STATUS + " INTEGER DEFAULT " + UNEDITED + "," +
                USER_VISIBLE + " BOOLEAN DEFAULT 1," +
                USER_EDITABLE + " BOOLEAN DEFAULT 1," +
                OWNED_BY + " INTEGER DEFAULT " + OWNED_BY_OTHERS + "," +
                APN_SET_ID + " INTEGER DEFAULT " + NO_APN_SET_ID + "," +
                SKIP_464XLAT + " INTEGER DEFAULT " + SKIP_464XLAT_DEFAULT + "," +
                // Uniqueness collisions are used to trigger merge code so if a field is listed
                // here it means we will accept both (user edited + new apn_conf definition)
                // Columns not included in UNIQUE constraint: name, current, edited,
                // user, server, password, authtype, type, sub_id, modem_cognitive, max_conns,
                // wait_time, max_conns_time, mtu, bearer_bitmask, user_visible,
                // network_type_bitmask, skip_464xlat.
                "UNIQUE (" + TextUtils.join(", ", CARRIERS_UNIQUE_FIELDS) + "));";
    }

    @VisibleForTesting
    public static String getStringForSimInfoTableCreation(String tableName) {
        return "CREATE TABLE " + tableName + "("
                + Telephony.SimInfo.COLUMN_UNIQUE_KEY_SUBSCRIPTION_ID
                + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + Telephony.SimInfo.COLUMN_ICC_ID + " TEXT NOT NULL,"
                + Telephony.SimInfo.COLUMN_SIM_SLOT_INDEX
                + " INTEGER DEFAULT " + Telephony.SimInfo.SIM_NOT_INSERTED + ","
                + Telephony.SimInfo.COLUMN_DISPLAY_NAME + " TEXT,"
                + Telephony.SimInfo.COLUMN_CARRIER_NAME + " TEXT,"
                + Telephony.SimInfo.COLUMN_NAME_SOURCE
                + " INTEGER DEFAULT " + Telephony.SimInfo.NAME_SOURCE_CARRIER_ID + ","
                + Telephony.SimInfo.COLUMN_COLOR + " INTEGER DEFAULT "
                + Telephony.SimInfo.COLOR_DEFAULT + ","
                + Telephony.SimInfo.COLUMN_NUMBER + " TEXT,"
                + Telephony.SimInfo.COLUMN_DISPLAY_NUMBER_FORMAT
                + " INTEGER NOT NULL DEFAULT " + Telephony.SimInfo.DISPLAY_NUMBER_DEFAULT + ","
                + Telephony.SimInfo.COLUMN_DATA_ROAMING
                + " INTEGER DEFAULT " + Telephony.SimInfo.DATA_ROAMING_DISABLE + ","
                + Telephony.SimInfo.COLUMN_MCC + " INTEGER DEFAULT 0,"
                + Telephony.SimInfo.COLUMN_MNC + " INTEGER DEFAULT 0,"
                + Telephony.SimInfo.COLUMN_MCC_STRING + " TEXT,"
                + Telephony.SimInfo.COLUMN_MNC_STRING + " TEXT,"
                + Telephony.SimInfo.COLUMN_EHPLMNS + " TEXT,"
                + Telephony.SimInfo.COLUMN_HPLMNS + " TEXT,"
                + Telephony.SimInfo.COLUMN_SIM_PROVISIONING_STATUS
                + " INTEGER DEFAULT " + Telephony.SimInfo.SIM_PROVISIONED + ","
                + Telephony.SimInfo.COLUMN_IS_EMBEDDED + " INTEGER DEFAULT 0,"
                + Telephony.SimInfo.COLUMN_CARD_ID + " TEXT NOT NULL,"
                + Telephony.SimInfo.COLUMN_ACCESS_RULES + " BLOB,"
                + Telephony.SimInfo.COLUMN_ACCESS_RULES_FROM_CARRIER_CONFIGS + " BLOB,"
                + Telephony.SimInfo.COLUMN_IS_REMOVABLE + " INTEGER DEFAULT 0,"
                + Telephony.SimInfo.COLUMN_CB_EXTREME_THREAT_ALERT + " INTEGER DEFAULT 1,"
                + Telephony.SimInfo.COLUMN_CB_SEVERE_THREAT_ALERT + " INTEGER DEFAULT 1,"
                + Telephony.SimInfo.COLUMN_CB_AMBER_ALERT + " INTEGER DEFAULT 1,"
                + Telephony.SimInfo.COLUMN_CB_EMERGENCY_ALERT + " INTEGER DEFAULT 1,"
                + Telephony.SimInfo.COLUMN_CB_ALERT_SOUND_DURATION + " INTEGER DEFAULT 4,"
                + Telephony.SimInfo.COLUMN_CB_ALERT_REMINDER_INTERVAL + " INTEGER DEFAULT 0,"
                + Telephony.SimInfo.COLUMN_CB_ALERT_VIBRATE + " INTEGER DEFAULT 1,"
                + Telephony.SimInfo.COLUMN_CB_ALERT_SPEECH + " INTEGER DEFAULT 1,"
                + Telephony.SimInfo.COLUMN_CB_ETWS_TEST_ALERT + " INTEGER DEFAULT 0,"
                + Telephony.SimInfo.COLUMN_CB_CHANNEL_50_ALERT + " INTEGER DEFAULT 1,"
                + Telephony.SimInfo.COLUMN_CB_CMAS_TEST_ALERT + " INTEGER DEFAULT 0,"
                + Telephony.SimInfo.COLUMN_CB_OPT_OUT_DIALOG + " INTEGER DEFAULT 1,"
                + Telephony.SimInfo.COLUMN_ENHANCED_4G_MODE_ENABLED + " INTEGER DEFAULT -1,"
                + Telephony.SimInfo.COLUMN_VT_IMS_ENABLED + " INTEGER DEFAULT -1,"
                + Telephony.SimInfo.COLUMN_WFC_IMS_ENABLED + " INTEGER DEFAULT -1,"
                + Telephony.SimInfo.COLUMN_WFC_IMS_MODE + " INTEGER DEFAULT -1,"
                + Telephony.SimInfo.COLUMN_WFC_IMS_ROAMING_MODE + " INTEGER DEFAULT -1,"
                + Telephony.SimInfo.COLUMN_WFC_IMS_ROAMING_ENABLED + " INTEGER DEFAULT -1,"
                + Telephony.SimInfo.COLUMN_IS_OPPORTUNISTIC + " INTEGER DEFAULT 0,"
                + Telephony.SimInfo.COLUMN_GROUP_UUID + " TEXT,"
                + Telephony.SimInfo.COLUMN_IS_METERED + " INTEGER DEFAULT 1,"
                + Telephony.SimInfo.COLUMN_ISO_COUNTRY_CODE + " TEXT,"
                + Telephony.SimInfo.COLUMN_CARRIER_ID + " INTEGER DEFAULT -1,"
                + Telephony.SimInfo.COLUMN_PROFILE_CLASS + " INTEGER DEFAULT "
                + Telephony.SimInfo.PROFILE_CLASS_UNSET + ","
                + Telephony.SimInfo.COLUMN_SUBSCRIPTION_TYPE + " INTEGER DEFAULT "
                + Telephony.SimInfo.SUBSCRIPTION_TYPE_LOCAL_SIM + ","
                + Telephony.SimInfo.COLUMN_GROUP_OWNER + " TEXT,"
                + Telephony.SimInfo.COLUMN_DATA_ENABLED_OVERRIDE_RULES + " TEXT,"
                + Telephony.SimInfo.COLUMN_IMSI + " TEXT,"
                + Telephony.SimInfo.COLUMN_UICC_APPLICATIONS_ENABLED + " INTEGER DEFAULT 1,"
                + Telephony.SimInfo.COLUMN_ALLOWED_NETWORK_TYPES + " BIGINT DEFAULT -1,"
                + Telephony.SimInfo.COLUMN_IMS_RCS_UCE_ENABLED + " INTEGER DEFAULT 0"
                + ");";
    }

    static {
        s_urlMatcher.addURI("telephony", "carriers", URL_TELEPHONY);
        s_urlMatcher.addURI("telephony", "carriers/current", URL_CURRENT);
        s_urlMatcher.addURI("telephony", "carriers/#", URL_ID);
        s_urlMatcher.addURI("telephony", "carriers/restore", URL_RESTOREAPN);
        s_urlMatcher.addURI("telephony", "carriers/preferapn", URL_PREFERAPN);
        s_urlMatcher.addURI("telephony", "carriers/preferapn_no_update", URL_PREFERAPN_NO_UPDATE);
        s_urlMatcher.addURI("telephony", "carriers/preferapnset", URL_PREFERAPNSET);

        s_urlMatcher.addURI("telephony", "siminfo", URL_SIMINFO);
        s_urlMatcher.addURI("telephony", "siminfo/#", URL_SIMINFO_USING_SUBID);

        s_urlMatcher.addURI("telephony", "carriers/subId/*", URL_TELEPHONY_USING_SUBID);
        s_urlMatcher.addURI("telephony", "carriers/current/subId/*", URL_CURRENT_USING_SUBID);
        s_urlMatcher.addURI("telephony", "carriers/restore/subId/*", URL_RESTOREAPN_USING_SUBID);
        s_urlMatcher.addURI("telephony", "carriers/preferapn/subId/*", URL_PREFERAPN_USING_SUBID);
        s_urlMatcher.addURI("telephony", "carriers/preferapn_no_update/subId/*",
                URL_PREFERAPN_NO_UPDATE_USING_SUBID);
        s_urlMatcher.addURI("telephony", "carriers/preferapnset/subId/*",
                URL_PREFERAPNSET_USING_SUBID);

        s_urlMatcher.addURI("telephony", "carriers/update_db", URL_UPDATE_DB);
        s_urlMatcher.addURI("telephony", "carriers/delete", URL_DELETE);

        // Only called by DevicePolicyManager to manipulate DPC records.
        s_urlMatcher.addURI("telephony", "carriers/dpc", URL_DPC);
        // Only called by DevicePolicyManager to manipulate a DPC record with certain _ID.
        s_urlMatcher.addURI("telephony", "carriers/dpc/#", URL_DPC_ID);
        // Only called by Settings app, DcTracker and other telephony components to get APN list
        // according to whether DPC records are enforced.
        s_urlMatcher.addURI("telephony", "carriers/filtered", URL_FILTERED);
        // Only called by Settings app, DcTracker and other telephony components to get a
        // single APN according to whether DPC records are enforced.
        s_urlMatcher.addURI("telephony", "carriers/filtered/#", URL_FILTERED_ID);
        // Used by DcTracker to pass a subId.
        s_urlMatcher.addURI("telephony", "carriers/filtered/subId/*", URL_FILTERED_USING_SUBID);

        // Only Called by DevicePolicyManager to enforce DPC records.
        s_urlMatcher.addURI("telephony", "carriers/enforce_managed", URL_ENFORCE_MANAGED);
        s_urlMatcher.addURI("telephony", "carriers/sim_apn_list", URL_SIM_APN_LIST);
        s_urlMatcher.addURI("telephony", "carriers/sim_apn_list/#", URL_SIM_APN_LIST_ID);
        s_urlMatcher.addURI("telephony", "carriers/sim_apn_list/filtered",
            URL_SIM_APN_LIST_FILTERED);
        s_urlMatcher.addURI("telephony", "carriers/sim_apn_list/filtered/subId/*",
                URL_SIM_APN_LIST_FILTERED_ID);

        s_currentNullMap = new ContentValues(1);
        s_currentNullMap.put(CURRENT, "0");

        s_currentSetMap = new ContentValues(1);
        s_currentSetMap.put(CURRENT, "1");
    }

    /**
     * Unit test will subclass it to inject mocks.
     */
    @VisibleForTesting
    static class Injector {
        int binderGetCallingUid() {
            return Binder.getCallingUid();
        }
    }

    public TelephonyProvider() {
        this(new Injector());
    }

    @VisibleForTesting
    public TelephonyProvider(Injector injector) {
        mInjector = injector;
    }

    @VisibleForTesting
    public static int getVersion(Context context) {
        if (VDBG) log("getVersion:+");
        // Get the database version, combining a static schema version and the XML version
        Resources r = context.getResources();
        if (r == null) {
            loge("resources=null, return version=" + Integer.toHexString(DATABASE_VERSION));
            return DATABASE_VERSION;
        }
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

    static public ContentValues setDefaultValue(ContentValues values) {
        if (!values.containsKey(SUBSCRIPTION_ID)) {
            int subId = SubscriptionManager.getDefaultSubscriptionId();
            values.put(SUBSCRIPTION_ID, subId);
        }

        return values;
    }

    @VisibleForTesting
    public class DatabaseHelper extends SQLiteOpenHelper {
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
            // Memory optimization - close idle connections after 30s of inactivity
            setIdleConnectionTimeout(IDLE_CONNECTION_TIMEOUT_MS);
            setWriteAheadLoggingEnabled(false);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            if (DBG) log("dbh.onCreate:+ db=" + db);
            createSimInfoTable(db, SIMINFO_TABLE);
            createCarriersTable(db, CARRIERS_TABLE);
            // if CarrierSettings app is installed, we expect it to do the initializiation instead
            if (apnSourceServiceExists(mContext)) {
                log("dbh.onCreate: Skipping apply APNs from xml.");
            } else {
                log("dbh.onCreate: Apply apns from xml.");
                initDatabase(db);
            }
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
                    createSimInfoTable(db, SIMINFO_TABLE);
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

        private void createSimInfoTable(SQLiteDatabase db, String tableName) {
            if (DBG) log("dbh.createSimInfoTable:+ " + tableName);
            db.execSQL(getStringForSimInfoTableCreation(tableName));
            if (DBG) log("dbh.createSimInfoTable:-");
        }

        private void createCarriersTable(SQLiteDatabase db, String tableName) {
            // Set up the database schema
            if (DBG) log("dbh.createCarriersTable: " + tableName);
            db.execSQL(getStringForCarrierTableCreation(tableName));
            if (DBG) log("dbh.createCarriersTable:-");
        }

        private long getChecksum(File file) {
            CRC32 checkSummer = new CRC32();
            long checkSum = -1;
            try (CheckedInputStream cis =
                new CheckedInputStream(new FileInputStream(file), checkSummer)){
                byte[] buf = new byte[128];
                if(cis != null) {
                    while(cis.read(buf) >= 0) {
                        // Just read for checksum to get calculated.
                    }
                }
                checkSum = checkSummer.getValue();
                if (DBG) log("Checksum for " + file.getAbsolutePath() + " is " + checkSum);
            } catch (FileNotFoundException e) {
                loge("FileNotFoundException for " + file.getAbsolutePath() + ":" + e);
            } catch (IOException e) {
                loge("IOException for " + file.getAbsolutePath() + ":" + e);
            }

            // The RRO may have been updated in a firmware upgrade. Add checksum for the
            // resources to the total checksum so that apns in an RRO update is not missed.
            try (InputStream inputStream = mContext.getResources().
                        openRawResource(com.android.internal.R.xml.apns)) {
                byte[] array = toByteArray(inputStream);
                checkSummer.reset();
                checkSummer.update(array);
                checkSum += checkSummer.getValue();
                if (DBG) log("Checksum after adding resource is " + checkSummer.getValue());
            } catch (IOException | Resources.NotFoundException e) {
                loge("Exception when calculating checksum for internal apn resources: " + e);
            }
            return checkSum;
        }

        private byte[] toByteArray(InputStream input) throws IOException {
            byte[] buffer = new byte[128];
            int bytesRead;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }
            return output.toByteArray();
        }

        private long getApnConfChecksum() {
            SharedPreferences sp = mContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
            return sp.getLong(APN_CONF_CHECKSUM, -1);
        }

        private void setApnConfChecksum(long checksum) {
            SharedPreferences sp = mContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = sp.edit();
            editor.putLong(APN_CONF_CHECKSUM, checksum);
            editor.apply();
        }

        private File getApnConfFile() {
            // Environment.getRootDirectory() is a fancy way of saying ANDROID_ROOT or "/system".
            File confFile = new File(Environment.getRootDirectory(), PARTNER_APNS_PATH);
            File oemConfFile =  new File(Environment.getOemDirectory(), OEM_APNS_PATH);
            File updatedConfFile = new File(Environment.getDataDirectory(), OTA_UPDATED_APNS_PATH);
            File productConfFile = new File(Environment.getProductDirectory(), PARTNER_APNS_PATH);
            confFile = pickSecondIfExists(confFile, oemConfFile);
            confFile = pickSecondIfExists(confFile, productConfFile);
            confFile = pickSecondIfExists(confFile, updatedConfFile);
            return confFile;
        }

        /**
         * This function computes checksum for the file to be read and compares it against the
         * last read file. DB needs to be updated only if checksum has changed, or old checksum does
         * not exist.
         * @return true if DB should be updated with new conf file, false otherwise
         */
        private boolean apnDbUpdateNeeded() {
            File confFile = getApnConfFile();
            long newChecksum = getChecksum(confFile);
            long oldChecksum = getApnConfChecksum();
            if (DBG) log("newChecksum: " + newChecksum);
            if (DBG) log("oldChecksum: " + oldChecksum);
            if (newChecksum == oldChecksum) {
                return false;
            } else {
                return true;
            }
        }

        /**
         *  This function adds APNs from xml file(s) to db. The db may or may not be empty to begin
         *  with.
         */
        private void initDatabase(SQLiteDatabase db) {
            if (VDBG) log("dbh.initDatabase:+ db=" + db);
            // Read internal APNS data
            Resources r = mContext.getResources();
            int publicversion = -1;
            if (r != null) {
                XmlResourceParser parser = r.getXml(com.android.internal.R.xml.apns);
                try {
                    XmlUtils.beginDocument(parser, "apns");
                    publicversion = Integer.parseInt(parser.getAttributeValue(null, "version"));
                    loadApns(db, parser);
                } catch (Exception e) {
                    loge("Got exception while loading APN database." + e);
                } finally {
                    parser.close();
                }
            } else {
                loge("initDatabase: resources=null");
            }

            // Read external APNS data (partner-provided)
            XmlPullParser confparser = null;
            File confFile = getApnConfFile();

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
                db.delete(CARRIERS_TABLE, IS_USER_DELETED + " or " + IS_CARRIER_DELETED, null);

                // Change USER_DELETED_BUT_PRESENT_IN_XML to USER_DELETED
                ContentValues cv = new ContentValues();
                cv.put(EDITED_STATUS, USER_DELETED);
                db.update(CARRIERS_TABLE, cv, IS_USER_DELETED_BUT_PRESENT_IN_XML, null);

                // Change CARRIER_DELETED_BUT_PRESENT_IN_XML to CARRIER_DELETED
                cv = new ContentValues();
                cv.put(EDITED_STATUS, CARRIER_DELETED);
                db.update(CARRIERS_TABLE, cv, IS_CARRIER_DELETED_BUT_PRESENT_IN_XML, null);

                if (confreader != null) {
                    try {
                        confreader.close();
                    } catch (IOException e) {
                        // do nothing
                    }
                }

                // Update the stored checksum
                setApnConfChecksum(getChecksum(confFile));
            }
            if (VDBG) log("dbh.initDatabase:- db=" + db);

        }

        private File pickSecondIfExists(File sysApnFile, File altApnFile) {
            if (altApnFile.exists()) {
                if (DBG) log("Load APNs from " + altApnFile.getPath() +
                        " instead of " + sysApnFile.getPath());
                return altApnFile;
            } else {
                if (DBG) log("Load APNs from " + sysApnFile.getPath() +
                        " instead of " + altApnFile.getPath());
                return sysApnFile;
            }
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (DBG) {
                log("dbh.onUpgrade:+ db=" + db + " oldV=" + oldVersion + " newV=" + newVersion);
            }

            deletePreferredApnId(mContext);

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
                            " ADD COLUMN " + Telephony.SimInfo.COLUMN_MCC + " INTEGER DEFAULT 0;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE +
                            " ADD COLUMN " + Telephony.SimInfo.COLUMN_MNC + " INTEGER DEFAULT 0;");
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
                            Telephony.SimInfo.COLUMN_CARRIER_NAME + " TEXT DEFAULT '';");
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
                    c = db.query(CARRIERS_TABLE, proj, IS_UNEDITED, null, null, null, null);
                    log("dbh.onUpgrade:- after upgrading total number of rows with " + IS_UNEDITED +
                            ": " + c.getCount());
                    c.close();
                    c = db.query(CARRIERS_TABLE, proj, IS_EDITED, null, null, null, null);
                    log("dbh.onUpgrade:- after upgrading total number of rows with " + IS_EDITED +
                            ": " + c.getCount());
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
                            + Telephony.SimInfo.COLUMN_CB_EXTREME_THREAT_ALERT
                            + " INTEGER DEFAULT 1;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + Telephony.SimInfo.COLUMN_CB_SEVERE_THREAT_ALERT
                            + " INTEGER DEFAULT 1;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + Telephony.SimInfo.COLUMN_CB_AMBER_ALERT + " INTEGER DEFAULT 1;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + Telephony.SimInfo.COLUMN_CB_EMERGENCY_ALERT + " INTEGER DEFAULT 1;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + Telephony.SimInfo.COLUMN_CB_ALERT_SOUND_DURATION
                            + " INTEGER DEFAULT 4;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + Telephony.SimInfo.COLUMN_CB_ALERT_REMINDER_INTERVAL
                            + " INTEGER DEFAULT 0;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + Telephony.SimInfo.COLUMN_CB_ALERT_VIBRATE + " INTEGER DEFAULT 1;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + Telephony.SimInfo.COLUMN_CB_ALERT_SPEECH + " INTEGER DEFAULT 1;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + Telephony.SimInfo.COLUMN_CB_ETWS_TEST_ALERT + " INTEGER DEFAULT 0;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + Telephony.SimInfo.COLUMN_CB_CHANNEL_50_ALERT + " INTEGER DEFAULT 1;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + Telephony.SimInfo.COLUMN_CB_CMAS_TEST_ALERT + " INTEGER DEFAULT 0;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + Telephony.SimInfo.COLUMN_CB_OPT_OUT_DIALOG + " INTEGER DEFAULT 1;");
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
                    if (c == null || c.getColumnIndex(USER_VISIBLE) == -1) {
                        db.execSQL("ALTER TABLE " + CARRIERS_TABLE + " ADD COLUMN " +
                                USER_VISIBLE + " BOOLEAN DEFAULT 1;");
                    } else {
                        if (DBG) {
                            log("onUpgrade skipping " + CARRIERS_TABLE + " upgrade.  Column " +
                                    USER_VISIBLE + " already exists.");
                        }
                    }
                } finally {
                    if (c != null) {
                        c.close();
                    }
                }
                oldVersion = 17 << 16 | 6;
            }
            if (oldVersion < (18 << 16 | 6)) {
                try {
                    // Try to update the siminfo table. It might not be there.
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN " +
                            Telephony.SimInfo.COLUMN_SIM_PROVISIONING_STATUS + " INTEGER DEFAULT " +
                            Telephony.SimInfo.SIM_PROVISIONED + ";");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + SIMINFO_TABLE + " upgrade. " +
                                " The table will get created in onOpen.");
                    }
                }
                oldVersion = 18 << 16 | 6;
            }
            if (oldVersion < (19 << 16 | 6)) {
                // Do nothing. This is to avoid recreating table twice. Table is anyway recreated
                // for version 24 and that takes care of updates for this version as well.
                // This version added more fields protocol and roaming protocol to the primary key.
            }
            if (oldVersion < (20 << 16 | 6)) {
                try {
                    // Try to update the siminfo table. It might not be there.
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN " +
                            Telephony.SimInfo.COLUMN_IS_EMBEDDED + " INTEGER DEFAULT 0;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN " +
                            Telephony.SimInfo.COLUMN_ACCESS_RULES + " BLOB;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN " +
                            Telephony.SimInfo.COLUMN_IS_REMOVABLE + " INTEGER DEFAULT 0;");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + SIMINFO_TABLE + " upgrade. " +
                                "The table will get created in onOpen.");
                    }
                }
                oldVersion = 20 << 16 | 6;
            }
            if (oldVersion < (21 << 16 | 6)) {
                try {
                    // Try to update the carriers table. It might not be there.
                    db.execSQL("ALTER TABLE " + CARRIERS_TABLE + " ADD COLUMN " +
                            USER_EDITABLE + " INTEGER DEFAULT 1;");
                } catch (SQLiteException e) {
                    // This is possible if the column already exists which may be the case if the
                    // table was just created as part of upgrade to version 19
                    if (DBG) {
                        log("onUpgrade skipping " + CARRIERS_TABLE + " upgrade. " +
                                "The table will get created in onOpen.");
                    }
                }
                oldVersion = 21 << 16 | 6;
            }
            if (oldVersion < (22 << 16 | 6)) {
                try {
                    // Try to update the siminfo table. It might not be there.
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + Telephony.SimInfo.COLUMN_ENHANCED_4G_MODE_ENABLED
                            + " INTEGER DEFAULT -1;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + Telephony.SimInfo.COLUMN_VT_IMS_ENABLED + " INTEGER DEFAULT -1;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + Telephony.SimInfo.COLUMN_WFC_IMS_ENABLED + " INTEGER DEFAULT -1;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + Telephony.SimInfo.COLUMN_WFC_IMS_MODE + " INTEGER DEFAULT -1;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + Telephony.SimInfo.COLUMN_WFC_IMS_ROAMING_MODE + " INTEGER DEFAULT -1;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + Telephony.SimInfo.COLUMN_WFC_IMS_ROAMING_ENABLED + " INTEGER DEFAULT -1;");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + CARRIERS_TABLE + " upgrade. " +
                                "The table will get created in onOpen.");
                    }
                }
                oldVersion = 22 << 16 | 6;
            }
            if (oldVersion < (23 << 16 | 6)) {
                try {
                    db.execSQL("ALTER TABLE " + CARRIERS_TABLE + " ADD COLUMN " +
                            OWNED_BY + " INTEGER DEFAULT " + OWNED_BY_OTHERS + ";");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + CARRIERS_TABLE + " upgrade. " +
                                "The table will get created in onOpen.");
                    }
                }
                oldVersion = 23 << 16 | 6;
            }
            if (oldVersion < (24 << 16 | 6)) {
                Cursor c = null;
                String[] proj = {"_id"};
                recreateDB(db, proj, /* version */24);
                if (VDBG) {
                    c = db.query(CARRIERS_TABLE, proj, null, null, null, null, null);
                    log("dbh.onUpgrade:- after upgrading total number of rows: " + c.getCount());
                    c.close();
                    c = db.query(
                            CARRIERS_TABLE, proj, NETWORK_TYPE_BITMASK, null, null, null, null);
                    log("dbh.onUpgrade:- after upgrading total number of rows with "
                            + NETWORK_TYPE_BITMASK + ": " + c.getCount());
                    c.close();
                }
                oldVersion = 24 << 16 | 6;
            }
            if (oldVersion < (25 << 16 | 6)) {
                // Add a new column SubscriptionManager.CARD_ID into the database and set the value
                // to be the same as the existing column SubscriptionManager.ICC_ID. In order to do
                // this, we need to first make a copy of the existing SIMINFO_TABLE, set the value
                // of the new column SubscriptionManager.CARD_ID, and replace the SIMINFO_TABLE with
                // the new table.
                Cursor c = null;
                String[] proj = {Telephony.SimInfo.COLUMN_UNIQUE_KEY_SUBSCRIPTION_ID};
                recreateSimInfoDB(c, db, proj);
                if (VDBG) {
                    c = db.query(SIMINFO_TABLE, proj, null, null, null, null, null);
                    log("dbh.onUpgrade:- after upgrading " + SIMINFO_TABLE
                            + " total number of rows: " + c.getCount());
                    c.close();
                    c = db.query(SIMINFO_TABLE, proj, Telephony.SimInfo.COLUMN_CARD_ID
                                    + " IS NOT NULL", null, null, null, null);
                    log("dbh.onUpgrade:- after upgrading total number of rows with "
                            + Telephony.SimInfo.COLUMN_CARD_ID + ": " + c.getCount());
                    c.close();
                }
                oldVersion = 25 << 16 | 6;
            }
            if (oldVersion < (26 << 16 | 6)) {
                // Add a new column Carriers.APN_SET_ID into the database and set the value to
                // Carriers.NO_SET_SET by default.
                try {
                    db.execSQL("ALTER TABLE " + CARRIERS_TABLE + " ADD COLUMN " +
                            APN_SET_ID + " INTEGER DEFAULT " + NO_APN_SET_ID + ";");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + CARRIERS_TABLE + " upgrade. " +
                                "The table will get created in onOpen.");
                    }
                }
                oldVersion = 26 << 16 | 6;
            }

            if (oldVersion < (27 << 16 | 6)) {
                // Add the new MCC_STRING and MNC_STRING columns into the subscription table,
                // and attempt to populate them.
                try {
                    // Try to update the siminfo table. It might not be there.
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE +
                            " ADD COLUMN " + Telephony.SimInfo.COLUMN_MCC_STRING + " TEXT;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE +
                            " ADD COLUMN " + Telephony.SimInfo.COLUMN_MNC_STRING + " TEXT;");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + SIMINFO_TABLE + " upgrade. " +
                                " The table will get created in onOpen.");
                    }
                }
                // Migrate the old integer values over to strings
                String[] proj = {Telephony.SimInfo.COLUMN_UNIQUE_KEY_SUBSCRIPTION_ID,
                        Telephony.SimInfo.COLUMN_MCC, Telephony.SimInfo.COLUMN_MNC};
                try (Cursor c = db.query(SIMINFO_TABLE, proj, null, null, null, null, null)) {
                    while (c.moveToNext()) {
                        fillInMccMncStringAtCursor(mContext, db, c);
                    }
                }
                oldVersion = 27 << 16 | 6;
            }

            if (oldVersion < (28 << 16 | 6)) {
                try {
                    // Try to update the siminfo table. It might not be there.
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + Telephony.SimInfo.COLUMN_IS_OPPORTUNISTIC + " INTEGER DEFAULT 0;");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + SIMINFO_TABLE + " upgrade. " +
                                "The table will get created in onOpen.");
                    }
                }
                oldVersion = 28 << 16 | 6;
            }

            if (oldVersion < (29 << 16 | 6)) {
                try {
                    // Add a new column Telephony.CARRIER_ID into the database and add UNIQUE
                    // constraint into table. However, sqlite cannot add constraints to an existing
                    // table, so recreate the table.
                    String[] proj = {"_id"};
                    recreateDB(db, proj,  /* version */29);
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + CARRIERS_TABLE + " upgrade. " +
                                "The table will get created in onOpen.");
                    }
                }
                oldVersion = 29 << 16 | 6;
            }

            if (oldVersion < (30 << 16 | 6)) {
                try {
                    // Try to update the siminfo table. It might not be there.
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                        + Telephony.SimInfo.COLUMN_GROUP_UUID + " TEXT;");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + SIMINFO_TABLE + " upgrade. " +
                            "The table will get created in onOpen.");
                    }
                }
                oldVersion = 30 << 16 | 6;
            }

            if (oldVersion < (31 << 16 | 6)) {
                try {
                    // Try to update the siminfo table. It might not be there.
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + Telephony.SimInfo.COLUMN_IS_METERED + " INTEGER DEFAULT 1;");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + SIMINFO_TABLE + " upgrade. " +
                                "The table will get created in onOpen.");
                    }
                }
                oldVersion = 31 << 16 | 6;
            }

            if (oldVersion < (32 << 16 | 6)) {
                try {
                    // Try to update the siminfo table. It might not be there.
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + Telephony.SimInfo.COLUMN_ISO_COUNTRY_CODE + " TEXT;");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + SIMINFO_TABLE + " upgrade. " +
                                "The table will get created in onOpen.");
                    }
                }
                oldVersion = 32 << 16 | 6;
            }

            if (oldVersion < (33 << 16 | 6)) {
                try {
                    // Try to update the siminfo table. It might not be there.
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + Telephony.SimInfo.COLUMN_CARRIER_ID + " INTEGER DEFAULT -1;");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + SIMINFO_TABLE + " upgrade. " +
                                "The table will get created in onOpen.");
                    }
                }
                oldVersion = 33 << 16 | 6;
            }

            if (oldVersion < (34 << 16 | 6)) {
                try {
                    // Try to update the siminfo table. It might not be there.
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN " +
                            Telephony.SimInfo.COLUMN_PROFILE_CLASS + " INTEGER DEFAULT " +
                            Telephony.SimInfo.PROFILE_CLASS_UNSET + ";");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + SIMINFO_TABLE + " upgrade. " +
                                "The table will get created in onOpen.");
                    }
                }
                oldVersion = 34 << 16 | 6;
            }

            if (oldVersion < (35 << 16 | 6)) {
                try {
                    // Try to update the siminfo table. It might not be there.
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                        + Telephony.SimInfo.COLUMN_SUBSCRIPTION_TYPE + " INTEGER DEFAULT "
                        + Telephony.SimInfo.SUBSCRIPTION_TYPE_LOCAL_SIM + ";");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + SIMINFO_TABLE + " upgrade. " +
                            "The table will get created in onOpen.");
                    }
                }
                oldVersion = 35 << 16 | 6;
            }

            if (oldVersion < (36 << 16 | 6)) {
                // Add a new column Carriers.SKIP_464XLAT into the database and set the value to
                // SKIP_464XLAT_DEFAULT.
                try {
                    db.execSQL("ALTER TABLE " + CARRIERS_TABLE + " ADD COLUMN " +
                            SKIP_464XLAT + " INTEGER DEFAULT " + SKIP_464XLAT_DEFAULT + ";");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + CARRIERS_TABLE + " upgrade. " +
                                "The table will get created in onOpen.");
                    }
                }
                oldVersion = 36 << 16 | 6;
            }

            if (oldVersion < (37 << 16 | 6)) {
                // Add new columns Telephony.SimInfo.EHPLMNS and Telephony.SimInfo.HPLMNS into
                // the database.
                try {
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE +
                            " ADD COLUMN " + Telephony.SimInfo.COLUMN_EHPLMNS + " TEXT;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE +
                            " ADD COLUMN " + Telephony.SimInfo.COLUMN_HPLMNS + " TEXT;");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + SIMINFO_TABLE + " upgrade for ehplmns. " +
                                "The table will get created in onOpen.");
                    }
                }
                oldVersion = 37 << 16 | 6;
            }

            if (oldVersion < (39 << 16 | 6)) {
                try {
                    // Try to update the siminfo table. It might not be there.
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + Telephony.SimInfo.COLUMN_GROUP_OWNER + " TEXT;");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + SIMINFO_TABLE + " upgrade. " +
                                "The table will get created in onOpen.");
                    }
                }
                oldVersion = 39 << 16 | 6;
            }

            if (oldVersion < (40 << 16 | 6)) {
                try {
                    // Try to update the siminfo table. It might not be there.
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + Telephony.SimInfo.COLUMN_DATA_ENABLED_OVERRIDE_RULES + " TEXT;");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + SIMINFO_TABLE + " upgrade. " +
                                "The table will get created in onOpen.");
                    }
                }
                oldVersion = 40 << 16 | 6;
            }

            if (oldVersion < (41 << 16 | 6)) {
                try {
                    // Try to update the siminfo table. It might not be there.
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + Telephony.SimInfo.COLUMN_IMSI + " TEXT;");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + SIMINFO_TABLE + " upgrade. " +
                                "The table will get created in onOpen.");
                    }
                }
                oldVersion = 41 << 16 | 6;
            }

            if (oldVersion < (42 << 16 | 6)) {
                try {
                    // Try to update the siminfo table. It might not be there.
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN " +
                            Telephony.SimInfo.COLUMN_ACCESS_RULES_FROM_CARRIER_CONFIGS + " BLOB;");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + SIMINFO_TABLE + " upgrade. " +
                                "The table will get created in onOpen.");
                    }
                }
            }

            if (oldVersion < (43 << 16 | 6)) {
                try {
                    // Try to update the siminfo table. It might not be there.
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + Telephony.SimInfo.COLUMN_UICC_APPLICATIONS_ENABLED
                            + " INTEGER DEFAULT 1;");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + SIMINFO_TABLE + " upgrade. " +
                                "The table will get created in onOpen.");
                    }
                }
                oldVersion = 43 << 16 | 6;
            }

            if (oldVersion < (44 << 16 | 6)) {
                try {
                    // Try to update the siminfo table. It might not be there.
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + Telephony.SimInfo.COLUMN_ALLOWED_NETWORK_TYPES
                            + " BIGINT DEFAULT -1;");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + SIMINFO_TABLE + " upgrade. " +
                                "The table will get created in onOpen.");
                    }
                }
                oldVersion = 44 << 16 | 6;
            }

            if (oldVersion < (45 << 16 | 6)) {
                try {
                    // Try to update the siminfo table. It might not be there.
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + Telephony.SimInfo.COLUMN_IMS_RCS_UCE_ENABLED
                            + " INTEGER DEFAULT 0;");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + SIMINFO_TABLE + " upgrade. " +
                                "The table will get created in onOpen.");
                    }
                }
                oldVersion = 45 << 16 | 6;
            }

            if (DBG) {
                log("dbh.onUpgrade:- db=" + db + " oldV=" + oldVersion + " newV=" + newVersion);
            }
            // when adding fields to onUpgrade, also add a unit test to TelephonyDatabaseHelperTest
            // and update the DATABASE_VERSION field and add a column in copyAllApnValues
        }

        private void recreateSimInfoDB(Cursor c, SQLiteDatabase db, String[] proj) {
            if (VDBG) {
                c = db.query(SIMINFO_TABLE, proj, null, null, null, null, null);
                log("dbh.onUpgrade:+ before upgrading " + SIMINFO_TABLE +
                        " total number of rows: " + c.getCount());
                c.close();
            }

            // Sort in ascending order by subscription id to make sure the rows do not get flipped
            // during the query and added in the new sim info table in another order (sub id is
            // stored in settings between migrations).
            c = db.query(SIMINFO_TABLE, null, null, null, null, null, ORDER_BY_SUB_ID);

            db.execSQL("DROP TABLE IF EXISTS " + SIMINFO_TABLE_TMP);

            createSimInfoTable(db, SIMINFO_TABLE_TMP);

            copySimInfoDataToTmpTable(db, c);
            c.close();

            db.execSQL("DROP TABLE IF EXISTS " + SIMINFO_TABLE);

            db.execSQL("ALTER TABLE " + SIMINFO_TABLE_TMP + " rename to " + SIMINFO_TABLE + ";");

        }

        private void copySimInfoDataToTmpTable(SQLiteDatabase db, Cursor c) {
            // Move entries from SIMINFO_TABLE to SIMINFO_TABLE_TMP
            if (c != null) {
                while (c.moveToNext()) {
                    ContentValues cv = new ContentValues();
                    copySimInfoValuesV24(cv, c);
                    // The card ID is supposed to be the ICCID of the profile for UICC card, and
                    // the EID of the card for eUICC card. Since EID is unknown for old entries in
                    // SIMINFO_TABLE, we use ICCID as the card ID for all the old entries while
                    // upgrading the SIMINFO_TABLE. In UiccController, both the card ID and ICCID
                    // will be checked when user queries the slot information using the card ID
                    // from the database.
                    getCardIdfromIccid(cv, c);
                    try {
                        db.insert(SIMINFO_TABLE_TMP, null, cv);
                        if (VDBG) {
                            log("dbh.copySimInfoDataToTmpTable: db.insert returned >= 0; " +
                                "insert successful for cv " + cv);
                        }
                    } catch (SQLException e) {
                        if (VDBG)
                            log("dbh.copySimInfoDataToTmpTable insertWithOnConflict exception " +
                                e + " for cv " + cv);
                    }
                }
            }
        }

        private void copySimInfoValuesV24(ContentValues cv, Cursor c) {
            // String vals
            getStringValueFromCursor(cv, c, Telephony.SimInfo.COLUMN_ICC_ID);
            getStringValueFromCursor(cv, c, Telephony.SimInfo.COLUMN_DISPLAY_NAME);
            getStringValueFromCursor(cv, c, Telephony.SimInfo.COLUMN_CARRIER_NAME);
            getStringValueFromCursor(cv, c, Telephony.SimInfo.COLUMN_NUMBER);

            // bool/int vals
            getIntValueFromCursor(cv, c, Telephony.SimInfo.COLUMN_SIM_SLOT_INDEX);
            getIntValueFromCursor(cv, c, Telephony.SimInfo.COLUMN_NAME_SOURCE);
            getIntValueFromCursor(cv, c, Telephony.SimInfo.COLUMN_COLOR);
            getIntValueFromCursor(cv, c, Telephony.SimInfo.COLUMN_DISPLAY_NUMBER_FORMAT);
            getIntValueFromCursor(cv, c, Telephony.SimInfo.COLUMN_DATA_ROAMING);
            getIntValueFromCursor(cv, c, Telephony.SimInfo.COLUMN_MCC);
            getIntValueFromCursor(cv, c, Telephony.SimInfo.COLUMN_MNC);
            getIntValueFromCursor(cv, c, Telephony.SimInfo.COLUMN_SIM_PROVISIONING_STATUS);
            getIntValueFromCursor(cv, c, Telephony.SimInfo.COLUMN_IS_EMBEDDED);
            getIntValueFromCursor(cv, c, Telephony.SimInfo.COLUMN_IS_REMOVABLE);
            getIntValueFromCursor(cv, c, Telephony.SimInfo.COLUMN_CB_EXTREME_THREAT_ALERT);
            getIntValueFromCursor(cv, c, Telephony.SimInfo.COLUMN_CB_SEVERE_THREAT_ALERT);
            getIntValueFromCursor(cv, c, Telephony.SimInfo.COLUMN_CB_AMBER_ALERT);
            getIntValueFromCursor(cv, c, Telephony.SimInfo.COLUMN_CB_EMERGENCY_ALERT);
            getIntValueFromCursor(cv, c, Telephony.SimInfo.COLUMN_CB_ALERT_SOUND_DURATION);
            getIntValueFromCursor(cv, c, Telephony.SimInfo.COLUMN_CB_ALERT_REMINDER_INTERVAL);
            getIntValueFromCursor(cv, c, Telephony.SimInfo.COLUMN_CB_ALERT_VIBRATE);
            getIntValueFromCursor(cv, c, Telephony.SimInfo.COLUMN_CB_ALERT_SPEECH);
            getIntValueFromCursor(cv, c, Telephony.SimInfo.COLUMN_CB_ETWS_TEST_ALERT);
            getIntValueFromCursor(cv, c, Telephony.SimInfo.COLUMN_CB_CHANNEL_50_ALERT);
            getIntValueFromCursor(cv, c, Telephony.SimInfo.COLUMN_CB_CMAS_TEST_ALERT);
            getIntValueFromCursor(cv, c, Telephony.SimInfo.COLUMN_CB_OPT_OUT_DIALOG);
            getIntValueFromCursor(cv, c, Telephony.SimInfo.COLUMN_ENHANCED_4G_MODE_ENABLED);
            getIntValueFromCursor(cv, c, Telephony.SimInfo.COLUMN_VT_IMS_ENABLED);
            getIntValueFromCursor(cv, c, Telephony.SimInfo.COLUMN_WFC_IMS_ENABLED);
            getIntValueFromCursor(cv, c, Telephony.SimInfo.COLUMN_WFC_IMS_MODE);
            getIntValueFromCursor(cv, c, Telephony.SimInfo.COLUMN_WFC_IMS_ROAMING_MODE);
            getIntValueFromCursor(cv, c, Telephony.SimInfo.COLUMN_WFC_IMS_ROAMING_ENABLED);

            // Blob vals
            getBlobValueFromCursor(cv, c, Telephony.SimInfo.COLUMN_ACCESS_RULES);
        }

        private void getCardIdfromIccid(ContentValues cv, Cursor c) {
            int columnIndex = c.getColumnIndex(Telephony.SimInfo.COLUMN_ICC_ID);
            if (columnIndex != -1) {
                String fromCursor = c.getString(columnIndex);
                if (!TextUtils.isEmpty(fromCursor)) {
                    cv.put(Telephony.SimInfo.COLUMN_CARD_ID, fromCursor);
                }
            }
        }

        private void recreateDB(SQLiteDatabase db, String[] proj, int version) {
            // Upgrade steps are:
            // 1. Create a temp table- done in createCarriersTable()
            // 2. copy over APNs from old table to new table - done in copyDataToTmpTable()
            // 3. Drop the existing table.
            // 4. Copy over the tmp table.
            Cursor c;
            if (VDBG) {
                c = db.query(CARRIERS_TABLE, proj, null, null, null, null, null);
                log("dbh.onUpgrade:- before upgrading total number of rows: " + c.getCount());
                c.close();
            }

            c = db.query(CARRIERS_TABLE, null, null, null, null, null, null);

            if (VDBG) {
                log("dbh.onUpgrade:- starting data copy of existing rows: " +
                        + ((c == null) ? 0 : c.getCount()));
            }

            db.execSQL("DROP TABLE IF EXISTS " + CARRIERS_TABLE_TMP);

            createCarriersTable(db, CARRIERS_TABLE_TMP);

            copyDataToTmpTable(db, c, version);
            c.close();

            db.execSQL("DROP TABLE IF EXISTS " + CARRIERS_TABLE);

            db.execSQL("ALTER TABLE " + CARRIERS_TABLE_TMP + " rename to " + CARRIERS_TABLE + ";");
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
                // APNs cannot be preserved. Log an error message so that OEMs know they need to
                // include old apns file for comparison.
                loge("PRESERVEUSERANDCARRIERAPNS: " + OLD_APNS_PATH +
                        " NOT FOUND. IT IS NEEDED TO UPGRADE FROM OLDER VERSIONS OF APN " +
                        "DB WHILE PRESERVING USER/CARRIER ADDED/EDITED ENTRIES.");
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

        private String queryValFirst(String field) {
            return field + "=?";
        }

        private String queryVal(String field) {
            return " and " + field + "=?";
        }

        private String queryValOrNull(String field) {
            return " and (" + field + "=? or " + field + " is null)";
        }

        private String queryVal2OrNull(String field) {
            return " and (" + field + "=? or " + field + "=? or " + field + " is null)";
        }

        private void deleteRow(SQLiteDatabase db, ContentValues values) {
            if (VDBG) log("deleteRow");
            String where = queryValFirst(NUMERIC) +
                    queryVal(MNC) +
                    queryVal(MNC) +
                    queryValOrNull(APN) +
                    queryValOrNull(USER) +
                    queryValOrNull(SERVER) +
                    queryValOrNull(PASSWORD) +
                    queryValOrNull(PROXY) +
                    queryValOrNull(PORT) +
                    queryValOrNull(MMSPROXY) +
                    queryValOrNull(MMSPORT) +
                    queryValOrNull(MMSC) +
                    queryValOrNull(AUTH_TYPE) +
                    queryValOrNull(TYPE) +
                    queryValOrNull(PROTOCOL) +
                    queryValOrNull(ROAMING_PROTOCOL) +
                    queryVal2OrNull(CARRIER_ENABLED) +
                    queryValOrNull(BEARER) +
                    queryValOrNull(MVNO_TYPE) +
                    queryValOrNull(MVNO_MATCH_DATA) +
                    queryValOrNull(PROFILE_ID) +
                    queryVal2OrNull(MODEM_PERSIST) +
                    queryValOrNull(MAX_CONNECTIONS) +
                    queryValOrNull(WAIT_TIME_RETRY) +
                    queryValOrNull(TIME_LIMIT_FOR_MAX_CONNECTIONS) +
                    queryValOrNull(MTU);
            String[] whereArgs = new String[29];
            int i = 0;
            whereArgs[i++] = values.getAsString(NUMERIC);
            whereArgs[i++] = values.getAsString(MCC);
            whereArgs[i++] = values.getAsString(MNC);
            whereArgs[i++] = values.getAsString(NAME);
            whereArgs[i++] = values.containsKey(APN) ?
                    values.getAsString(APN) : "";
            whereArgs[i++] = values.containsKey(USER) ?
                    values.getAsString(USER) : "";
            whereArgs[i++] = values.containsKey(SERVER) ?
                    values.getAsString(SERVER) : "";
            whereArgs[i++] = values.containsKey(PASSWORD) ?
                    values.getAsString(PASSWORD) : "";
            whereArgs[i++] = values.containsKey(PROXY) ?
                    values.getAsString(PROXY) : "";
            whereArgs[i++] = values.containsKey(PORT) ?
                    values.getAsString(PORT) : "";
            whereArgs[i++] = values.containsKey(MMSPROXY) ?
                    values.getAsString(MMSPROXY) : "";
            whereArgs[i++] = values.containsKey(MMSPORT) ?
                    values.getAsString(MMSPORT) : "";
            whereArgs[i++] = values.containsKey(MMSC) ?
                    values.getAsString(MMSC) : "";
            whereArgs[i++] = values.containsKey(AUTH_TYPE) ?
                    values.getAsString(AUTH_TYPE) : "-1";
            whereArgs[i++] = values.containsKey(TYPE) ?
                    values.getAsString(TYPE) : "";
            whereArgs[i++] = values.containsKey(PROTOCOL) ?
                    values.getAsString(PROTOCOL) : DEFAULT_PROTOCOL;
            whereArgs[i++] = values.containsKey(ROAMING_PROTOCOL) ?
                    values.getAsString(ROAMING_PROTOCOL) : DEFAULT_ROAMING_PROTOCOL;

            if (values.containsKey(CARRIER_ENABLED)) {
                whereArgs[i++] = convertStringToBoolString(values.getAsString(CARRIER_ENABLED));
                whereArgs[i++] = convertStringToIntString(values.getAsString(CARRIER_ENABLED));
            } else {
                String defaultIntString = CARRIERS_UNIQUE_FIELDS_DEFAULTS.get(CARRIER_ENABLED);
                whereArgs[i++] = convertStringToBoolString(defaultIntString);
                whereArgs[i++] = defaultIntString;
            }

            whereArgs[i++] = values.containsKey(BEARER) ?
                    values.getAsString(BEARER) : "0";
            whereArgs[i++] = values.containsKey(MVNO_TYPE) ?
                    values.getAsString(MVNO_TYPE) : "";
            whereArgs[i++] = values.containsKey(MVNO_MATCH_DATA) ?
                    values.getAsString(MVNO_MATCH_DATA) : "";
            whereArgs[i++] = values.containsKey(PROFILE_ID) ?
                    values.getAsString(PROFILE_ID) : "0";

            if (values.containsKey(MODEM_PERSIST) &&
                    (values.getAsString(MODEM_PERSIST).
                            equalsIgnoreCase("true") ||
                            values.getAsString(MODEM_PERSIST).equals("1"))) {
                whereArgs[i++] = "true";
                whereArgs[i++] = "1";
            } else {
                whereArgs[i++] = "false";
                whereArgs[i++] = "0";
            }

            whereArgs[i++] = values.containsKey(MAX_CONNECTIONS) ?
                    values.getAsString(MAX_CONNECTIONS) : "0";
            whereArgs[i++] = values.containsKey(WAIT_TIME_RETRY) ?
                    values.getAsString(WAIT_TIME_RETRY) : "0";
            whereArgs[i++] = values.containsKey(TIME_LIMIT_FOR_MAX_CONNECTIONS) ?
                    values.getAsString(TIME_LIMIT_FOR_MAX_CONNECTIONS) : "0";
            whereArgs[i++] = values.containsKey(MTU) ?
                    values.getAsString(MTU) : "0";

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

        private void copyDataToTmpTable(SQLiteDatabase db, Cursor c, int version) {
            // Move entries from CARRIERS_TABLE to CARRIERS_TABLE_TMP
            if (c != null) {
                while (c.moveToNext()) {
                    ContentValues cv = new ContentValues();
                    copyAllApnValues(cv, c);
                    if (version == 24) {
                        // Sync bearer bitmask and network type bitmask
                        getNetworkTypeBitmaskFromCursor(cv, c);
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
                    }
                }
            }
        }

        private void copyApnValuesV17(ContentValues cv, Cursor c) {
            // Include only non-null values in cv so that null values can be replaced
            // with default if there's a default value for the field

            // String vals
            getStringValueFromCursor(cv, c, NAME);
            getStringValueFromCursor(cv, c, NUMERIC);
            getStringValueFromCursor(cv, c, MCC);
            getStringValueFromCursor(cv, c, MNC);
            getStringValueFromCursor(cv, c, APN);
            getStringValueFromCursor(cv, c, USER);
            getStringValueFromCursor(cv, c, SERVER);
            getStringValueFromCursor(cv, c, PASSWORD);
            getStringValueFromCursor(cv, c, PROXY);
            getStringValueFromCursor(cv, c, PORT);
            getStringValueFromCursor(cv, c, MMSPROXY);
            getStringValueFromCursor(cv, c, MMSPORT);
            getStringValueFromCursor(cv, c, MMSC);
            getStringValueFromCursor(cv, c, TYPE);
            getStringValueFromCursor(cv, c, PROTOCOL);
            getStringValueFromCursor(cv, c, ROAMING_PROTOCOL);
            getStringValueFromCursor(cv, c, MVNO_TYPE);
            getStringValueFromCursor(cv, c, MVNO_MATCH_DATA);

            // bool/int vals
            getIntValueFromCursor(cv, c, AUTH_TYPE);
            getIntValueFromCursor(cv, c, CURRENT);
            getIntValueFromCursor(cv, c, CARRIER_ENABLED);
            getIntValueFromCursor(cv, c, BEARER);
            getIntValueFromCursor(cv, c, SUBSCRIPTION_ID);
            getIntValueFromCursor(cv, c, PROFILE_ID);
            getIntValueFromCursor(cv, c, MODEM_PERSIST);
            getIntValueFromCursor(cv, c, MAX_CONNECTIONS);
            getIntValueFromCursor(cv, c, WAIT_TIME_RETRY);
            getIntValueFromCursor(cv, c, TIME_LIMIT_FOR_MAX_CONNECTIONS);
            getIntValueFromCursor(cv, c, MTU);
            getIntValueFromCursor(cv, c, BEARER_BITMASK);
            getIntValueFromCursor(cv, c, EDITED_STATUS);
            getIntValueFromCursor(cv, c, USER_VISIBLE);
        }

        private void copyAllApnValues(ContentValues cv, Cursor c) {
            // String vals
            getStringValueFromCursor(cv, c, NAME);
            getStringValueFromCursor(cv, c, NUMERIC);
            getStringValueFromCursor(cv, c, MCC);
            getStringValueFromCursor(cv, c, MNC);
            getStringValueFromCursor(cv, c, APN);
            getStringValueFromCursor(cv, c, USER);
            getStringValueFromCursor(cv, c, SERVER);
            getStringValueFromCursor(cv, c, PASSWORD);
            getStringValueFromCursor(cv, c, PROXY);
            getStringValueFromCursor(cv, c, PORT);
            getStringValueFromCursor(cv, c, MMSPROXY);
            getStringValueFromCursor(cv, c, MMSPORT);
            getStringValueFromCursor(cv, c, MMSC);
            getStringValueFromCursor(cv, c, TYPE);
            getStringValueFromCursor(cv, c, PROTOCOL);
            getStringValueFromCursor(cv, c, ROAMING_PROTOCOL);
            getStringValueFromCursor(cv, c, MVNO_TYPE);
            getStringValueFromCursor(cv, c, MVNO_MATCH_DATA);

            // bool/int vals
            getIntValueFromCursor(cv, c, AUTH_TYPE);
            getIntValueFromCursor(cv, c, CURRENT);
            getIntValueFromCursor(cv, c, CARRIER_ENABLED);
            getIntValueFromCursor(cv, c, BEARER);
            getIntValueFromCursor(cv, c, SUBSCRIPTION_ID);
            getIntValueFromCursor(cv, c, PROFILE_ID);
            getIntValueFromCursor(cv, c, MODEM_PERSIST);
            getIntValueFromCursor(cv, c, MAX_CONNECTIONS);
            getIntValueFromCursor(cv, c, WAIT_TIME_RETRY);
            getIntValueFromCursor(cv, c, TIME_LIMIT_FOR_MAX_CONNECTIONS);
            getIntValueFromCursor(cv, c, MTU);
            getIntValueFromCursor(cv, c, NETWORK_TYPE_BITMASK);
            getIntValueFromCursor(cv, c, BEARER_BITMASK);
            getIntValueFromCursor(cv, c, EDITED_STATUS);
            getIntValueFromCursor(cv, c, USER_VISIBLE);
            getIntValueFromCursor(cv, c, USER_EDITABLE);
            getIntValueFromCursor(cv, c, OWNED_BY);
            getIntValueFromCursor(cv, c, APN_SET_ID);
            getIntValueFromCursor(cv, c, SKIP_464XLAT);
        }

        private void copyPreservedApnsToNewTable(SQLiteDatabase db, Cursor c) {
            // Move entries from CARRIERS_TABLE to CARRIERS_TABLE_TMP
            if (c != null && mContext.getResources() != null) {
                try {
                    String[] persistApnsForPlmns = mContext.getResources().getStringArray(
                            R.array.persist_apns_for_plmn);
                    while (c.moveToNext()) {
                        ContentValues cv = new ContentValues();
                        String val;
                        // Using V17 copy function for V15 upgrade. This should be fine since it handles
                        // columns that may not exist properly (getStringValueFromCursor() and
                        // getIntValueFromCursor() handle column index -1)
                        copyApnValuesV17(cv, c);
                        // Change bearer to a bitmask
                        String bearerStr = c.getString(c.getColumnIndex(BEARER));
                        if (!TextUtils.isEmpty(bearerStr)) {
                            int bearer_bitmask = getBitmaskForTech(Integer.parseInt(bearerStr));
                            cv.put(BEARER_BITMASK, bearer_bitmask);

                            int networkTypeBitmask = rilRadioTechnologyToNetworkTypeBitmask(
                                    Integer.parseInt(bearerStr));
                            cv.put(NETWORK_TYPE_BITMASK, networkTypeBitmask);
                        }

                        int userEditedColumnIdx = c.getColumnIndex("user_edited");
                        if (userEditedColumnIdx != -1) {
                            String user_edited = c.getString(userEditedColumnIdx);
                            if (!TextUtils.isEmpty(user_edited)) {
                                cv.put(EDITED_STATUS, new Integer(user_edited));
                            }
                        } else {
                            cv.put(EDITED_STATUS, CARRIER_EDITED);
                        }

                        // New EDITED column. Default value (UNEDITED) will
                        // be used for all rows except for non-mvno entries for plmns indicated
                        // by resource: those will be set to CARRIER_EDITED to preserve
                        // their current values
                        val = c.getString(c.getColumnIndex(NUMERIC));
                        for (String s : persistApnsForPlmns) {
                            if (!TextUtils.isEmpty(val) && val.equals(s) &&
                                    (!cv.containsKey(MVNO_TYPE) ||
                                            TextUtils.isEmpty(cv.getAsString(MVNO_TYPE)))) {
                                if (userEditedColumnIdx == -1) {
                                    cv.put(EDITED_STATUS, CARRIER_EDITED);
                                } else { // if (oldVersion == 14) -- if db had user_edited column
                                    if (cv.getAsInteger(EDITED_STATUS) == USER_EDITED) {
                                        cv.put(EDITED_STATUS, CARRIER_EDITED);
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
                            Cursor oldRow = selectConflictingRow(db,
                                    CARRIERS_TABLE_TMP, cv);
                            if (oldRow != null) {
                                ContentValues mergedValues = new ContentValues();
                                mergeFieldsAndUpdateDb(db, CARRIERS_TABLE_TMP, oldRow, cv,
                                        mergedValues, true, mContext);
                                oldRow.close();
                            }
                        }
                    }
                } catch (Resources.NotFoundException e) {
                    loge("array.persist_apns_for_plmn is not found");
                    return;
                }
            }
        }

        private void getStringValueFromCursor(ContentValues cv, Cursor c, String key) {
            int columnIndex = c.getColumnIndex(key);
            if (columnIndex != -1) {
                String fromCursor = c.getString(columnIndex);
                if (fromCursor != null) {
                    cv.put(key, fromCursor);
                }
            }
        }

        /**
         * If NETWORK_TYPE_BITMASK does not exist (upgrade from version 23 to version 24), generate
         * NETWORK_TYPE_BITMASK with the use of BEARER_BITMASK. If NETWORK_TYPE_BITMASK existed
         * (upgrade from version 24 to forward), always map NETWORK_TYPE_BITMASK to BEARER_BITMASK.
         */
        private void getNetworkTypeBitmaskFromCursor(ContentValues cv, Cursor c) {
            int columnIndex = c.getColumnIndex(NETWORK_TYPE_BITMASK);
            if (columnIndex != -1) {
                getStringValueFromCursor(cv, c, NETWORK_TYPE_BITMASK);
                // Map NETWORK_TYPE_BITMASK to BEARER_BITMASK if NETWORK_TYPE_BITMASK existed;
                String fromCursor = c.getString(columnIndex);
                if (!TextUtils.isEmpty(fromCursor) && fromCursor.matches("\\d+")) {
                    int networkBitmask = Integer.valueOf(fromCursor);
                    int bearerBitmask = convertNetworkTypeBitmaskToBearerBitmask(networkBitmask);
                    cv.put(BEARER_BITMASK, String.valueOf(bearerBitmask));
                }
                return;
            }
            columnIndex = c.getColumnIndex(BEARER_BITMASK);
            if (columnIndex != -1) {
                String fromCursor = c.getString(columnIndex);
                if (!TextUtils.isEmpty(fromCursor) && fromCursor.matches("\\d+")) {
                    int bearerBitmask = Integer.valueOf(fromCursor);
                    int networkBitmask = convertBearerBitmaskToNetworkTypeBitmask(bearerBitmask);
                    cv.put(NETWORK_TYPE_BITMASK, String.valueOf(networkBitmask));
                }
            }
        }

        private void getIntValueFromCursor(ContentValues cv, Cursor c, String key) {
            int columnIndex = c.getColumnIndex(key);
            if (columnIndex != -1) {
                String fromCursor = c.getString(columnIndex);
                if (!TextUtils.isEmpty(fromCursor)) {
                    try {
                        cv.put(key, new Integer(fromCursor));
                    } catch (NumberFormatException nfe) {
                        // do nothing
                    }
                }
            }
        }

        private void getBlobValueFromCursor(ContentValues cv, Cursor c, String key) {
            int columnIndex = c.getColumnIndex(key);
            if (columnIndex != -1) {
                byte[] fromCursor = c.getBlob(columnIndex);
                if (fromCursor != null) {
                    cv.put(key, fromCursor);
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

            map.put(NUMERIC, numeric);
            map.put(MCC, mcc);
            map.put(MNC, mnc);
            map.put(NAME, parser.getAttributeValue(null, "carrier"));

            // do not add NULL to the map so that default values can be inserted in db
            addStringAttribute(parser, "apn", map, APN);
            addStringAttribute(parser, "user", map, USER);
            addStringAttribute(parser, "server", map, SERVER);
            addStringAttribute(parser, "password", map, PASSWORD);
            addStringAttribute(parser, "proxy", map, PROXY);
            addStringAttribute(parser, "port", map, PORT);
            addStringAttribute(parser, "mmsproxy", map, MMSPROXY);
            addStringAttribute(parser, "mmsport", map, MMSPORT);
            addStringAttribute(parser, "mmsc", map, MMSC);

            String apnType = parser.getAttributeValue(null, "type");
            if (apnType != null) {
                // Remove spaces before putting it in the map.
                apnType = apnType.replaceAll("\\s+", "");
                map.put(TYPE, apnType);
            }

            addStringAttribute(parser, "protocol", map, PROTOCOL);
            addStringAttribute(parser, "roaming_protocol", map, ROAMING_PROTOCOL);

            addIntAttribute(parser, "authtype", map, AUTH_TYPE);
            addIntAttribute(parser, "bearer", map, BEARER);
            addIntAttribute(parser, "profile_id", map, PROFILE_ID);
            addIntAttribute(parser, "max_conns", map, MAX_CONNECTIONS);
            addIntAttribute(parser, "wait_time", map, WAIT_TIME_RETRY);
            addIntAttribute(parser, "max_conns_time", map, TIME_LIMIT_FOR_MAX_CONNECTIONS);
            addIntAttribute(parser, "mtu", map, MTU);
            addIntAttribute(parser, "apn_set_id", map, APN_SET_ID);
            addIntAttribute(parser, "carrier_id", map, CARRIER_ID);
            addIntAttribute(parser, "skip_464xlat", map, SKIP_464XLAT);

            addBoolAttribute(parser, "carrier_enabled", map, CARRIER_ENABLED);
            addBoolAttribute(parser, "modem_cognitive", map, MODEM_PERSIST);
            addBoolAttribute(parser, "user_visible", map, USER_VISIBLE);
            addBoolAttribute(parser, "user_editable", map, USER_EDITABLE);

            int networkTypeBitmask = 0;
            String networkTypeList = parser.getAttributeValue(null, "network_type_bitmask");
            if (networkTypeList != null) {
                networkTypeBitmask = getBitmaskFromString(networkTypeList);
            }
            map.put(NETWORK_TYPE_BITMASK, networkTypeBitmask);

            int bearerBitmask = 0;
            if (networkTypeList != null) {
                bearerBitmask = convertNetworkTypeBitmaskToBearerBitmask(networkTypeBitmask);
            } else {
                String bearerList = parser.getAttributeValue(null, "bearer_bitmask");
                if (bearerList != null) {
                    bearerBitmask = getBitmaskFromString(bearerList);
                }
                // Update the network type bitmask to keep them sync.
                networkTypeBitmask = convertBearerBitmaskToNetworkTypeBitmask(bearerBitmask);
                map.put(NETWORK_TYPE_BITMASK, networkTypeBitmask);
            }
            map.put(BEARER_BITMASK, bearerBitmask);

            String mvno_type = parser.getAttributeValue(null, "mvno_type");
            if (mvno_type != null) {
                String mvno_match_data = parser.getAttributeValue(null, "mvno_match_data");
                if (mvno_match_data != null) {
                    map.put(MVNO_TYPE, mvno_type);
                    map.put(MVNO_MATCH_DATA, mvno_match_data);
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

        private void insertAddingDefaults(SQLiteDatabase db, ContentValues row) {
            row = setDefaultValue(row);
            try {
                db.insertWithOnConflict(CARRIERS_TABLE, null, row, SQLiteDatabase.CONFLICT_ABORT);
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
                    int edited = oldRow.getInt(oldRow.getColumnIndex(EDITED_STATUS));
                    int old_edited = edited;
                    if (edited != UNEDITED) {
                        if (edited == USER_DELETED) {
                            // USER_DELETED_BUT_PRESENT_IN_XML indicates entry has been deleted
                            // by user but present in apn xml file.
                            edited = USER_DELETED_BUT_PRESENT_IN_XML;
                        } else if (edited == CARRIER_DELETED) {
                            // CARRIER_DELETED_BUT_PRESENT_IN_XML indicates entry has been deleted
                            // by user but present in apn xml file.
                            edited = CARRIER_DELETED_BUT_PRESENT_IN_XML;
                        }
                        mergedValues.put(EDITED_STATUS, edited);
                    }

                    mergeFieldsAndUpdateDb(db, CARRIERS_TABLE, oldRow, row, mergedValues, false,
                            mContext);

                    if (VDBG) log("dbh.insertAddingDefaults: old edited = " + old_edited
                            + " new edited = " + edited);

                    oldRow.close();
                }
            }
        }
    }

    public static void mergeFieldsAndUpdateDb(SQLiteDatabase db, String table, Cursor oldRow,
            ContentValues newRow, ContentValues mergedValues,
            boolean onUpgrade, Context context) {
        if (newRow.containsKey(TYPE)) {
            // Merge the types
            String oldType = oldRow.getString(oldRow.getColumnIndex(TYPE));
            String newType = newRow.getAsString(TYPE);

            if (!oldType.equalsIgnoreCase(newType)) {
                if (oldType.equals("") || newType.equals("")) {
                    newRow.put(TYPE, "");
                } else {
                    String[] oldTypes = oldType.toLowerCase().split(",");
                    String[] newTypes = newType.toLowerCase().split(",");

                    if (VDBG) {
                        log("mergeFieldsAndUpdateDb: Calling separateRowsNeeded() oldType=" +
                                oldType + " old bearer=" + oldRow.getInt(oldRow.getColumnIndex(
                                BEARER_BITMASK)) +  " old networkType=" +
                                oldRow.getInt(oldRow.getColumnIndex(NETWORK_TYPE_BITMASK)) +
                                " old profile_id=" + oldRow.getInt(oldRow.getColumnIndex(
                                PROFILE_ID)) + " newRow " + newRow);
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
                    newRow.put(TYPE, mergedType.toString());
                }
            }
            mergedValues.put(TYPE, newRow.getAsString(TYPE));
        }

        if (newRow.containsKey(BEARER_BITMASK)) {
            int oldBearer = oldRow.getInt(oldRow.getColumnIndex(BEARER_BITMASK));
            int newBearer = newRow.getAsInteger(BEARER_BITMASK);
            if (oldBearer != newBearer) {
                if (oldBearer == 0 || newBearer == 0) {
                    newRow.put(BEARER_BITMASK, 0);
                } else {
                    newRow.put(BEARER_BITMASK, (oldBearer | newBearer));
                }
            }
            mergedValues.put(BEARER_BITMASK, newRow.getAsInteger(BEARER_BITMASK));
        }

        if (newRow.containsKey(NETWORK_TYPE_BITMASK)) {
            int oldBitmask = oldRow.getInt(oldRow.getColumnIndex(NETWORK_TYPE_BITMASK));
            int newBitmask = newRow.getAsInteger(NETWORK_TYPE_BITMASK);
            if (oldBitmask != newBitmask) {
                if (oldBitmask == 0 || newBitmask == 0) {
                    newRow.put(NETWORK_TYPE_BITMASK, 0);
                } else {
                    newRow.put(NETWORK_TYPE_BITMASK, (oldBitmask | newBitmask));
                }
            }
            mergedValues.put(NETWORK_TYPE_BITMASK, newRow.getAsInteger(NETWORK_TYPE_BITMASK));
        }

        if (newRow.containsKey(BEARER_BITMASK)
                && newRow.containsKey(NETWORK_TYPE_BITMASK)) {
            syncBearerBitmaskAndNetworkTypeBitmask(mergedValues);
        }

        if (!onUpgrade) {
            // Do not overwrite a carrier or user edit with EDITED=UNEDITED
            if (newRow.containsKey(EDITED_STATUS)) {
                int oldEdited = oldRow.getInt(oldRow.getColumnIndex(EDITED_STATUS));
                int newEdited = newRow.getAsInteger(EDITED_STATUS);
                if (newEdited == UNEDITED && (oldEdited == CARRIER_EDITED
                        || oldEdited == CARRIER_DELETED
                        || oldEdited == CARRIER_DELETED_BUT_PRESENT_IN_XML
                        || oldEdited == USER_EDITED
                        || oldEdited == USER_DELETED
                        || oldEdited == USER_DELETED_BUT_PRESENT_IN_XML)) {
                    newRow.remove(EDITED_STATUS);
                }
            }
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
        if (context.getResources() != null) {
            String[] persistApnsForPlmns = context.getResources().getStringArray(
                    R.array.persist_apns_for_plmn);
            for (String s : persistApnsForPlmns) {
                if (s.equalsIgnoreCase(newRow.getAsString(NUMERIC))) {
                    match = true;
                    break;
                }
            }
        } else {
            loge("separateRowsNeeded: resources=null");
        }

        if (!match) return false;

        // APN falls under persist_apns_for_plmn
        // Check if only difference between old type and new type is that
        // one has dun
        ArrayList<String> oldTypesAl = new ArrayList<String>(Arrays.asList(oldTypes));
        ArrayList<String> newTypesAl = new ArrayList<String>(Arrays.asList(newTypes));
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

        if (listWithDun.contains("dun") && !listWithoutDun.contains("dun")) {
            listWithoutDun.add("dun");
            if (!listWithDun.containsAll(listWithoutDun)) {
                return false;
            }

            // Only difference between old type and new type is that
            // one has dun
            // Check if profile_id is 0/not set
            if (oldRow.getInt(oldRow.getColumnIndex(PROFILE_ID)) == 0) {
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
                        log("separateRowsNeeded: updating type in oldRow to " + updatedType);
                    }
                    updateOldRow.put(TYPE, updatedType);
                    db.update(table, updateOldRow,
                            "_id=" + oldRow.getInt(oldRow.getColumnIndex("_id")), null);
                    return true;
                } else {
                    if (VDBG) log("separateRowsNeeded: adding profile id 1 to newRow");
                    // Update newRow to set profile_id to 1
                    newRow.put(PROFILE_ID, new Integer(1));
                }
            } else {
                return false;
            }

            // If match was found, both oldRow and newRow need to exist
            // separately in db. Add newRow to db.
            try {
                db.insertWithOnConflict(table, null, newRow, SQLiteDatabase.CONFLICT_REPLACE);
                if (VDBG) log("separateRowsNeeded: added newRow with profile id 1 to db");
                return true;
            } catch (SQLException e) {
                loge("Exception on trying to add new row after updating profile_id");
            }
        }

        return false;
    }

    public static Cursor selectConflictingRow(SQLiteDatabase db, String table,
            ContentValues row) {
        // Conflict is possible only when numeric, mcc, mnc (fields without any default value)
        // are set in the new row
        if (!row.containsKey(NUMERIC) || !row.containsKey(MCC) || !row.containsKey(MNC)) {
            loge("dbh.selectConflictingRow: called for non-conflicting row: " + row);
            return null;
        }

        String[] columns = { "_id",
                TYPE,
                EDITED_STATUS,
                BEARER_BITMASK,
                NETWORK_TYPE_BITMASK,
                PROFILE_ID };
        String selection = TextUtils.join("=? AND ", CARRIERS_UNIQUE_FIELDS) + "=?";
        int i = 0;
        String[] selectionArgs = new String[CARRIERS_UNIQUE_FIELDS.size()];
        for (String field : CARRIERS_UNIQUE_FIELDS) {
            if (!row.containsKey(field)) {
                selectionArgs[i++] = CARRIERS_UNIQUE_FIELDS_DEFAULTS.get(field);
            } else {
                if (CARRIERS_BOOLEAN_FIELDS.contains(field)) {
                    // for boolean fields we overwrite the strings "true" and "false" with "1"
                    // and "0"
                    selectionArgs[i++] = convertStringToIntString(row.getAsString(field));
                } else {
                    selectionArgs[i++] = row.getAsString(field);
                }
            }
        }

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

    /**
     * Convert "true" and "false" to "1" and "0".
     * If the passed in string is already "1" or "0" returns the passed in string.
     */
    private static String convertStringToIntString(String boolString) {
        if ("0".equals(boolString) || "false".equalsIgnoreCase(boolString)) return "0";
        return "1";
    }

    /**
     * Convert "1" and "0" to "true" and "false".
     * If the passed in string is already "true" or "false" returns the passed in string.
     */
    private static String convertStringToBoolString(String intString) {
        if ("0".equals(intString) || "false".equalsIgnoreCase(intString)) return "false";
        return "true";
    }

    /**
     * These methods can be overridden in a subclass for testing TelephonyProvider using an
     * in-memory database.
     */
    SQLiteDatabase getReadableDatabase() {
        return mOpenHelper.getReadableDatabase();
    }
    SQLiteDatabase getWritableDatabase() {
        return mOpenHelper.getWritableDatabase();
    }
    void initDatabaseWithDatabaseHelper(SQLiteDatabase db) {
        mOpenHelper.initDatabase(db);
    }
    boolean needApnDbUpdate() {
        return mOpenHelper.apnDbUpdateNeeded();
    }

    private static boolean apnSourceServiceExists(Context context) {
        if (s_apnSourceServiceExists != null) {
            return s_apnSourceServiceExists;
        }
        try {
            String service = context.getResources().getString(R.string.apn_source_service);
            if (TextUtils.isEmpty(service)) {
                s_apnSourceServiceExists = false;
            } else {
                s_apnSourceServiceExists = context.getPackageManager().getServiceInfo(
                        ComponentName.unflattenFromString(service), 0)
                        != null;
            }
        } catch (PackageManager.NameNotFoundException e) {
            s_apnSourceServiceExists = false;
        }
        return s_apnSourceServiceExists;
    }

    private void restoreApnsWithService(int subId) {
        Context context = getContext();
        Resources r = context.getResources();
        AtomicBoolean connectionBindingInvalid = new AtomicBoolean(false);
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName className,
                    IBinder service) {
                log("restoreApnsWithService: onServiceConnected");
                synchronized (mLock) {
                    mIApnSourceService = IApnSourceService.Stub.asInterface(service);
                    mLock.notifyAll();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName arg0) {
                loge("mIApnSourceService has disconnected unexpectedly");
                synchronized (mLock) {
                    mIApnSourceService = null;
                }
            }

            @Override
            public void onBindingDied(ComponentName name) {
                loge("The binding to the apn service connection is dead: " + name);
                synchronized (mLock) {
                    connectionBindingInvalid.set(true);
                    mLock.notifyAll();
                }
            }

            @Override
            public void onNullBinding(ComponentName name) {
                loge("Null binding: " + name);
                synchronized (mLock) {
                    connectionBindingInvalid.set(true);
                    mLock.notifyAll();
                }
            }
        };

        Intent intent = new Intent(IApnSourceService.class.getName());
        intent.setComponent(ComponentName.unflattenFromString(
                r.getString(R.string.apn_source_service)));
        log("binding to service to restore apns, intent=" + intent);
        try {
            if (context.bindService(intent, connection, Context.BIND_IMPORTANT |
                        Context.BIND_AUTO_CREATE)) {
                synchronized (mLock) {
                    while (mIApnSourceService == null && !connectionBindingInvalid.get()) {
                        try {
                            mLock.wait();
                        } catch (InterruptedException e) {
                            loge("Error while waiting for service connection: " + e);
                        }
                    }
                    if (connectionBindingInvalid.get()) {
                        loge("The binding is invalid.");
                        return;
                    }
                    try {
                        ContentValues[] values = mIApnSourceService.getApns(subId);
                        if (values != null) {
                            // we use the unsynchronized insert because this function is called
                            // within the syncrhonized function delete()
                            unsynchronizedBulkInsert(CONTENT_URI, values);
                            log("restoreApnsWithService: restored");
                        }
                    } catch (RemoteException e) {
                        loge("Error applying apns from service: " + e);
                    }
                }
            } else {
                loge("unable to bind to service from intent=" + intent);
            }
        } catch (SecurityException e) {
            loge("Error applying apns from service: " + e);
        } finally {
            if (connection != null) {
                context.unbindService(connection);
            }
            synchronized (mLock) {
                mIApnSourceService = null;
            }
        }
    }


    @Override
    public boolean onCreate() {
        mOpenHelper = new DatabaseHelper(getContext());

        try {
            PhoneFactory.addLocalLog(TAG, 100);
        } catch (IllegalArgumentException e) {
            // ignore
        }

        boolean isNewBuild = false;
        String newBuildId = SystemProperties.get("ro.build.id", null);
        if (!TextUtils.isEmpty(newBuildId)) {
            // Check if build id has changed
            SharedPreferences sp = getContext().getSharedPreferences(BUILD_ID_FILE,
                    Context.MODE_PRIVATE);
            String oldBuildId = sp.getString(RO_BUILD_ID, "");
            if (!newBuildId.equals(oldBuildId)) {
                localLog("onCreate: build id changed from " + oldBuildId + " to " + newBuildId);
                isNewBuild = true;
            } else {
                if (VDBG) log("onCreate: build id did not change: " + oldBuildId);
            }
            sp.edit().putString(RO_BUILD_ID, newBuildId).apply();
        } else {
            if (VDBG) log("onCreate: newBuildId is empty");
        }

        if (isNewBuild) {
            if (!apnSourceServiceExists(getContext())) {
                // Update APN DB
                updateApnDb();
            }

            // Add all APN related shared prefs to local log for dumpsys
            if (DBG) addAllApnSharedPrefToLocalLog();
        }

        SharedPreferences sp = getContext().getSharedPreferences(ENFORCED_FILE,
                Context.MODE_PRIVATE);
        mManagedApnEnforced = sp.getBoolean(ENFORCED_KEY, false);

        if (VDBG) log("onCreate:- ret true");

        return true;
    }

    private void addAllApnSharedPrefToLocalLog() {
        localLog("addAllApnSharedPrefToLocalLog");
        SharedPreferences spApn = getContext().getSharedPreferences(PREF_FILE_APN,
                Context.MODE_PRIVATE);

        Map<String, ?> allPrefApnId = spApn.getAll();
        for (String key : allPrefApnId.keySet()) {
            try {
                localLog(key + ":" + allPrefApnId.get(key).toString());
            } catch (Exception e) {
                localLog("Skipping over key " + key + " due to exception " + e);
            }
        }

        SharedPreferences spFullApn = getContext().getSharedPreferences(PREF_FILE_FULL_APN,
                Context.MODE_PRIVATE);

        Map<String, ?> allPrefFullApn = spFullApn.getAll();
        for (String key : allPrefFullApn.keySet()) {
            try {
                localLog(key + ":" + allPrefFullApn.get(key).toString());
            } catch (Exception e) {
                localLog("Skipping over key " + key + " due to exception " + e);
            }
        }
    }

    private static void localLog(String logMsg) {
        Log.d(TAG, logMsg);
        PhoneFactory.localLog(TAG, logMsg);
    }

    private synchronized boolean isManagedApnEnforced() {
        return mManagedApnEnforced;
    }

    private void setManagedApnEnforced(boolean enforced) {
        SharedPreferences sp = getContext().getSharedPreferences(ENFORCED_FILE,
                Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        editor.putBoolean(ENFORCED_KEY, enforced);
        editor.apply();
        synchronized (this) {
            mManagedApnEnforced = enforced;
        }
    }

    private void setPreferredApnId(Long id, int subId, boolean saveApn) {
        SharedPreferences sp = getContext().getSharedPreferences(PREF_FILE_APN,
                Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        editor.putLong(COLUMN_APN_ID + subId, id != null ? id : INVALID_APN_ID);
        localLog("setPreferredApnId: " + COLUMN_APN_ID + subId + ":"
                + (id != null ? id : INVALID_APN_ID));
        // This is for debug purposes. It indicates if this APN was set by DcTracker or user (true)
        // or if this was restored from APN saved in PREF_FILE_FULL_APN (false).
        editor.putBoolean(EXPLICIT_SET_CALLED + subId, saveApn);
        localLog("setPreferredApnId: " + EXPLICIT_SET_CALLED + subId + ":" + saveApn);
        editor.apply();
        if (id == null || id.longValue() == INVALID_APN_ID) {
            deletePreferredApn(subId);
        } else {
            // If id is not invalid, and saveApn is true, save the actual APN in PREF_FILE_FULL_APN
            // too.
            if (saveApn) {
                setPreferredApn(id, subId);
            }
        }
    }

    private long getPreferredApnId(int subId, boolean checkApnSp) {
        SharedPreferences sp = getContext().getSharedPreferences(PREF_FILE_APN,
                Context.MODE_PRIVATE);
        long apnId = sp.getLong(COLUMN_APN_ID + subId, INVALID_APN_ID);
        if (apnId == INVALID_APN_ID && checkApnSp) {
            apnId = getPreferredApnIdFromApn(subId);
            if (apnId != INVALID_APN_ID) {
                setPreferredApnId(apnId, subId, false);
            }
        }
        return apnId;
    }

    private int getPreferredApnSetId(int subId) {
        SharedPreferences sp = getContext().getSharedPreferences(PREF_FILE_FULL_APN,
                Context.MODE_PRIVATE);
        try {
            return Integer.parseInt(sp.getString(APN_SET_ID + subId, null));
        } catch (NumberFormatException e) {
            return NO_APN_SET_ID;
        }
    }

    private void deletePreferredApnId(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_FILE_APN,
                Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        editor.clear();
        editor.apply();
    }

    private void setPreferredApn(Long id, int subId) {
        localLog("setPreferredApn: _id " + id + " subId " + subId);
        SQLiteDatabase db = getWritableDatabase();
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
                    localLog("setPreferredApn: " + key + subId + ":"
                            + c.getString(c.getColumnIndex(key)));
                }
                // also store the version number
                editor.putString(DB_VERSION_KEY + subId, "" + DATABASE_VERSION);
                localLog("setPreferredApn: " + DB_VERSION_KEY + subId + ":" + DATABASE_VERSION);
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
        SQLiteDatabase db = getReadableDatabase();

        List<String> whereList = new ArrayList<>();
        List<String> whereArgsList = new ArrayList<>();
        SharedPreferences sp = getContext().getSharedPreferences(PREF_FILE_FULL_APN,
                Context.MODE_PRIVATE);
        for (String key : CARRIERS_UNIQUE_FIELDS) {
            String value = sp.getString(key + subId, null);
            if (value == null) {
                continue;
            } else {
                whereList.add(key);
                whereArgsList.add(value);
            }
        }
        if (whereList.size() == 0) return INVALID_APN_ID;

        String where = TextUtils.join("=? and ", whereList) + "=?";
        String[] whereArgs = new String[whereArgsList.size()];
        whereArgs = whereArgsList.toArray(whereArgs);

        long apnId = INVALID_APN_ID;
        Cursor c = db.query(CARRIERS_TABLE, new String[]{"_id"}, where, whereArgs, null, null,
                null);
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
            editor.apply();
        }
    }

    boolean isCallingFromSystemOrPhoneUid() {
        return mInjector.binderGetCallingUid() == Process.SYSTEM_UID ||
                mInjector.binderGetCallingUid() == Process.PHONE_UID;
    }

    void ensureCallingFromSystemOrPhoneUid(String message) {
        if (!isCallingFromSystemOrPhoneUid()) {
            throw new SecurityException(message);
        }
    }

    @Override
    public synchronized Cursor query(Uri url, String[] projectionIn, String selection,
            String[] selectionArgs, String sort) {
        if (VDBG) log("query: url=" + url + ", projectionIn=" + projectionIn + ", selection="
                + selection + "selectionArgs=" + selectionArgs + ", sort=" + sort);
        int subId = SubscriptionManager.getDefaultSubscriptionId();
        String subIdString;
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setStrict(true); // a little protection from injection attacks
        qb.setTables(CARRIERS_TABLE);

        List<String> constraints = new ArrayList<String>();

        int match = s_urlMatcher.match(url);
        checkPermissionCompat(match, projectionIn);
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
                TelephonyManager telephonyManager = getContext()
                    .getSystemService(TelephonyManager.class).createForSubscriptionId(subId);
                constraints.add(NUMERIC + " = '" + telephonyManager.getSimOperator() + "'");
                // TODO b/74213956 turn this back on once insertion includes correct sub id
                // constraints.add(SUBSCRIPTION_ID + "=" + subIdString);
            }
            // intentional fall through from above case
            case URL_TELEPHONY: {
                constraints.add(IS_NOT_OWNED_BY_DPC);
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
                // TODO b/74213956 turn this back on once insertion includes correct sub id
                // constraints.add(SUBSCRIPTION_ID + "=" + subIdString);
            }
            //intentional fall through from above case
            case URL_CURRENT: {
                constraints.add("current IS NOT NULL");
                constraints.add(IS_NOT_OWNED_BY_DPC);
                // do not ignore the selection since MMS may use it.
                //selection = null;
                break;
            }

            case URL_ID: {
                constraints.add("_id = " + url.getPathSegments().get(1));
                constraints.add(IS_NOT_OWNED_BY_DPC);
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
                // TODO b/74213956 turn this back on once insertion includes correct sub id
                // constraints.add(SUBSCRIPTION_ID + "=" + subIdString);
            }
            //intentional fall through from above case
            case URL_PREFERAPN:
            case URL_PREFERAPN_NO_UPDATE: {
                constraints.add("_id = " + getPreferredApnId(subId, true));
                break;
            }

            case URL_PREFERAPNSET_USING_SUBID: {
                subIdString = url.getLastPathSegment();
                try {
                    subId = Integer.parseInt(subIdString);
                } catch (NumberFormatException e) {
                    loge("NumberFormatException" + e);
                    return null;
                }
                if (DBG) log("subIdString = " + subIdString + " subId = " + subId);
                // TODO b/74213956 turn this back on once insertion includes correct sub id
                // constraints.add(SUBSCRIPTION_ID + "=" + subIdString);
            }
            // intentional fall through from above case
            case URL_PREFERAPNSET: {
                final int set = getPreferredApnSetId(subId);
                if (set == NO_APN_SET_ID) {
                    return null;
                }
                constraints.add(APN_SET_ID + "=" + set);
                qb.appendWhere(TextUtils.join(" AND ", constraints));
                return getSubscriptionMatchingAPNList(qb, projectionIn, selection, selectionArgs,
                        sort, subId);
            }

            case URL_DPC: {
                ensureCallingFromSystemOrPhoneUid("URL_DPC called from non SYSTEM_UID.");
                // DPC query only returns DPC records.
                constraints.add(IS_OWNED_BY_DPC);
                break;
            }

            case URL_FILTERED_ID:
            case URL_FILTERED_USING_SUBID: {
                String idString = url.getLastPathSegment();
                if (match == URL_FILTERED_ID) {
                    constraints.add("_id = " + idString);
                } else {
                    try {
                        subId = Integer.parseInt(idString);
                        // TODO b/74213956 turn this back on once insertion includes correct sub id
                        // constraints.add(SUBSCRIPTION_ID + "=" + subIdString);
                    } catch (NumberFormatException e) {
                        loge("NumberFormatException" + e);
                        return null;
                    }
                }
            }
            //intentional fall through from above case
            case URL_FILTERED: {
                if (isManagedApnEnforced()) {
                    // If enforced, return DPC records only.
                    constraints.add(IS_OWNED_BY_DPC);
                } else {
                    // Otherwise return non-DPC records only.
                    constraints.add(IS_NOT_OWNED_BY_DPC);
                }
                break;
            }

            case URL_ENFORCE_MANAGED: {
                ensureCallingFromSystemOrPhoneUid(
                        "URL_ENFORCE_MANAGED called from non SYSTEM_UID.");
                MatrixCursor cursor = new MatrixCursor(new String[]{ENFORCED_KEY});
                cursor.addRow(new Object[]{isManagedApnEnforced() ? 1 : 0});
                return cursor;
            }

            case URL_SIMINFO: {
                qb.setTables(SIMINFO_TABLE);
                break;
            }
            case URL_SIM_APN_LIST_ID: {
                subIdString = url.getLastPathSegment();
                try {
                    subId = Integer.parseInt(subIdString);
                } catch (NumberFormatException e) {
                    loge("NumberFormatException" + e);
                    return null;
                }
            }
            //intentional fall through from above case
            case URL_SIM_APN_LIST: {
                qb.appendWhere(IS_NOT_OWNED_BY_DPC);
                return getSubscriptionMatchingAPNList(qb, projectionIn, selection, selectionArgs,
                        sort, subId);
            }

            case URL_SIM_APN_LIST_FILTERED_ID: {
                subIdString = url.getLastPathSegment();
                try {
                    subId = Integer.parseInt(subIdString);
                } catch (NumberFormatException e) {
                    loge("NumberFormatException" + e);
                    return null;
                }
            }
            //intentional fall through from above case
            case URL_SIM_APN_LIST_FILTERED: {
                if (isManagedApnEnforced()) {
                    // If enforced, return DPC records only.
                    qb.appendWhereStandalone(IS_OWNED_BY_DPC);
                } else {
                    // Otherwise return non-DPC records only.
                    qb.appendWhereStandalone(IS_NOT_OWNED_BY_DPC);
                }
                return getSubscriptionMatchingAPNList(qb, projectionIn, selection, selectionArgs,
                    sort, subId);
            }

            default: {
                return null;
            }
        }

        // appendWhere doesn't add ANDs so we do it ourselves
        if (constraints.size() > 0) {
            qb.appendWhere(TextUtils.join(" AND ", constraints));
        }

        SQLiteDatabase db = getReadableDatabase();
        Cursor ret = null;
        try {
            // Exclude entries marked deleted
            if (CARRIERS_TABLE.equals(qb.getTables())) {
                if (TextUtils.isEmpty(selection)) {
                    selection = "";
                } else {
                    selection += " and ";
                }
                selection += IS_NOT_USER_DELETED + " and " +
                        IS_NOT_USER_DELETED_BUT_PRESENT_IN_XML + " and " +
                        IS_NOT_CARRIER_DELETED + " and " +
                        IS_NOT_CARRIER_DELETED_BUT_PRESENT_IN_XML;
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

    /**
     * To find the current sim APN. Query APN based on {MCC, MNC, MVNO} and {Carrier_ID}.
     *
     * There has three steps:
     * 1. Query the APN based on { MCC, MNC, MVNO } and if has results jump to step 3, else jump to
     *    step 2.
     * 2. Fallback to query the parent APN that query based on { MCC, MNC }.
     * 3. Append the result with the APN that query based on { Carrier_ID }
     */
    private Cursor getSubscriptionMatchingAPNList(SQLiteQueryBuilder qb, String[] projectionIn,
            String selection, String[] selectionArgs, String sort, int subId) {
        Cursor ret;
        Context context = getContext();
        SubscriptionManager subscriptionManager = (SubscriptionManager) context
                .getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        if (!subscriptionManager.isActiveSubscriptionId(subId)) {
            return null;
        }

        final TelephonyManager tm = ((TelephonyManager) context
                .getSystemService(Context.TELEPHONY_SERVICE))
                .createForSubscriptionId(subId);
        SQLiteDatabase db = getReadableDatabase();
        String mccmnc = tm.getSimOperator();
        int carrierId = tm.getSimCarrierId();

        qb.appendWhereStandalone(IS_NOT_USER_DELETED + " and " +
                IS_NOT_USER_DELETED_BUT_PRESENT_IN_XML + " and " +
                IS_NOT_CARRIER_DELETED + " and " +
                IS_NOT_CARRIER_DELETED_BUT_PRESENT_IN_XML);

        // For query db one time, append all conditions in one selection and separate results after
        // the query is completed. IMSI has special match rule, so just query the MCC / MNC and
        // filter the MVNO by ourselves
        qb.appendWhereStandalone(NUMERIC + " = '" + mccmnc + "' OR " +
                CARRIER_ID + " = '" + carrierId + "'");

        ret = qb.query(db, null, selection, selectionArgs, null, null, sort);
        if (ret == null) {
            loge("query current APN but cursor is null.");
            return null;
        }

        if (DBG) log("match current APN size:  " + ret.getCount());

        String[] columnNames = projectionIn != null ? projectionIn : ret.getColumnNames();
        MatrixCursor currentCursor = new MatrixCursor(columnNames);
        MatrixCursor parentCursor = new MatrixCursor(columnNames);
        MatrixCursor carrierIdCursor = new MatrixCursor(columnNames);

        int numericIndex = ret.getColumnIndex(NUMERIC);
        int mvnoIndex = ret.getColumnIndex(MVNO_TYPE);
        int mvnoDataIndex = ret.getColumnIndex(MVNO_MATCH_DATA);
        int carrierIdIndex = ret.getColumnIndex(CARRIER_ID);

        // Separate the result into MatrixCursor
        while (ret.moveToNext()) {
            List<String> data = new ArrayList<>();
            for (String column : columnNames) {
                data.add(ret.getString(ret.getColumnIndex(column)));
            }

            boolean isMVNOAPN = !TextUtils.isEmpty(ret.getString(numericIndex))
                    && tm.matchesCurrentSimOperator(ret.getString(numericIndex),
                            getMvnoTypeIntFromString(ret.getString(mvnoIndex)),
                            ret.getString(mvnoDataIndex));
            boolean isMNOAPN = !TextUtils.isEmpty(ret.getString(numericIndex))
                    && ret.getString(numericIndex).equals(mccmnc)
                    && TextUtils.isEmpty(ret.getString(mvnoIndex));
            boolean isCarrierIdAPN = !TextUtils.isEmpty(ret.getString(carrierIdIndex))
                    && ret.getString(carrierIdIndex).equals(String.valueOf(carrierId))
                    && carrierId != TelephonyManager.UNKNOWN_CARRIER_ID;

            if (isMVNOAPN) {
                // 1. The APN that query based on legacy SIM MCC/MCC and MVNO
                currentCursor.addRow(data);
            } else if (isMNOAPN) {
                // 2. The APN that query based on SIM MCC/MNC
                parentCursor.addRow(data);
            } else if (isCarrierIdAPN) {
                // The APN that query based on carrier Id (not include the MVNO or MNO APN)
                carrierIdCursor.addRow(data);
            }
        }
        ret.close();

        MatrixCursor result;
        if (currentCursor.getCount() > 0) {
            if (DBG) log("match MVNO APN: " + currentCursor.getCount());
            result = currentCursor;
        } else if (parentCursor.getCount() > 0) {
            if (DBG) log("match MNO APN: " + parentCursor.getCount());
            result = parentCursor;
        } else {
            if (DBG) log("can't find the MVNO and MNO APN");
            result = new MatrixCursor(columnNames);
        }

        if (DBG) log("match carrier id APN: " + carrierIdCursor.getCount());
        appendCursorData(result, carrierIdCursor);
        return result;
    }

    private static void appendCursorData(@NonNull MatrixCursor from, @NonNull MatrixCursor to) {
        while (to.moveToNext()) {
            List<Object> data = new ArrayList<>();
            for (String column : to.getColumnNames()) {
                int index = to.getColumnIndex(column);
                switch (to.getType(index)) {
                    case Cursor.FIELD_TYPE_INTEGER:
                        data.add(to.getInt(index));
                        break;
                    case Cursor.FIELD_TYPE_FLOAT:
                        data.add(to.getFloat(index));
                        break;
                    case Cursor.FIELD_TYPE_BLOB:
                        data.add(to.getBlob(index));
                        break;
                    case Cursor.FIELD_TYPE_STRING:
                    case Cursor.FIELD_TYPE_NULL:
                        data.add(to.getString(index));
                        break;
                }
            }
            from.addRow(data);
        }
    }

    @Override
    public String getType(Uri url)
    {
        switch (s_urlMatcher.match(url)) {
        case URL_TELEPHONY:
        case URL_TELEPHONY_USING_SUBID:
            return "vnd.android.cursor.dir/telephony-carrier";

        case URL_ID:
        case URL_FILTERED_ID:
        case URL_FILTERED_USING_SUBID:
            return "vnd.android.cursor.item/telephony-carrier";

        case URL_PREFERAPN_USING_SUBID:
        case URL_PREFERAPN_NO_UPDATE_USING_SUBID:
        case URL_PREFERAPN:
        case URL_PREFERAPN_NO_UPDATE:
        case URL_PREFERAPNSET:
        case URL_PREFERAPNSET_USING_SUBID:
            return "vnd.android.cursor.item/telephony-carrier";

        default:
            throw new IllegalArgumentException("Unknown URL " + url);
        }
    }

    /**
     * Insert an array of ContentValues and call notifyChange at the end.
     */
    @Override
    public synchronized int bulkInsert(Uri url, ContentValues[] values) {
        return unsynchronizedBulkInsert(url, values);
    }

    /**
     * Do a bulk insert while inside a synchronized function. This is typically not safe and should
     * only be done when you are sure there will be no conflict.
     */
    private int unsynchronizedBulkInsert(Uri url, ContentValues[] values) {
        int count = 0;
        boolean notify = false;
        for (ContentValues value : values) {
            Pair<Uri, Boolean> rowAndNotify = insertSingleRow(url, value);
            if (rowAndNotify.first != null) {
                count++;
            }
            if (rowAndNotify.second == true) {
                notify = true;
            }
        }
        if (notify) {
            getContext().getContentResolver().notifyChange(CONTENT_URI, null,
                    true, UserHandle.USER_ALL);
        }
        return count;
    }

    @Override
    public synchronized Uri insert(Uri url, ContentValues initialValues) {
        Pair<Uri, Boolean> rowAndNotify = insertSingleRow(url, initialValues);
        if (rowAndNotify.second) {
            getContext().getContentResolver().notifyChange(CONTENT_URI, null,
                    true, UserHandle.USER_ALL);
        }
        return rowAndNotify.first;
    }

    /**
     * Internal insert function to prevent code duplication for URL_TELEPHONY and URL_DPC.
     *
     * @param values the value that caller wants to insert
     * @return a pair in which the first element refers to the Uri for the row inserted, the second
     *         element refers to whether sends out nofitication.
     */
    private Pair<Uri, Boolean> insertRowWithValue(ContentValues values) {
        Uri result = null;
        boolean notify = false;
        SQLiteDatabase db = getWritableDatabase();

        try {
            // Abort on conflict of unique fields and attempt merge
            long rowID = db.insertWithOnConflict(CARRIERS_TABLE, null, values,
                    SQLiteDatabase.CONFLICT_ABORT);
            if (rowID >= 0) {
                result = ContentUris.withAppendedId(CONTENT_URI, rowID);
                notify = true;
            }
            if (VDBG) log("insert: inserted " + values.toString() + " rowID = " + rowID);
        } catch (SQLException e) {
            log("insert: exception " + e);
            // Insertion failed which could be due to a conflict. Check if that is the case
            // and merge the entries
            Cursor oldRow = selectConflictingRow(db, CARRIERS_TABLE, values);
            if (oldRow != null) {
                ContentValues mergedValues = new ContentValues();
                mergeFieldsAndUpdateDb(db, CARRIERS_TABLE, oldRow, values,
                        mergedValues, false, getContext());
                oldRow.close();
                notify = true;
            }
        }
        return Pair.create(result, notify);
    }

    private Pair<Uri, Boolean> insertSingleRow(Uri url, ContentValues initialValues) {
        Uri result = null;
        int subId = SubscriptionManager.getDefaultSubscriptionId();

        checkPermission();
        syncBearerBitmaskAndNetworkTypeBitmask(initialValues);

        boolean notify = false;
        SQLiteDatabase db = getWritableDatabase();
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
                    return Pair.create(result, notify);
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

                values = setDefaultValue(values);
                if (!values.containsKey(EDITED_STATUS)) {
                    values.put(EDITED_STATUS, CARRIER_EDITED);
                }
                // Owned_by should be others if inserted via general uri.
                values.put(OWNED_BY, OWNED_BY_OTHERS);

                Pair<Uri, Boolean> ret = insertRowWithValue(values);
                result = ret.first;
                notify = ret.second;
                break;
            }

            case URL_CURRENT_USING_SUBID:
            {
                String subIdString = url.getLastPathSegment();
                try {
                    subId = Integer.parseInt(subIdString);
                } catch (NumberFormatException e) {
                    loge("NumberFormatException" + e);
                    return Pair.create(result, notify);
                }
                if (DBG) log("subIdString = " + subIdString + " subId = " + subId);
                // FIXME use subId in the query
            }
            //intentional fall through from above case

            case URL_CURRENT:
            {
                // zero out the previous operator
                db.update(CARRIERS_TABLE, s_currentNullMap, CURRENT + "!=0", null);

                String numeric = initialValues.getAsString(NUMERIC);
                int updated = db.update(CARRIERS_TABLE, s_currentSetMap,
                        NUMERIC + " = '" + numeric + "'", null);

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
                    return Pair.create(result, notify);
                }
                if (DBG) log("subIdString = " + subIdString + " subId = " + subId);
            }
            //intentional fall through from above case

            case URL_PREFERAPN:
            case URL_PREFERAPN_NO_UPDATE:
            {
                if (initialValues != null) {
                    if(initialValues.containsKey(COLUMN_APN_ID)) {
                        setPreferredApnId(initialValues.getAsLong(COLUMN_APN_ID), subId, true);
                    }
                }
                break;
            }

            case URL_DPC: {
                ensureCallingFromSystemOrPhoneUid("URL_DPC called from non SYSTEM_UID.");

                ContentValues values;
                if (initialValues != null) {
                    values = new ContentValues(initialValues);
                } else {
                    values = new ContentValues();
                }

                // Owned_by should be DPC if inserted via URL_DPC.
                values.put(OWNED_BY, OWNED_BY_DPC);
                // DPC records should not be user editable.
                values.put(USER_EDITABLE, false);

                final long rowID = db.insertWithOnConflict(CARRIERS_TABLE, null, values,
                        SQLiteDatabase.CONFLICT_IGNORE);
                if (rowID >= 0) {
                    result = ContentUris.withAppendedId(CONTENT_URI, rowID);
                    notify = true;
                }
                if (VDBG) log("insert: inserted " + values.toString() + " rowID = " + rowID);

                break;
            }

            case URL_SIMINFO: {
               long id = db.insert(SIMINFO_TABLE, null, initialValues);
               result = ContentUris.withAppendedId(Telephony.SimInfo.CONTENT_URI, id);
               break;
            }
        }

        return Pair.create(result, notify);
    }

    @Override
    public synchronized int delete(Uri url, String where, String[] whereArgs) {
        int count = 0;
        int subId = SubscriptionManager.getDefaultSubscriptionId();
        String userOrCarrierEdited = ") and (" +
                IS_USER_EDITED +  " or " +
                IS_CARRIER_EDITED + ")";
        String notUserOrCarrierEdited = ") and (" +
                IS_NOT_USER_EDITED +  " and " +
                IS_NOT_CARRIER_EDITED + ")";
        String unedited = ") and " + IS_UNEDITED;
        ContentValues cv = new ContentValues();
        cv.put(EDITED_STATUS, USER_DELETED);

        checkPermission();

        SQLiteDatabase db = getWritableDatabase();
        int match = s_urlMatcher.match(url);
        switch (match)
        {
            case URL_DELETE:
            {
                // Delete preferred APN for all subIds
                deletePreferredApnId(getContext());
                // Delete unedited entries
                count = db.delete(CARRIERS_TABLE, "(" + where + unedited + " and " +
                        IS_NOT_OWNED_BY_DPC, whereArgs);
                break;
            }

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
                count = db.delete(CARRIERS_TABLE, "(" + where + userOrCarrierEdited
                        + " and " + IS_NOT_OWNED_BY_DPC, whereArgs);
                // Otherwise mark as user deleted instead of deleting
                count += db.update(CARRIERS_TABLE, cv, "(" + where +
                        notUserOrCarrierEdited + " and " + IS_NOT_OWNED_BY_DPC, whereArgs);
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
                count = db.delete(CARRIERS_TABLE, "(" + where + userOrCarrierEdited
                        + " and " + IS_NOT_OWNED_BY_DPC, whereArgs);
                // Otherwise mark as user deleted instead of deleting
                count += db.update(CARRIERS_TABLE, cv, "(" + where +
                        notUserOrCarrierEdited + " and " + IS_NOT_OWNED_BY_DPC, whereArgs);
                break;
            }

            case URL_ID:
            {
                // Delete user/carrier edited entries
                count = db.delete(CARRIERS_TABLE,
                        "(" + _ID + "=?" + userOrCarrierEdited +
                                " and " + IS_NOT_OWNED_BY_DPC,
                        new String[] { url.getLastPathSegment() });
                // Otherwise mark as user deleted instead of deleting
                count += db.update(CARRIERS_TABLE, cv,
                        "(" + _ID + "=?" + notUserOrCarrierEdited +
                                " and " + IS_NOT_OWNED_BY_DPC,
                        new String[]{url.getLastPathSegment() });
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
            }
            // intentional fall through from above case

            case URL_RESTOREAPN: {
                count = 1;
                restoreDefaultAPN(subId);
                getContext().getContentResolver().notifyChange(
                        Uri.withAppendedPath(CONTENT_URI, "restore/subId/" + subId), null,
                        true, UserHandle.USER_ALL);
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
                setPreferredApnId((long)INVALID_APN_ID, subId, true);
                if ((match == URL_PREFERAPN) || (match == URL_PREFERAPN_USING_SUBID)) count = 1;
                break;
            }

            case URL_DPC_ID: {
                ensureCallingFromSystemOrPhoneUid("URL_DPC_ID called from non SYSTEM_UID.");

                // Only delete if owned by DPC.
                count = db.delete(CARRIERS_TABLE, "(" + _ID + "=?)" + " and " + IS_OWNED_BY_DPC,
                        new String[] { url.getLastPathSegment() });
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
            getContext().getContentResolver().notifyChange(CONTENT_URI, null,
                    true, UserHandle.USER_ALL);
        }

        return count;
    }

    @Override
    public synchronized int update(Uri url, ContentValues values, String where, String[] whereArgs)
    {
        int count = 0;
        int uriType = URL_UNKNOWN;
        int subId = SubscriptionManager.getDefaultSubscriptionId();

        checkPermission();
        syncBearerBitmaskAndNetworkTypeBitmask(values);

        SQLiteDatabase db = getWritableDatabase();
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
                if (!values.containsKey(EDITED_STATUS)) {
                    values.put(EDITED_STATUS, CARRIER_EDITED);
                }

                // Replace on conflict so that if same APN is present in db with edited
                // as UNEDITED or USER/CARRIER_DELETED, it is replaced with
                // edited USER/CARRIER_EDITED
                count = db.updateWithOnConflict(CARRIERS_TABLE, values, where +
                                " and " + IS_NOT_OWNED_BY_DPC, whereArgs,
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
                if (!values.containsKey(EDITED_STATUS)) {
                    values.put(EDITED_STATUS, CARRIER_EDITED);
                }
                // Replace on conflict so that if same APN is present in db with edited
                // as UNEDITED or USER/CARRIER_DELETED, it is replaced with
                // edited USER/CARRIER_EDITED
                count = db.updateWithOnConflict(CARRIERS_TABLE, values, where +
                                " and " + IS_NOT_OWNED_BY_DPC,
                        whereArgs, SQLiteDatabase.CONFLICT_REPLACE);
                break;
            }

            case URL_ID:
            {
                String rowID = url.getLastPathSegment();
                if (where != null || whereArgs != null) {
                    throw new UnsupportedOperationException(
                            "Cannot update URL " + url + " with a where clause");
                }
                if (!values.containsKey(EDITED_STATUS)) {
                    values.put(EDITED_STATUS, CARRIER_EDITED);
                }

                try {
                    count = db.updateWithOnConflict(CARRIERS_TABLE, values, _ID + "=?" + " and " +
                            IS_NOT_OWNED_BY_DPC, new String[] { rowID },
                            SQLiteDatabase.CONFLICT_ABORT);
                } catch (SQLException e) {
                    // Update failed which could be due to a conflict. Check if that is
                    // the case and merge the entries
                    log("update: exception " + e);
                    Cursor oldRow = selectConflictingRow(db, CARRIERS_TABLE, values);
                    if (oldRow != null) {
                        ContentValues mergedValues = new ContentValues();
                        mergeFieldsAndUpdateDb(db, CARRIERS_TABLE, oldRow, values,
                                mergedValues, false, getContext());
                        oldRow.close();
                        db.delete(CARRIERS_TABLE, _ID + "=?" + " and " + IS_NOT_OWNED_BY_DPC,
                                new String[] { rowID });
                    }
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
                    throw new IllegalArgumentException("Invalid subId " + url);
                }
                if (DBG) log("subIdString = " + subIdString + " subId = " + subId);
            }

            case URL_PREFERAPN:
            case URL_PREFERAPN_NO_UPDATE:
            {
                if (values != null) {
                    if (values.containsKey(COLUMN_APN_ID)) {
                        setPreferredApnId(values.getAsLong(COLUMN_APN_ID), subId, true);
                        if ((match == URL_PREFERAPN) ||
                                (match == URL_PREFERAPN_USING_SUBID)) {
                            count = 1;
                        }
                    }
                }
                break;
            }

            case URL_DPC_ID:
            {
                ensureCallingFromSystemOrPhoneUid("URL_DPC_ID called from non SYSTEM_UID.");

                if (where != null || whereArgs != null) {
                    throw new UnsupportedOperationException(
                            "Cannot update URL " + url + " with a where clause");
                }
                count = db.updateWithOnConflict(CARRIERS_TABLE, values,
                        _ID + "=?" + " and " + IS_OWNED_BY_DPC,
                        new String[] { url.getLastPathSegment() }, SQLiteDatabase.CONFLICT_IGNORE);
                break;
            }

            case URL_ENFORCE_MANAGED: {
                ensureCallingFromSystemOrPhoneUid(
                        "URL_ENFORCE_MANAGED called from non SYSTEM_UID.");
                if (values != null) {
                    if (values.containsKey(ENFORCED_KEY)) {
                        setManagedApnEnforced(values.getAsBoolean(ENFORCED_KEY));
                        count = 1;
                    }
                }
                break;
            }

            case URL_SIMINFO_USING_SUBID:
                String subIdString = url.getLastPathSegment();
                try {
                    subId = Integer.parseInt(subIdString);
                } catch (NumberFormatException e) {
                    loge("NumberFormatException" + e);
                    throw new IllegalArgumentException("Invalid subId " + url);
                }
                if (DBG) log("subIdString = " + subIdString + " subId = " + subId);
                if (where != null || whereArgs != null) {
                    throw new UnsupportedOperationException(
                            "Cannot update URL " + url + " with a where clause");
                }
                count = db.update(SIMINFO_TABLE, values, _ID + "=?",
                        new String[] { subIdString});
                uriType = URL_SIMINFO_USING_SUBID;
                break;

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
            boolean usingSubId = false;
            switch (uriType) {
                case URL_SIMINFO_USING_SUBID:
                    usingSubId = true;
                    // intentional fall through from above case
                case URL_SIMINFO:
                    // skip notifying descendant URLs to avoid unneccessary wake up.
                    // If not set, any change to SIMINFO will notify observers which listens to
                    // specific field of SIMINFO.
                    getContext().getContentResolver().notifyChange(
                        Telephony.SimInfo.CONTENT_URI, null,
                            ContentResolver.NOTIFY_SYNC_TO_NETWORK
                                    | ContentResolver.NOTIFY_SKIP_NOTIFY_FOR_DESCENDANTS,
                            UserHandle.USER_ALL);
                    // notify observers on specific user settings changes.
                    if (values.containsKey(Telephony.SimInfo.COLUMN_WFC_IMS_ENABLED)) {
                        getContext().getContentResolver().notifyChange(
                                getNotifyContentUri(SubscriptionManager.WFC_ENABLED_CONTENT_URI,
                                        usingSubId, subId), null, true, UserHandle.USER_ALL);
                    }
                    if (values.containsKey(Telephony.SimInfo.COLUMN_ENHANCED_4G_MODE_ENABLED)) {
                        getContext().getContentResolver().notifyChange(
                                getNotifyContentUri(SubscriptionManager
                                                .ADVANCED_CALLING_ENABLED_CONTENT_URI,
                                        usingSubId, subId), null, true, UserHandle.USER_ALL);
                    }
                    if (values.containsKey(Telephony.SimInfo.COLUMN_VT_IMS_ENABLED)) {
                        getContext().getContentResolver().notifyChange(
                                getNotifyContentUri(SubscriptionManager.VT_ENABLED_CONTENT_URI,
                                        usingSubId, subId), null, true, UserHandle.USER_ALL);
                    }
                    if (values.containsKey(Telephony.SimInfo.COLUMN_WFC_IMS_MODE)) {
                        getContext().getContentResolver().notifyChange(
                                getNotifyContentUri(SubscriptionManager.WFC_MODE_CONTENT_URI,
                                        usingSubId, subId), null, true, UserHandle.USER_ALL);
                    }
                    if (values.containsKey(Telephony.SimInfo.COLUMN_WFC_IMS_ROAMING_MODE)) {
                        getContext().getContentResolver().notifyChange(getNotifyContentUri(
                                SubscriptionManager.WFC_ROAMING_MODE_CONTENT_URI,
                                usingSubId, subId), null, true, UserHandle.USER_ALL);
                    }
                    if (values.containsKey(Telephony.SimInfo.COLUMN_WFC_IMS_ROAMING_ENABLED)) {
                        getContext().getContentResolver().notifyChange(getNotifyContentUri(
                                SubscriptionManager.WFC_ROAMING_ENABLED_CONTENT_URI,
                                usingSubId, subId), null, true, UserHandle.USER_ALL);
                    }
                    if (values.containsKey(Telephony.SimInfo.COLUMN_IMS_RCS_UCE_ENABLED)) {
                        getContext().getContentResolver().notifyChange(getNotifyContentUri(
                                Uri.withAppendedPath(Telephony.SimInfo.CONTENT_URI,
                                        Telephony.SimInfo.COLUMN_IMS_RCS_UCE_ENABLED), usingSubId, subId),
                                null, true, UserHandle.USER_ALL);
                    }
                    break;
                default:
                    getContext().getContentResolver().notifyChange(
                            CONTENT_URI, null, true, UserHandle.USER_ALL);
            }
        }

        return count;
    }

    private static Uri getNotifyContentUri(Uri uri, boolean usingSubId, int subId) {
        return (usingSubId) ? Uri.withAppendedPath(uri, "" + subId) : uri;
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
            if (telephonyManager.checkCarrierPrivilegesForPackageAnyPhone(pkg) ==
                    TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS) {
                return;
            }
        }


        throw new SecurityException("No permission to access APN settings");
    }

    /**
     * Check permission to query the database based on PlatformCompat settings -- if the compat
     * change is enabled, check WRITE_APN_SETTINGS or carrier privs for all queries. Otherwise,
     * use the legacy checkQueryPermission method to see if the query should be allowed.
     */
    private void checkPermissionCompat(int match, String[] projectionIn) {
        boolean useNewBehavior = CompatChanges.isChangeEnabled(
                Telephony.Carriers.APN_READING_PERMISSION_CHANGE_ID,
                Binder.getCallingUid());

        if (!useNewBehavior) {
            log("Using old permission behavior for telephony provider compat");
            checkQueryPermission(match, projectionIn);
        } else {
            checkPermission();
        }
    }

    private void checkQueryPermission(int match, String[] projectionIn) {
        if (match != URL_SIMINFO) {
            if (projectionIn != null) {
                for (String column : projectionIn) {
                    if (TYPE.equals(column) ||
                            MMSC.equals(column) ||
                            MMSPROXY.equals(column) ||
                            MMSPORT.equals(column) ||
                            MVNO_TYPE.equals(column) ||
                            MVNO_MATCH_DATA.equals(column) ||
                            APN.equals(column)) {
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
    }

    private DatabaseHelper mOpenHelper;

    private void restoreDefaultAPN(int subId) {
        SQLiteDatabase db = getWritableDatabase();
        TelephonyManager telephonyManager =
                (TelephonyManager) getContext().getSystemService(Context.TELEPHONY_SERVICE);
        String where = null;
        if (telephonyManager.getPhoneCount() > 1) {
            where = getWhereClauseForRestoreDefaultApn(db, subId);
        }
        if (TextUtils.isEmpty(where)) {
            where = IS_NOT_OWNED_BY_DPC;
        }
        log("restoreDefaultAPN: where: " + where);

        try {
            db.delete(CARRIERS_TABLE, where, null);
        } catch (SQLException e) {
            loge("got exception when deleting to restore: " + e);
        }

        // delete preferred apn ids and preferred apns (both stored in diff SharedPref) for all
        // subIds
        SharedPreferences spApnId = getContext().getSharedPreferences(PREF_FILE_APN,
                Context.MODE_PRIVATE);
        SharedPreferences.Editor editorApnId = spApnId.edit();
        editorApnId.clear();
        editorApnId.apply();

        SharedPreferences spApn = getContext().getSharedPreferences(PREF_FILE_FULL_APN,
                Context.MODE_PRIVATE);
        SharedPreferences.Editor editorApn = spApn.edit();
        editorApn.clear();
        editorApn.apply();

        if (apnSourceServiceExists(getContext())) {
            restoreApnsWithService(subId);
        } else {
            initDatabaseWithDatabaseHelper(db);
        }
    }

    private String getWhereClauseForRestoreDefaultApn(SQLiteDatabase db, int subId) {
        TelephonyManager telephonyManager =
            getContext().getSystemService(TelephonyManager.class).createForSubscriptionId(subId);
        String simOperator = telephonyManager.getSimOperator();
        Cursor cursor = db.query(CARRIERS_TABLE, new String[] {MVNO_TYPE, MVNO_MATCH_DATA},
                NUMERIC + "='" + simOperator + "'", null, null, null, DEFAULT_SORT_ORDER);
        String where = null;

        if (cursor != null) {
            cursor.moveToFirst();
            while (!cursor.isAfterLast()) {
                String mvnoType = cursor.getString(0 /* MVNO_TYPE index */);
                String mvnoMatchData = cursor.getString(1 /* MVNO_MATCH_DATA index */);
                if (!TextUtils.isEmpty(mvnoType) && !TextUtils.isEmpty(mvnoMatchData)
                        && telephonyManager.matchesCurrentSimOperator(simOperator,
                            getMvnoTypeIntFromString(mvnoType), mvnoMatchData)) {
                    where = NUMERIC + "='" + simOperator + "'"
                            + " AND " + MVNO_TYPE + "='" + mvnoType + "'"
                            + " AND " + MVNO_MATCH_DATA + "='" + mvnoMatchData + "'"
                            + " AND " + IS_NOT_OWNED_BY_DPC;
                    break;
                }
                cursor.moveToNext();
            }
            cursor.close();

            if (TextUtils.isEmpty(where)) {
                where = NUMERIC + "='" + simOperator + "'"
                        + " AND (" + MVNO_TYPE + "='' OR " + MVNO_MATCH_DATA + "='')"
                        + " AND " + IS_NOT_OWNED_BY_DPC;
            }
        }
        return where;
    }

    private synchronized void updateApnDb() {
        if (apnSourceServiceExists(getContext())) {
            loge("called updateApnDb when apn source service exists");
            return;
        }

        if (!needApnDbUpdate()) {
            log("Skipping apn db update since apn-conf has not changed.");
            return;
        }

        SQLiteDatabase db = getWritableDatabase();

        // Delete preferred APN for all subIds
        deletePreferredApnId(getContext());

        // Delete entries in db
        try {
            if (VDBG) log("updateApnDb: deleting edited=UNEDITED entries");
            db.delete(CARRIERS_TABLE, IS_UNEDITED + " and " + IS_NOT_OWNED_BY_DPC, null);
        } catch (SQLException e) {
            loge("got exception when deleting to update: " + e);
        }

        initDatabaseWithDatabaseHelper(db);

        // Notify listeners of DB change since DB has been updated
        getContext().getContentResolver().notifyChange(
                CONTENT_URI, null, true, UserHandle.USER_ALL);

    }

    public static void fillInMccMncStringAtCursor(Context context, SQLiteDatabase db, Cursor c) {
        int mcc, mnc;
        String subId;
        try {
            mcc = c.getInt(c.getColumnIndexOrThrow(Telephony.SimInfo.COLUMN_MCC));
            mnc = c.getInt(c.getColumnIndexOrThrow(Telephony.SimInfo.COLUMN_MNC));
            subId = c.getString(c.getColumnIndexOrThrow(
                    Telephony.SimInfo.COLUMN_UNIQUE_KEY_SUBSCRIPTION_ID));
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Possible database corruption -- some columns not found.");
            return;
        }

        String mccString = String.format(Locale.getDefault(), "%03d", mcc);
        String mncString = getBestStringMnc(context, mccString, mnc);
        ContentValues cv = new ContentValues(2);
        cv.put(Telephony.SimInfo.COLUMN_MCC_STRING, mccString);
        cv.put(Telephony.SimInfo.COLUMN_MNC_STRING, mncString);
        db.update(SIMINFO_TABLE, cv,
                Telephony.SimInfo.COLUMN_UNIQUE_KEY_SUBSCRIPTION_ID + "=?",
                new String[]{subId});
    }

    /*
     * Find the best string-form mnc by looking up possibilities in the carrier id db.
     * Default to the three-digit version if neither/both are valid.
     */
    private static String getBestStringMnc(Context context, String mcc, int mnc) {
        if (mnc >= 100 && mnc <= 999) {
            return String.valueOf(mnc);
        }
        String twoDigitMnc = String.format(Locale.getDefault(), "%02d", mnc);
        String threeDigitMnc = "0" + twoDigitMnc;

        try (
                Cursor twoDigitMncCursor = context.getContentResolver().query(
                        Telephony.CarrierId.All.CONTENT_URI,
                        /* projection */ null,
                        /* selection */ Telephony.CarrierId.All.MCCMNC + "=?",
                        /* selectionArgs */ new String[]{mcc + twoDigitMnc}, null)
        ) {
            if (twoDigitMncCursor.getCount() > 0) {
                return twoDigitMnc;
            }
            return threeDigitMnc;
        }
    }

    /**
     * Sync the bearer bitmask and network type bitmask when inserting and updating.
     * Since bearerBitmask is deprecating, map the networkTypeBitmask to bearerBitmask if
     * networkTypeBitmask was provided. But if networkTypeBitmask was not provided, map the
     * bearerBitmask to networkTypeBitmask.
     */
    private static void syncBearerBitmaskAndNetworkTypeBitmask(ContentValues values) {
        if (values.containsKey(NETWORK_TYPE_BITMASK)) {
            int convertedBitmask = convertNetworkTypeBitmaskToBearerBitmask(
                    values.getAsInteger(NETWORK_TYPE_BITMASK));
            if (values.containsKey(BEARER_BITMASK)
                    && convertedBitmask != values.getAsInteger(BEARER_BITMASK)) {
                loge("Network type bitmask and bearer bitmask are not compatible.");
            }
            values.put(BEARER_BITMASK, convertNetworkTypeBitmaskToBearerBitmask(
                    values.getAsInteger(NETWORK_TYPE_BITMASK)));
        } else {
            if (values.containsKey(BEARER_BITMASK)) {
                int convertedBitmask = convertBearerBitmaskToNetworkTypeBitmask(
                        values.getAsInteger(BEARER_BITMASK));
                values.put(NETWORK_TYPE_BITMASK, convertedBitmask);
            }
        }
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

    private static int getMvnoTypeIntFromString(String mvnoType) {
        String mvnoTypeString = TextUtils.isEmpty(mvnoType) ? mvnoType : mvnoType.toLowerCase();
        Integer mvnoTypeInt = MVNO_TYPE_STRING_MAP.get(mvnoTypeString);
        return  mvnoTypeInt == null ? UNSPECIFIED_INT : mvnoTypeInt;
    }

    private static int getBitmaskFromString(String bearerList) {
        String[] bearers = bearerList.split("\\|");
        int bearerBitmask = 0;
        for (String bearer : bearers) {
            int bearerInt = 0;
            try {
                bearerInt = Integer.parseInt(bearer.trim());
            } catch (NumberFormatException nfe) {
                return 0;
            }

            if (bearerInt == 0) {
                return 0;
            }
            bearerBitmask |= getBitmaskForTech(bearerInt);
        }
        return bearerBitmask;
    }

    /**
     * Transform RIL radio technology value to Network
     * type bitmask{@link android.telephony.TelephonyManager.NetworkTypeBitMask}.
     *
     * @param rat The RIL radio technology.
     * @return The network type
     * bitmask{@link android.telephony.TelephonyManager.NetworkTypeBitMask}.
     */
    private static int rilRadioTechnologyToNetworkTypeBitmask(int rat) {
        switch (rat) {
            case RIL_RADIO_TECHNOLOGY_GPRS:
                return (int) TelephonyManager.NETWORK_TYPE_BITMASK_GPRS;
            case RIL_RADIO_TECHNOLOGY_EDGE:
                return (int) TelephonyManager.NETWORK_TYPE_BITMASK_EDGE;
            case RIL_RADIO_TECHNOLOGY_UMTS:
                return (int) TelephonyManager.NETWORK_TYPE_BITMASK_UMTS;
            case RIL_RADIO_TECHNOLOGY_HSDPA:
                return (int) TelephonyManager.NETWORK_TYPE_BITMASK_HSDPA;
            case RIL_RADIO_TECHNOLOGY_HSUPA:
                return (int) TelephonyManager.NETWORK_TYPE_BITMASK_HSUPA;
            case RIL_RADIO_TECHNOLOGY_HSPA:
                return (int) TelephonyManager.NETWORK_TYPE_BITMASK_HSPA;
            case RIL_RADIO_TECHNOLOGY_IS95A:
            case RIL_RADIO_TECHNOLOGY_IS95B:
                return (int) TelephonyManager.NETWORK_TYPE_BITMASK_CDMA;
            case RIL_RADIO_TECHNOLOGY_1xRTT:
                return (int) TelephonyManager.NETWORK_TYPE_BITMASK_1xRTT;
            case RIL_RADIO_TECHNOLOGY_EVDO_0:
                return (int) TelephonyManager.NETWORK_TYPE_BITMASK_EVDO_0;
            case RIL_RADIO_TECHNOLOGY_EVDO_A:
                return (int) TelephonyManager.NETWORK_TYPE_BITMASK_EVDO_A;
            case RIL_RADIO_TECHNOLOGY_EVDO_B:
                return (int) TelephonyManager.NETWORK_TYPE_BITMASK_EVDO_B;
            case RIL_RADIO_TECHNOLOGY_EHRPD:
                return (int) TelephonyManager.NETWORK_TYPE_BITMASK_EHRPD;
            case RIL_RADIO_TECHNOLOGY_LTE:
                return (int) TelephonyManager.NETWORK_TYPE_BITMASK_LTE;
            case RIL_RADIO_TECHNOLOGY_HSPAP:
                return (int) TelephonyManager.NETWORK_TYPE_BITMASK_HSPAP;
            case RIL_RADIO_TECHNOLOGY_GSM:
                return (int) TelephonyManager.NETWORK_TYPE_BITMASK_GSM;
            case RIL_RADIO_TECHNOLOGY_TD_SCDMA:
                return (int) TelephonyManager.NETWORK_TYPE_BITMASK_TD_SCDMA;
            case RIL_RADIO_TECHNOLOGY_IWLAN:
                return (int) TelephonyManager.NETWORK_TYPE_BITMASK_IWLAN;
            case RIL_RADIO_TECHNOLOGY_LTE_CA:
                return (int) TelephonyManager.NETWORK_TYPE_BITMASK_LTE_CA;
            case RIL_RADIO_TECHNOLOGY_NR:
                return (int) TelephonyManager.NETWORK_TYPE_BITMASK_NR;
            default:
                return (int) TelephonyManager.NETWORK_TYPE_BITMASK_UNKNOWN;
        }
    }

    /**
     * Convert network type bitmask to bearer bitmask.
     *
     * @param networkTypeBitmask The network type bitmask value
     * @return The bearer bitmask value.
     */
    private static int convertNetworkTypeBitmaskToBearerBitmask(int networkTypeBitmask) {
        if (networkTypeBitmask == 0) {
            return 0;
        }

        int bearerBitmask = 0;
        for (int bearerInt = 0; bearerInt < NEXT_RIL_RADIO_TECHNOLOGY; bearerInt++) {
            if (bitmaskHasTarget(networkTypeBitmask,
                    rilRadioTechnologyToNetworkTypeBitmask(bearerInt))) {
                bearerBitmask |= getBitmaskForTech(bearerInt);
            }
        }
        return bearerBitmask;
    }

    /**
     * Convert bearer bitmask to network type bitmask.
     *
     * @param bearerBitmask The bearer bitmask value.
     * @return The network type bitmask value.
     */
    private static int convertBearerBitmaskToNetworkTypeBitmask(int bearerBitmask) {
        if (bearerBitmask == 0) {
            return 0;
        }

        int networkTypeBitmask = 0;
        for (int bearerUnitInt = 0; bearerUnitInt < NEXT_RIL_RADIO_TECHNOLOGY; bearerUnitInt++) {
            int bearerUnitBitmask = getBitmaskForTech(bearerUnitInt);
            if (bitmaskHasTarget(bearerBitmask, bearerUnitBitmask)) {
                networkTypeBitmask |= rilRadioTechnologyToNetworkTypeBitmask(bearerUnitInt);
            }
        }
        return networkTypeBitmask;
    }

    private static boolean bitmaskHasTarget(int bearerBitmask, int targetBitmask) {
        if (bearerBitmask == 0) {
            return true;
        } else if (targetBitmask != 0) {
            return ((bearerBitmask & targetBitmask) != 0);
        }
        return false;
    }

    private static int getBitmaskForTech(int radioTech) {
        if (radioTech >= 1) {
            return (1 << (radioTech - 1));
        }
        return 0;
    }
}
