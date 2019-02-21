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
 * limitations under the License
 */

package com.android.providers.telephony;

import static android.provider.Telephony.RcsColumns.Rcs1To1ThreadColumns.FALLBACK_THREAD_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsParticipantColumns.RCS_PARTICIPANT_ID_COLUMN;

import static com.google.common.truth.Truth.assertThat;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;

class RcsProviderHelper {
    /**
     * Sets up a 1-to-1 thread with a participant ID of 1 and a fallback thread ID of 1.
     *
     * @return the {@link Uri} of the newly inserted row
     */
    static Uri setup1To1Thread(ContentResolver contentResolver) {
        return setup1To1Thread(contentResolver, 1, 1);
    }

    static Uri setup1To1Thread(
            ContentResolver contentResolver, int participantId, int fallbackThreadId) {
        ContentValues insertValues = new ContentValues();
        Uri insertionUri = Uri.parse("content://rcs/p2p_thread");
        insertValues.put(RCS_PARTICIPANT_ID_COLUMN, participantId);

        Uri rowUri = contentResolver.insert(insertionUri, insertValues);
        assertThat(rowUri).isNotNull();

        ContentValues updateValues = new ContentValues();
        updateValues.put(FALLBACK_THREAD_ID_COLUMN, fallbackThreadId);
        assertThat(contentResolver.update(rowUri, updateValues, null, null))
                .isEqualTo(1);

        return rowUri;
    }

}
