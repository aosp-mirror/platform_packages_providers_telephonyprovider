/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import android.app.AppOpsManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.telephony.TelephonyManager;
import android.test.mock.MockContentResolver;
import android.test.mock.MockContext;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

public class RcsProviderTest {
    private MockContentResolver mContentResolver;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        RcsProvider rcsProvider = new RcsProviderTestable();
        MockContextWithProvider context = new MockContextWithProvider(rcsProvider);
        mContentResolver = context.getContentResolver();
    }

    // TODO(sahinc): This test isn't that useful for now as it only checks the return value. Revisit
    // once we have more of the implementation in place.
    @Test
    public void testInsertThread() {
        ContentValues contentValues = new ContentValues();
        contentValues.put(RcsProviderThreadHelper.OWNER_PARTICIPANT, 5);

        Uri uri = mContentResolver.insert(Uri.parse("content://rcs/thread"), contentValues);
        assertThat(uri).isNull();
    }

    @Test
    public void testUpdateThread() {
        ContentValues contentValues = new ContentValues();
        contentValues.put(RcsProviderThreadHelper.OWNER_PARTICIPANT, 5);
        mContentResolver.insert(Uri.parse("content://rcs/thread"), contentValues);

        contentValues.put(RcsProviderThreadHelper.OWNER_PARTICIPANT, 12);
        int updateCount = mContentResolver.update(Uri.parse("content://rcs/thread"),
                contentValues, "owner_participant=5", null);

        assertThat(updateCount).isEqualTo(1);
    }

    @Test
    public void testCanQueryAllThreads() {
        // insert two threads
        ContentValues contentValues = new ContentValues();
        Uri threadsUri = Uri.parse("content://rcs/thread");
        contentValues.put(RcsProviderThreadHelper.OWNER_PARTICIPANT, 7);
        mContentResolver.insert(threadsUri, contentValues);

        contentValues.put(RcsProviderThreadHelper.OWNER_PARTICIPANT, 13);
        mContentResolver.insert(threadsUri, contentValues);

        //verify two threads are inserted
        Cursor cursor = mContentResolver.query(threadsUri, null, null, null, null);
        assertThat(cursor.getCount()).isEqualTo(2);
    }

    class MockContextWithProvider extends MockContext {
        private final MockContentResolver mResolver;

        MockContextWithProvider(RcsProvider rcsProvider) {
            mResolver = new MockContentResolver();

            // Add authority="rcs" to given smsProvider
            ProviderInfo providerInfo = new ProviderInfo();
            providerInfo.authority = RcsProvider.AUTHORITY;
            rcsProvider.attachInfoForTesting(this, providerInfo);
            mResolver.addProvider(RcsProvider.AUTHORITY, rcsProvider);
        }

        @Override
        public MockContentResolver getContentResolver() {
            return mResolver;
        }

        @Override
        public Object getSystemService(String name) {
            switch (name) {
                case Context.APP_OPS_SERVICE:
                    return Mockito.mock(AppOpsManager.class);
                case Context.TELEPHONY_SERVICE:
                    return Mockito.mock(TelephonyManager.class);
                default:
                    return null;
            }
        }
    }
}
