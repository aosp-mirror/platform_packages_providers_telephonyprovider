/*
 * Copyright 2019 The Android Open Source Project
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

import android.util.Log;

public class SqlQueryChecker {
    private static final String SELECT_TOKEN = "select";

    static void checkToken(String token) {
        if (SELECT_TOKEN.equalsIgnoreCase(token)) {
            throw new IllegalArgumentException("SELECT token not allowed in query");
        }
    }

    /**
     * Check the query parameters to see if they contain subqueries. Throws an
     * {@link IllegalArgumentException} if they do. See
     * {@link android.content.ContentProvider#query} for the definitions of the arguments.
     */
    static void checkQueryParametersForSubqueries(String[] projection,
            String selection, String sortOrder) {
        Log.v("MmsProvider", "inside checkQueryParametersForSubqueries");
        if (projection != null) {
            for (String proj : projection) {
                Log.v("MmsProvider", "checkQueryParametersForSubqueries checking proj: " + proj);
                SQLiteTokenizer.tokenize(proj, SQLiteTokenizer.OPTION_NONE,
                        SqlQueryChecker::checkToken);
            }
        }
        Log.v("MmsProvider", "checkQueryParametersForSubqueries checking sel: " + selection);
        SQLiteTokenizer.tokenize(selection, SQLiteTokenizer.OPTION_NONE,
                SqlQueryChecker::checkToken);
        Log.v("MmsProvider", "checkQueryParametersForSubqueries checking sort: " + sortOrder);
        SQLiteTokenizer.tokenize(sortOrder, SQLiteTokenizer.OPTION_NONE,
                SqlQueryChecker::checkToken);
    }
}
