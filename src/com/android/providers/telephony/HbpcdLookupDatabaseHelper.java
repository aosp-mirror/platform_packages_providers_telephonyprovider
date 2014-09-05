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

/*
 * This class is used to create, load tables for HBPCD
 * HBPCD means 'Handset Based Plus Code Dialing', for CDMA network, most of network
 * couldn't handle international dialing number with '+', it need to be converted
 * to a IDD (International Direct Dialing) number, and some CDMA network won't
 * broadcast operator numeric, we need CDMA system ID and timezone etc. information
 * to get right MCC part of numeric, MNC part of numeric has no way to get in this
 * case, but for HBPCD, the MCC is enough.
 *
 * Table TABLE_MCC_LOOKUP_TABLE
 * This table has country name, country code, time zones for each MCC
 *
 * Table TABLE_MCC_IDD
 * This table has the IDDs for each MCC, some countries have multiple IDDs.
 *
 * Table TABLE_MCC_SID_RANGE
 * This table are SIDs assigned to each MCC
 *
 * Table TABLE_MCC_SID_CONFLICT
 * This table shows those SIDs are assigned to more than 1 MCC entry,
 * if the SID is here, it means the SID couldn't be matched to a single MCC,
 * it need to check the time zone and SID in TABLE_MCC_LOOKUP_TABLE to get
 * right MCC.
 *
 * Table TABLE_ARBITRARY_MCC_SID_MATCH
 * The SID listed in this table technically have operators in multiple MCC,
 * but conveniently only have *active* operators in a single MCC allowing a
 * unique SID->MCC lookup.  Lookup by Timezone however would be complicatedi
 * as there will be multiple matches, and those matched entries have same
 * time zone, which can not tell which MCC is right. Conventionaly it is known
 * that SID is used only by the *active* operators in that MCC.
 *
 * Table TABLE_NANP_AREA_CODE
 * This table has NANP(North America Number Planning) area code, this is used
 * to check if a dialing number is a NANP number.
 */

package com.android.providers.telephony;

import android.content.ContentValues;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.util.Log;
import android.util.Xml;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

import com.android.internal.telephony.HbpcdLookup;
import com.android.internal.telephony.HbpcdLookup.MccIdd;
import com.android.internal.telephony.HbpcdLookup.MccLookup;
import com.android.internal.telephony.HbpcdLookup.MccSidConflicts;
import com.android.internal.telephony.HbpcdLookup.MccSidRange;
import com.android.internal.telephony.HbpcdLookup.ArbitraryMccSidMatch;
import com.android.internal.telephony.HbpcdLookup.NanpAreaCode;

public class HbpcdLookupDatabaseHelper extends SQLiteOpenHelper {
    private static final String TAG = "HbpcdLockupDatabaseHelper";
    private static final boolean DBG = true;

    private static final String DATABASE_NAME = "HbpcdLookup.db";
    private static final int DATABASE_VERSION = 1;

    // Context to access resources with
    private Context mContext;

    /**
     * DatabaseHelper helper class for loading apns into a database.
     *
     * @param context of the user.
     */
    public HbpcdLookupDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);

        mContext = context;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        //set up the database schema
        // 1 MCC may has more IDDs
        db.execSQL("CREATE TABLE " + HbpcdLookupProvider.TABLE_MCC_IDD +
            "(_id INTEGER PRIMARY KEY," +
                "MCC INTEGER," +
                "IDD TEXT);");

        db.execSQL("CREATE TABLE " + HbpcdLookupProvider.TABLE_MCC_LOOKUP_TABLE +
            "(_id INTEGER PRIMARY KEY," +
                "MCC INTEGER," +
                "Country_Code TEXT," +
                "Country_Name TEXT," +
                "NDD TEXT," +
                "NANPS BOOLEAN," +
                "GMT_Offset_Low REAL," +
                "GMT_Offset_High REAL," +
                "GMT_DST_Low REAL," +
                "GMT_DST_High REAL);");

        db.execSQL("CREATE TABLE " + HbpcdLookupProvider.TABLE_MCC_SID_CONFLICT +
            "(_id INTEGER PRIMARY KEY," +
                "MCC INTEGER," +
                "SID_Conflict INTEGER);");

        db.execSQL("CREATE TABLE " + HbpcdLookupProvider.TABLE_MCC_SID_RANGE +
            "(_id INTEGER PRIMARY KEY," +
                "MCC INTEGER," +
                "SID_Range_Low INTEGER," +
                "SID_Range_High INTEGER);");

        db.execSQL("CREATE TABLE " + HbpcdLookupProvider.TABLE_NANP_AREA_CODE +
            "(_id INTEGER PRIMARY KEY," +
                "AREA_CODE INTEGER UNIQUE);");

        db.execSQL("CREATE TABLE " + HbpcdLookupProvider.TABLE_ARBITRARY_MCC_SID_MATCH +
            "(_id INTEGER PRIMARY KEY," +
                "MCC INTEGER," +
                "SID INTEGER UNIQUE);");

        initDatabase(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // do nothing
    }

    private void initDatabase (SQLiteDatabase db) {
        // Read internal data from xml
        Resources r = mContext.getResources();
        XmlResourceParser parser = r.getXml(R.xml.hbpcd_lookup_tables);

        if (parser == null) {
           Log.e (TAG, "error to load the HBPCD resource");
        } else {
            try {
                db.beginTransaction();
                XmlUtils.beginDocument(parser, "hbpcd_info");

                int eventType = parser.getEventType();
                String tagName = parser.getName();

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG
                            && tagName.equalsIgnoreCase("table")) {
                        String tableName = parser.getAttributeValue(null, "name");
                        loadTable(db, parser, tableName);
                    }
                    parser.next();
                    eventType = parser.getEventType();
                    tagName = parser.getName();
                }
                db.setTransactionSuccessful();
            } catch (XmlPullParserException e) {
                Log.e (TAG, "Got XmlPullParserException when load hbpcd info");
            } catch (IOException e) {
                Log.e (TAG, "Got IOException when load hbpcd info");
            } catch (SQLException e) {
                Log.e (TAG, "Got SQLException when load hbpcd info");
            } finally {
                db.endTransaction();
                parser.close();
            }
        }
    }

    private void loadTable(SQLiteDatabase db, XmlPullParser parser, String tableName)
            throws XmlPullParserException, IOException {
        int eventType = parser.getEventType();
        String tagName = parser.getName();
        while (!(eventType == XmlPullParser.END_TAG
                && tagName.equalsIgnoreCase("table"))) {
            ContentValues row = null;
            if (tableName.equalsIgnoreCase(HbpcdLookupProvider.TABLE_MCC_IDD)) {
                row = getTableMccIddRow(parser);
            } else if (tableName.equalsIgnoreCase(HbpcdLookupProvider.TABLE_MCC_LOOKUP_TABLE)) {
                row = getTableMccLookupTableRow(parser);
            } else if (tableName.equalsIgnoreCase(HbpcdLookupProvider.TABLE_MCC_SID_CONFLICT)) {
                row = getTableMccSidConflictRow(parser);
            } else if (tableName.equalsIgnoreCase(HbpcdLookupProvider.TABLE_MCC_SID_RANGE)) {
                row = getTableMccSidRangeRow(parser);
            } else if (tableName.equalsIgnoreCase(HbpcdLookupProvider.TABLE_NANP_AREA_CODE)) {
                row = getTableNanpAreaCodeRow(parser);
            } else if (tableName.equalsIgnoreCase(
                    HbpcdLookupProvider.TABLE_ARBITRARY_MCC_SID_MATCH)) {
                row = getTableArbitraryMccSidMatch(parser);
            } else {
                Log.e(TAG, "unrecognized table name"  + tableName);
                break;
            }
            if (row != null) {
                db.insert(tableName, null, row);
            }
            parser.next();
            eventType = parser.getEventType();
            tagName = parser.getName();
        }
    }

    private ContentValues getTableMccIddRow(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        int eventType = parser.getEventType();
        String tagName = parser.getName();
        ContentValues row = new ContentValues();

        while (!(eventType == XmlPullParser.END_TAG && tagName.equalsIgnoreCase("row"))) {
            if (eventType == XmlPullParser.START_TAG) {
                if (tagName.equalsIgnoreCase(MccIdd.MCC)) {
                    row.put(MccIdd.MCC, Integer.parseInt(parser.nextText()));
                } else if (tagName.equalsIgnoreCase(MccIdd.IDD)) {
                    row.put(MccIdd.IDD, parser.nextText());
                }
            }
            parser.next();
            eventType = parser.getEventType();
            tagName = parser.getName();
        }
        return row;
    }

    private ContentValues getTableMccLookupTableRow(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        int eventType = parser.getEventType();
        String tagName = parser.getName();
        ContentValues row = new ContentValues();

        while (!(eventType == XmlPullParser.END_TAG && tagName.equalsIgnoreCase("row"))) {
            if (eventType == XmlPullParser.START_TAG) {
                if (tagName.equalsIgnoreCase(MccLookup.MCC)) {
                    row.put(MccLookup.MCC, Integer.parseInt(parser.nextText()));
                } else if (tagName.equalsIgnoreCase(MccLookup.COUNTRY_CODE)) {
                    row.put(MccLookup.COUNTRY_CODE, Integer.parseInt(parser.nextText()));
                } else if (tagName.equalsIgnoreCase(MccLookup.COUNTRY_NAME)) {
                    row.put(MccLookup.COUNTRY_NAME, parser.nextText());
                } else if (tagName.equalsIgnoreCase(MccLookup.NDD)) {
                    row.put(MccLookup.NDD, parser.nextText());
                } else if (tagName.equalsIgnoreCase(MccLookup.NANPS)) {
                    row.put(MccLookup.NANPS, Boolean.parseBoolean(parser.nextText()));
                } else if (tagName.equalsIgnoreCase(MccLookup.GMT_OFFSET_LOW)) {
                    row.put(MccLookup.GMT_OFFSET_LOW, Float.parseFloat(parser.nextText()));
                } else if (tagName.equalsIgnoreCase(MccLookup.GMT_OFFSET_HIGH)) {
                    row.put(MccLookup.GMT_OFFSET_HIGH, Float.parseFloat(parser.nextText()));
                } else if (tagName.equalsIgnoreCase(MccLookup.GMT_DST_LOW)) {
                    row.put(MccLookup.GMT_DST_LOW, Float.parseFloat(parser.nextText()));
                } else if (tagName.equalsIgnoreCase(MccLookup.GMT_DST_HIGH)) {
                    row.put(MccLookup.GMT_DST_HIGH, Float.parseFloat(parser.nextText()));
                }
            }
            parser.next();
            eventType = parser.getEventType();
            tagName = parser.getName();
        }
        return row;
    }

    private ContentValues getTableMccSidConflictRow(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        int eventType = parser.getEventType();
        String tagName = parser.getName();
        ContentValues row = new ContentValues();

        while (!(eventType == XmlPullParser.END_TAG && tagName.equalsIgnoreCase("row"))) {
            if (eventType == XmlPullParser.START_TAG) {
                if (tagName.equalsIgnoreCase(MccSidConflicts.MCC)) {
                    row.put(MccSidConflicts.MCC, Integer.parseInt(parser.nextText()));
                } else if (tagName.equalsIgnoreCase(MccSidConflicts.SID_CONFLICT)) {
                    row.put(MccSidConflicts.SID_CONFLICT, Integer.parseInt(parser.nextText()));
                }
            }
            parser.next();
            eventType = parser.getEventType();
            tagName = parser.getName();
        }
        return row;
    }

    private ContentValues getTableMccSidRangeRow(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        int eventType = parser.getEventType();
        String tagName = parser.getName();
        ContentValues row = new ContentValues();

        while (!(eventType == XmlPullParser.END_TAG && tagName.equalsIgnoreCase("row"))) {
            if (eventType == XmlPullParser.START_TAG) {
                if (tagName.equalsIgnoreCase(MccSidRange.MCC)) {
                    row.put(MccSidRange.MCC, Integer.parseInt(parser.nextText()));
                } else if (tagName.equalsIgnoreCase(MccSidRange.RANGE_LOW)) {
                    row.put(MccSidRange.RANGE_LOW, Integer.parseInt(parser.nextText()));
                } else if (tagName.equalsIgnoreCase(MccSidRange.RANGE_HIGH)) {
                    row.put(MccSidRange.RANGE_HIGH, Integer.parseInt(parser.nextText()));
                }
            }
            parser.next();
            eventType = parser.getEventType();
            tagName = parser.getName();
       }
       return row;
    }

    private ContentValues getTableNanpAreaCodeRow(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        int eventType = parser.getEventType();
        String tagName = parser.getName();
        ContentValues row = new ContentValues();

        while (!(eventType == XmlPullParser.END_TAG && tagName.equalsIgnoreCase("row"))) {
            if (eventType == XmlPullParser.START_TAG) {
                if (tagName.equalsIgnoreCase(NanpAreaCode.AREA_CODE)) {
                    row.put(NanpAreaCode.AREA_CODE, Integer.parseInt(parser.nextText()));
                }
            }
            parser.next();
            eventType = parser.getEventType();
            tagName = parser.getName();
        }
        return row;
    }

    private ContentValues getTableArbitraryMccSidMatch(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        int eventType = parser.getEventType();
        String tagName = parser.getName();
        ContentValues row = new ContentValues();

        while (!(eventType == XmlPullParser.END_TAG && tagName.equalsIgnoreCase("row"))) {
            if (eventType == XmlPullParser.START_TAG) {
                if (tagName.equalsIgnoreCase(ArbitraryMccSidMatch.MCC)) {
                    row.put(ArbitraryMccSidMatch.MCC, Integer.parseInt(parser.nextText()));
                } else if (tagName.equalsIgnoreCase(ArbitraryMccSidMatch.SID)) {
                    row.put(ArbitraryMccSidMatch.SID, Integer.parseInt(parser.nextText()));
                }
            }
            parser.next();
            eventType = parser.getEventType();
            tagName = parser.getName();
        }
        return row;
    }
}
