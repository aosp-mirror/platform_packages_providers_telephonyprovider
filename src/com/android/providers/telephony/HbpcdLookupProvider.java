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

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.database.Cursor;
import android.database.SQLException;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import java.util.HashMap;

import com.android.internal.telephony.HbpcdLookup;
import com.android.internal.telephony.HbpcdLookup.MccIdd;
import com.android.internal.telephony.HbpcdLookup.MccLookup;
import com.android.internal.telephony.HbpcdLookup.MccSidConflicts;
import com.android.internal.telephony.HbpcdLookup.ArbitraryMccSidMatch;
import com.android.internal.telephony.HbpcdLookup.MccSidRange;
import com.android.internal.telephony.HbpcdLookup.NanpAreaCode;

public class HbpcdLookupProvider extends ContentProvider {
    private static boolean DBG = false;
    private static final String TAG = "HbpcdLookupProvider";

    public static final String TABLE_MCC_IDD = "mcc_idd";
    public static final String TABLE_MCC_LOOKUP_TABLE = "mcc_lookup_table";
    public static final String TABLE_MCC_SID_CONFLICT = "mcc_sid_conflict";
    public static final String TABLE_MCC_SID_RANGE = "mcc_sid_range";
    public static final String TABLE_NANP_AREA_CODE = "nanp_area_code";
    public static final String TABLE_ARBITRARY_MCC_SID_MATCH= "arbitrary_mcc_sid_match";

    private static final int MCC_IDD = 1;
    private static final int MCC_LOOKUP_TABLE = 2;
    private static final int MCC_SID_CONFLICT = 3;
    private static final int MCC_SID_RANGE = 4;
    private static final int NANP_AREA_CODE = 5;
    private static final int ARBITRARY_MCC_SID_MATCH = 6;
    private static final int MCC_IDD_ID = 8;
    private static final int MCC_LOOKUP_TABLE_ID = 9;
    private static final int MCC_SID_CONFLICT_ID = 10;
    private static final int MCC_SID_RANGE_ID = 11;
    private static final int NANP_AREA_CODE_ID = 12;
    private static final int ARBITRARY_MCC_SID_MATCH_ID = 13;

    private static final UriMatcher sURIMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    private static final HashMap<String, String> sIddProjectionMap;
    private static final HashMap<String, String> sLookupProjectionMap;
    private static final HashMap<String, String> sConflictProjectionMap;
    private static final HashMap<String, String> sRangeProjectionMap;
    private static final HashMap<String, String> sNanpProjectionMap;
    private static final HashMap<String, String> sArbitraryProjectionMap;

    static {
        sURIMatcher.addURI(HbpcdLookup.AUTHORITY, HbpcdLookup.PATH_MCC_IDD, MCC_IDD);
        sURIMatcher.addURI(HbpcdLookup.AUTHORITY,
                HbpcdLookup.PATH_MCC_LOOKUP_TABLE, MCC_LOOKUP_TABLE);
        // following URI is a joint table of MCC_LOOKUP_TABLE and MCC_SID_CONFLIct.
        sURIMatcher.addURI(HbpcdLookup.AUTHORITY,
                HbpcdLookup.PATH_MCC_SID_CONFLICT, MCC_SID_CONFLICT);
        sURIMatcher.addURI(HbpcdLookup.AUTHORITY, HbpcdLookup.PATH_MCC_SID_RANGE, MCC_SID_RANGE);
        sURIMatcher.addURI(HbpcdLookup.AUTHORITY, HbpcdLookup.PATH_NANP_AREA_CODE, NANP_AREA_CODE);
        sURIMatcher.addURI(HbpcdLookup.AUTHORITY,
                HbpcdLookup.PATH_ARBITRARY_MCC_SID_MATCH, ARBITRARY_MCC_SID_MATCH);
        sURIMatcher.addURI(HbpcdLookup.AUTHORITY, HbpcdLookup.PATH_MCC_IDD + "/#", MCC_IDD_ID);
        sURIMatcher.addURI(HbpcdLookup.AUTHORITY,
                HbpcdLookup.PATH_MCC_LOOKUP_TABLE + "/#", MCC_LOOKUP_TABLE_ID);
        sURIMatcher.addURI(HbpcdLookup.AUTHORITY,
                HbpcdLookup.PATH_MCC_SID_CONFLICT + "/#", MCC_SID_CONFLICT_ID);
        sURIMatcher.addURI(HbpcdLookup.AUTHORITY,
                HbpcdLookup.PATH_MCC_SID_RANGE + "/#", MCC_SID_RANGE_ID);
        sURIMatcher.addURI(HbpcdLookup.AUTHORITY,
                HbpcdLookup.PATH_NANP_AREA_CODE + "/#", NANP_AREA_CODE_ID);
        sURIMatcher.addURI(HbpcdLookup.AUTHORITY,
                HbpcdLookup.PATH_ARBITRARY_MCC_SID_MATCH + "/#", ARBITRARY_MCC_SID_MATCH_ID);

        sIddProjectionMap = new HashMap<String, String>();
        sIddProjectionMap.put(HbpcdLookup.ID, HbpcdLookup.ID);
        sIddProjectionMap.put(MccIdd.MCC, MccIdd.MCC);
        sIddProjectionMap.put(MccIdd.IDD, MccIdd.IDD);

        sLookupProjectionMap = new HashMap<String, String>();
        sLookupProjectionMap.put(HbpcdLookup.ID, HbpcdLookup.ID);
        sLookupProjectionMap.put(MccLookup.MCC, MccLookup.MCC);
        sLookupProjectionMap.put(MccLookup.COUNTRY_CODE, MccLookup.COUNTRY_CODE);
        sLookupProjectionMap.put(MccLookup.COUNTRY_NAME, MccLookup.COUNTRY_NAME);
        sLookupProjectionMap.put(MccLookup.NDD, MccLookup.NDD);
        sLookupProjectionMap.put(MccLookup.NANPS, MccLookup.NANPS);
        sLookupProjectionMap.put(MccLookup.GMT_OFFSET_LOW, MccLookup.GMT_OFFSET_LOW);
        sLookupProjectionMap.put(MccLookup.GMT_OFFSET_HIGH, MccLookup.GMT_OFFSET_HIGH);
        sLookupProjectionMap.put(MccLookup.GMT_DST_LOW, MccLookup.GMT_DST_LOW);
        sLookupProjectionMap.put(MccLookup.GMT_DST_HIGH, MccLookup.GMT_DST_HIGH);

        // when we do query, we will join it with MccLookup table
        sConflictProjectionMap = new HashMap<String, String>();
        // MccLookup.MCC is duped to MccSidConflicts.MCC
        sConflictProjectionMap.put(MccLookup.GMT_OFFSET_LOW,
                TABLE_MCC_LOOKUP_TABLE + "." + MccLookup.GMT_OFFSET_LOW);
        sConflictProjectionMap.put(MccLookup.GMT_OFFSET_HIGH,
                TABLE_MCC_LOOKUP_TABLE + "." + MccLookup.GMT_OFFSET_HIGH);
        sConflictProjectionMap.put(MccLookup.GMT_DST_LOW,
                TABLE_MCC_LOOKUP_TABLE + "." + MccLookup.GMT_DST_LOW);
        sConflictProjectionMap.put(MccLookup.GMT_DST_HIGH,
                TABLE_MCC_LOOKUP_TABLE + "." + MccLookup.GMT_DST_HIGH);
        sConflictProjectionMap.put(MccSidConflicts.MCC,
                TABLE_MCC_SID_CONFLICT + "." + MccSidConflicts.MCC);
        sConflictProjectionMap.put(MccSidConflicts.SID_CONFLICT,
                TABLE_MCC_SID_CONFLICT + "." + MccSidConflicts.SID_CONFLICT);

        sRangeProjectionMap = new HashMap<String, String>();
        sRangeProjectionMap.put(HbpcdLookup.ID, HbpcdLookup.ID);
        sRangeProjectionMap.put(MccSidRange.MCC, MccSidRange.MCC);
        sRangeProjectionMap.put(MccSidRange.RANGE_LOW, MccSidRange.RANGE_LOW);
        sRangeProjectionMap.put(MccSidRange.RANGE_HIGH, MccSidRange.RANGE_HIGH);

        sNanpProjectionMap = new HashMap<String, String>();
        sNanpProjectionMap.put(HbpcdLookup.ID, HbpcdLookup.ID);
        sNanpProjectionMap.put(NanpAreaCode.AREA_CODE, NanpAreaCode.AREA_CODE);

        sArbitraryProjectionMap = new HashMap<String, String>();
        sArbitraryProjectionMap.put(HbpcdLookup.ID, HbpcdLookup.ID);
        sArbitraryProjectionMap.put(ArbitraryMccSidMatch.MCC, ArbitraryMccSidMatch.MCC);
        sArbitraryProjectionMap.put(ArbitraryMccSidMatch.SID, ArbitraryMccSidMatch.SID);
    }

    private HbpcdLookupDatabaseHelper mDbHelper;

    @Override
    public boolean onCreate() {
        if (DBG) {
            Log.d(TAG, "onCreate");
        }
        mDbHelper = new HbpcdLookupDatabaseHelper(getContext());

        mDbHelper.getReadableDatabase();
        return true;
    }

    @Override
    public String getType(Uri uri) {
        if (DBG) {
            Log.d(TAG, "getType");
        }

        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projectionIn, String selection,
                        String[] selectionArgs, String sortOrder) {
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        String orderBy = null;
        String groupBy = null;
        boolean useDefaultOrder = TextUtils.isEmpty(sortOrder);

        int match = sURIMatcher.match(uri);
        switch (match) {
            case MCC_IDD: {
                qb.setTables(TABLE_MCC_IDD);
                qb.setProjectionMap(sIddProjectionMap);
                if (useDefaultOrder) {
                    orderBy = MccIdd.DEFAULT_SORT_ORDER;
                }
                break;
            }
            case MCC_LOOKUP_TABLE: {
                qb.setTables(TABLE_MCC_LOOKUP_TABLE);
                qb.setProjectionMap(sLookupProjectionMap);
                if (useDefaultOrder) {
                    orderBy = MccLookup.DEFAULT_SORT_ORDER;
                }
                groupBy = MccLookup.COUNTRY_NAME;
                break;
            }
            case MCC_SID_CONFLICT: {
                StringBuilder joinT = new StringBuilder();
                joinT.append(TABLE_MCC_LOOKUP_TABLE);
                joinT.append(" INNER JOIN ");
                joinT.append(TABLE_MCC_SID_CONFLICT);
                joinT.append(" ON (");
                joinT.append(TABLE_MCC_LOOKUP_TABLE); // table name
                joinT.append(".");
                joinT.append(MccLookup.MCC); // column name
                joinT.append(" = ");
                joinT.append(TABLE_MCC_SID_CONFLICT); // table name
                joinT.append(".");
                joinT.append(MccSidConflicts.MCC); //column name
                joinT.append(")");
                qb.setTables(joinT.toString());
                qb.setProjectionMap(sConflictProjectionMap);
                break;
            }
            case MCC_SID_RANGE: {
                qb.setTables(TABLE_MCC_SID_RANGE);
                qb.setProjectionMap(sRangeProjectionMap);
                if (useDefaultOrder) {
                    orderBy = MccIdd.DEFAULT_SORT_ORDER;
                }
                break;
            }
            case NANP_AREA_CODE: {
                qb.setTables(TABLE_NANP_AREA_CODE);
                qb.setProjectionMap(sNanpProjectionMap);
                if (useDefaultOrder) {
                    orderBy = NanpAreaCode.DEFAULT_SORT_ORDER;
                }
                break;
            }
            case ARBITRARY_MCC_SID_MATCH: {
                qb.setTables(TABLE_ARBITRARY_MCC_SID_MATCH);
                qb.setProjectionMap(sArbitraryProjectionMap);
                if (useDefaultOrder) {
                    orderBy = ArbitraryMccSidMatch.DEFAULT_SORT_ORDER;
                }
                break;
            }
            case MCC_IDD_ID: {
                qb.setTables(TABLE_MCC_IDD);
                qb.setProjectionMap(sIddProjectionMap);
                qb.appendWhere(TABLE_MCC_IDD + "._id=");
                qb.appendWhere(uri.getPathSegments().get(1));
                if (useDefaultOrder) {
                    orderBy = MccIdd.DEFAULT_SORT_ORDER;
                }
                break;
            }
            case MCC_LOOKUP_TABLE_ID: {
                qb.setTables(TABLE_MCC_LOOKUP_TABLE);
                qb.setProjectionMap(sLookupProjectionMap);
                qb.appendWhere(TABLE_MCC_LOOKUP_TABLE + "._id=");
                qb.appendWhere(uri.getPathSegments().get(1));
                if (useDefaultOrder) {
                    orderBy = MccLookup.DEFAULT_SORT_ORDER;
                }
                break;
            }
            case MCC_SID_CONFLICT_ID: {
                qb.setTables(TABLE_MCC_SID_CONFLICT);
                qb.appendWhere(TABLE_MCC_SID_CONFLICT + "._id=");
                qb.appendWhere(uri.getPathSegments().get(1));
                if (useDefaultOrder) {
                    orderBy = MccSidConflicts.DEFAULT_SORT_ORDER;
                }
                break;
            }
            case MCC_SID_RANGE_ID: {
                qb.setTables(TABLE_MCC_SID_RANGE);
                qb.setProjectionMap(sRangeProjectionMap);
                qb.appendWhere(TABLE_MCC_SID_RANGE + "._id=");
                qb.appendWhere(uri.getPathSegments().get(1));
                if (useDefaultOrder) {
                    orderBy = MccIdd.DEFAULT_SORT_ORDER;
                }
                break;
            }
            case NANP_AREA_CODE_ID: {
                qb.setTables(TABLE_NANP_AREA_CODE);
                qb.setProjectionMap(sNanpProjectionMap);
                qb.appendWhere(TABLE_NANP_AREA_CODE + "._id=");
                qb.appendWhere(uri.getPathSegments().get(1));
                if (useDefaultOrder) {
                    orderBy = NanpAreaCode.DEFAULT_SORT_ORDER;
                }
                break;
            }
            case ARBITRARY_MCC_SID_MATCH_ID: {
                qb.setTables(TABLE_ARBITRARY_MCC_SID_MATCH);
                qb.setProjectionMap(sArbitraryProjectionMap);
                qb.appendWhere(TABLE_ARBITRARY_MCC_SID_MATCH + "._id=");
                qb.appendWhere(uri.getPathSegments().get(1));
                if (useDefaultOrder) {
                    orderBy = ArbitraryMccSidMatch.DEFAULT_SORT_ORDER;
                }
                break;
            }
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }

        if (!useDefaultOrder) {
            orderBy = sortOrder;
        }

        SQLiteDatabase db = mDbHelper.getReadableDatabase();
        Cursor c = qb.query(db, projectionIn, selection, selectionArgs, groupBy, null, orderBy);
        if (c != null) {
            c.setNotificationUri(getContext().getContentResolver(), uri);
        }

        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Failed to insert row into " + uri);
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Cannot delete URL: " + uri);
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        int count = 0;
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();

        final int match= sURIMatcher.match(uri);
        switch (match) {
            case MCC_LOOKUP_TABLE:
                count = db.update(TABLE_MCC_LOOKUP_TABLE, values, selection, selectionArgs);
                break;
            default:
                throw new UnsupportedOperationException("Cannot update URL: " + uri);
        }

        return count;
    }
}
