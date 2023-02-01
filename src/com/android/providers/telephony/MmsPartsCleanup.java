/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static java.util.Arrays.stream;

import android.annotation.NonNull;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.FileUtils;
import android.provider.Telephony;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/** Tools to find and delete unreferenced MMS parts from the parts directory. */
public class MmsPartsCleanup {
    private static final String TAG = "MmsPartsCleanup";

    static final String PART_FILE_COUNT = "part_file_count";
    static final String PART_TABLE_ENTRY_COUNT = "part_table_entry_count";
    static final String DELETED_COUNT = "deleted_count";

    /**
     * @param context
     * @param doDelete if true, delete the unreferenced MMS part files found in the data dir.
     *                 if false, compute the number of unreferenced part files and return the
     *                 results, but don't actually delete the files
     * @param bundle an existing bundle where int values will be added:
     *               PART_FILE_COUNT - an int count of MMS attachment files in the parts
     *                                 directory
     *               PART_TABLE_ENTRY_COUNT - an int count of MMS attachments referenced
     *                                        by existing MMS messages
     *               DELETED_COUNT - the number of non-referenced MMS part files delete (or would
     *                               be deleted if doDelete is true)
     */
    public static void cleanupDanglingParts(Context context, boolean doDelete, Bundle bundle) {
        Set<String> danglingFilePathsToDelete = getDanglingMmsParts(context, bundle);
        danglingFilePathsToDelete.forEach(path -> {
            File partFile = new File(path);
            if (partFile.exists()) {
                if (doDelete) {
                    Log.d(TAG, "Deleting dangling MMS part: " + partFile.getAbsolutePath());
                    partFile.delete();
                } else {
                    Log.d(TAG,
                            "Would have deleted dangling MMS part: "
                                    + partFile.getAbsolutePath());
                }
            } else {
                Log.wtf(TAG,
                        "Part file path does not exist: "
                                + partFile.getAbsolutePath());
            }
        });
        bundle.putInt(DELETED_COUNT, danglingFilePathsToDelete.size());
    }

    /**
     * @param context
     * @return a set of file path names for every MMS attachment file found in the data directory
     */
    @NonNull
    private static Set<String> getAllMmsPartsInPartsDir(Context context) {
        Set<String> allMmsAttachments = new HashSet<>();
        try {
            String partsDirPath = context.getDir(MmsProvider.PARTS_DIR_NAME, 0)
                    .getCanonicalPath();
            Log.d(TAG, "getDanglingMmsParts: " + partsDirPath);
            File partsDir = new File(partsDirPath);
            allMmsAttachments = Arrays.stream(FileUtils.listFilesOrEmpty(partsDir)).map(p ->
            {
                try {
                    return p.getCanonicalPath();
                } catch (IOException e) {
                    Log.d(TAG, "getDanglingMmsParts: couldn't get path for " + p);
                }
                return null;
            }).filter(path -> path != null).collect(Collectors.toSet());
        } catch (IOException e) {
            Log.e(TAG, "getDanglingMmsParts: failed " + e, e);
            allMmsAttachments.clear();
        }
        return allMmsAttachments;
    }

    /**
     * @param partFilePaths the incoming set contains the file paths of all MMS attachments in the
     *                      data directory. This function modifies this set by removing any path
     *                      that is referenced from an MMS part in the parts table. In other words,
     *                      when this function returns, partFilePaths contains only the unreferenced
     *                      file paths
     * @param context
     * @return the number of MMS parts that are referenced and still attached to an MMS message
     */
    @NonNull
    private static int removeReferencedPartsFromSet(Set<String> partFilePaths, Context context) {
        if (partFilePaths.isEmpty()) {
            return 0;
        }
        SQLiteDatabase db = MmsSmsDatabaseHelper.getInstanceForCe(context).getReadableDatabase();
        int referencedPartCount = 0;
        try (Cursor partRows =
                     db.query(
                             MmsProvider.TABLE_PART,
                             new String[] {Telephony.Mms.Part._DATA},
                             Telephony.Mms.Part._DATA + " IS NOT NULL AND "
                                     + Telephony.Mms.Part._DATA + " <> ''",
                             null,
                             null,
                             null,
                             null)) {
            if (partRows != null) {
                while (partRows.moveToNext()) {
                    String path = partRows.getString(0);
                    if (path != null) {
                        if (partFilePaths.remove(path)) {
                            ++referencedPartCount;
                        }
                    }
                }
            }
        }
        return referencedPartCount;
    }

    /**
     * @param context
     * @param bundle an existing bundle that is modified to contain two possible key/values:
     *               PART_FILE_COUNT - an int count of MMS attachment files in the parts directory
     *               PART_TABLE_ENTRY_COUNT - an int count of MMS attachments referenced by existing
     *                                        MMS messages
     * @return a set of file path names for MMS attachments that live in the data directory, but
     *         aren't referenced by any existing MMS message
     */
    @NonNull
    private static Set<String> getDanglingMmsParts(Context context, Bundle bundle) {
        Set<String> allExistingMmsAttachments = getAllMmsPartsInPartsDir(context);
        bundle.putInt(PART_FILE_COUNT, allExistingMmsAttachments.size());
        bundle.putInt(PART_TABLE_ENTRY_COUNT,
                removeReferencedPartsFromSet(allExistingMmsAttachments, context));

        return allExistingMmsAttachments;
    }
}
