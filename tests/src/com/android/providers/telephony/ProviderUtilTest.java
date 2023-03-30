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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.UserHandle;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

public class ProviderUtilTest {
    private static final String TAG = "ProviderUtilTest";

    private Context mContext;
    @Mock
    private SubscriptionManager mSubscriptionManager;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext = spy(ApplicationProvider.getApplicationContext());

        when(mContext.getSystemService(SubscriptionManager.class)).thenReturn(mSubscriptionManager);
    }

    @After
    public void tearDown() throws Exception {
    }

    @Test
    public void getSelectionBySubIds_noSubscription() {
        List<SubscriptionInfo> subscriptionInfoList = new ArrayList<>();
        doReturn(subscriptionInfoList).when(mSubscriptionManager)
                .getSubscriptionInfoListAssociatedWithUser(UserHandle.SYSTEM);

        assertThat(ProviderUtil.getSelectionBySubIds(mContext, UserHandle.SYSTEM))
                .isEqualTo("sub_id IN ('-1')");
    }

    @Test
    public void getSelectionBySubIds_withDefaultSubId() {
        // As sub_id is not set explicitly, its value will be -1
        SubscriptionInfo subscriptionInfo1 = new SubscriptionInfo.Builder()
                .setSimSlotIndex(0)
                .build();
        List<SubscriptionInfo> subscriptionInfoList = new ArrayList<>();
        subscriptionInfoList.add(subscriptionInfo1);

        doReturn(subscriptionInfoList).when(mSubscriptionManager)
                .getSubscriptionInfoListAssociatedWithUser(UserHandle.SYSTEM);
        assertThat(ProviderUtil.getSelectionBySubIds(mContext, UserHandle.SYSTEM))
                .isEqualTo("sub_id IN ('-1','-1')");
    }

    @Test
    public void getSelectionBySubIds_withActiveSubscriptions() {
        SubscriptionInfo subscriptionInfo1 = new SubscriptionInfo.Builder()
                .setId(1)
                .setSimSlotIndex(0)
                .build();
        List<SubscriptionInfo> subscriptionInfoList = new ArrayList<>();

        SubscriptionInfo subscriptionInfo2 = new SubscriptionInfo.Builder()
                .setId(2)
                .setSimSlotIndex(1)
                .build();

        subscriptionInfoList.add(subscriptionInfo1);
        subscriptionInfoList.add(subscriptionInfo2);
        doReturn(subscriptionInfoList).when(mSubscriptionManager)
                .getSubscriptionInfoListAssociatedWithUser(UserHandle.SYSTEM);

        assertThat(ProviderUtil.getSelectionBySubIds(mContext, UserHandle.SYSTEM))
                .isEqualTo("sub_id IN ('1','2','-1')");
    }
}