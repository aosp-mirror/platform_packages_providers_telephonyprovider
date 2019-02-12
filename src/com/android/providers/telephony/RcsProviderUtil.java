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

import static android.provider.Telephony.RcsColumns.TRANSACTION_FAILED;

import static android.telephony.ims.RcsQueryContinuationToken.QUERY_CONTINUATION_TOKEN;
import static com.android.providers.telephony.RcsProvider.TAG;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.telephony.ims.RcsQueryContinuationToken;
import android.text.TextUtils;
import android.util.Log;

class RcsProviderUtil {
    /**
     * The value returned when database insertion fails,
     * @see SQLiteDatabase#insert(String, String, ContentValues)
     */
    static final int INSERTION_FAILED = -1;

    static void appendLimit(StringBuilder stringBuilder, int limit) {
        if (limit > 0) {
            stringBuilder.append(" LIMIT ").append(limit);
        }
    }

    static void createContinuationTokenBundle(Cursor cursor, Parcelable continuationToken,
            String tokenKey) {
        if (cursor.getCount() > 0) {
            Bundle bundle = new Bundle();
            bundle.putParcelable(tokenKey, continuationToken);
            cursor.setExtras(bundle);
        }
    }

    /**
     * This method gets a token with raw query, performs the query, increments the token's offset
     * and returns it as a cursor extra
     */
    static Cursor performContinuationQuery(SQLiteDatabase db,
            RcsQueryContinuationToken continuationToken) {
        String rawQuery = continuationToken.getRawQuery();
        int offset = continuationToken.getOffset();

        if (offset <= 0 || TextUtils.isEmpty(rawQuery)) {
            Log.e(TAG, "RcsProviderUtil: Invalid continuation token");
            return null;
        }

        String continuationQuery = rawQuery + " OFFSET " + offset;

        Cursor cursor = db.rawQuery(continuationQuery, null);
        if (cursor.getCount() > 0) {
            continuationToken.incrementOffset();
            Bundle bundle = new Bundle();
            bundle.putParcelable(QUERY_CONTINUATION_TOKEN, continuationToken);
            cursor.setExtras(bundle);
        }

        return cursor;
    }

    static Uri buildUriWithRowIdAppended(Uri prefix, long rowId) {
        if (rowId == TRANSACTION_FAILED) {
            return null;
        }
        return Uri.withAppendedPath(prefix, Long.toString(rowId));
    }

    static Uri returnUriAsIsIfSuccessful(Uri uri, long rowId) {
        if (rowId == TRANSACTION_FAILED) {
            return null;
        }
        return uri;
    }
}
