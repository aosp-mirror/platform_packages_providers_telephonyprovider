/* //device/content/providers/telephony/TelephonyProvider.java
**
** Copyright 2016, The Android Open Source Project
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
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MatrixCursor.RowBuilder;
import android.net.Uri;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.SubscriptionController;

import java.lang.NumberFormatException;
import java.util.HashMap;
import java.util.Objects;

import static android.provider.Telephony.ServiceStateTable.getUriForSubscriptionId;
import static android.provider.Telephony.ServiceStateTable.getUriForSubscriptionIdAndField;

import static android.provider.Telephony.ServiceStateTable;
import static android.provider.Telephony.ServiceStateTable.CONTENT_URI;

import static android.provider.Telephony.ServiceStateTable.VOICE_REG_STATE;
import static android.provider.Telephony.ServiceStateTable.DATA_REG_STATE;
import static android.provider.Telephony.ServiceStateTable.VOICE_ROAMING_TYPE;
import static android.provider.Telephony.ServiceStateTable.DATA_ROAMING_TYPE;
import static android.provider.Telephony.ServiceStateTable.VOICE_OPERATOR_ALPHA_LONG;
import static android.provider.Telephony.ServiceStateTable.VOICE_OPERATOR_ALPHA_SHORT;
import static android.provider.Telephony.ServiceStateTable.VOICE_OPERATOR_NUMERIC;
import static android.provider.Telephony.ServiceStateTable.DATA_OPERATOR_ALPHA_LONG;
import static android.provider.Telephony.ServiceStateTable.DATA_OPERATOR_ALPHA_SHORT;
import static android.provider.Telephony.ServiceStateTable.DATA_OPERATOR_NUMERIC;
import static android.provider.Telephony.ServiceStateTable.IS_MANUAL_NETWORK_SELECTION;
import static android.provider.Telephony.ServiceStateTable.RIL_VOICE_RADIO_TECHNOLOGY;
import static android.provider.Telephony.ServiceStateTable.RIL_DATA_RADIO_TECHNOLOGY;
import static android.provider.Telephony.ServiceStateTable.CSS_INDICATOR;
import static android.provider.Telephony.ServiceStateTable.NETWORK_ID;
import static android.provider.Telephony.ServiceStateTable.SYSTEM_ID;
import static android.provider.Telephony.ServiceStateTable.CDMA_ROAMING_INDICATOR;
import static android.provider.Telephony.ServiceStateTable.CDMA_DEFAULT_ROAMING_INDICATOR;
import static android.provider.Telephony.ServiceStateTable.CDMA_ERI_ICON_INDEX;
import static android.provider.Telephony.ServiceStateTable.CDMA_ERI_ICON_MODE;
import static android.provider.Telephony.ServiceStateTable.IS_EMERGENCY_ONLY;
import static android.provider.Telephony.ServiceStateTable.IS_DATA_ROAMING_FROM_REGISTRATION;
import static android.provider.Telephony.ServiceStateTable.IS_USING_CARRIER_AGGREGATION;


public class ServiceStateProvider extends ContentProvider {
    private static final String TAG = "ServiceStateProvider";

    public static final String AUTHORITY = ServiceStateTable.AUTHORITY;
    public static final Uri AUTHORITY_URI = Uri.parse("content://" + AUTHORITY);

    private final HashMap<Integer, ServiceState> mServiceStates = new HashMap<>();
    private static final String[] sColumns = {
        VOICE_REG_STATE,
        DATA_REG_STATE,
        VOICE_ROAMING_TYPE,
        DATA_ROAMING_TYPE,
        VOICE_OPERATOR_ALPHA_LONG,
        VOICE_OPERATOR_ALPHA_SHORT,
        VOICE_OPERATOR_NUMERIC,
        DATA_OPERATOR_ALPHA_LONG,
        DATA_OPERATOR_ALPHA_SHORT,
        DATA_OPERATOR_NUMERIC,
        IS_MANUAL_NETWORK_SELECTION,
        RIL_VOICE_RADIO_TECHNOLOGY,
        RIL_DATA_RADIO_TECHNOLOGY,
        CSS_INDICATOR,
        NETWORK_ID,
        SYSTEM_ID,
        CDMA_ROAMING_INDICATOR,
        CDMA_DEFAULT_ROAMING_INDICATOR,
        CDMA_ERI_ICON_INDEX,
        CDMA_ERI_ICON_MODE,
        IS_EMERGENCY_ONLY,
        IS_DATA_ROAMING_FROM_REGISTRATION,
        IS_USING_CARRIER_AGGREGATION,
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    @VisibleForTesting
    public ServiceState getServiceState(int subId) {
        return mServiceStates.get(subId);
    }

    @VisibleForTesting
    public int getDefaultSubId() {
        return SubscriptionController.getInstance().getDefaultSubId();
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        if (uri.isPathPrefixMatch(CONTENT_URI)) {
            // Parse the subId
            int subId = 0;
            try {
                subId = Integer.parseInt(uri.getLastPathSegment());
            } catch (NumberFormatException e) {
                Log.e(TAG, "insert: no subId provided in uri");
                throw e;
            }
            Log.d(TAG, "subId=" + subId);

            // handle DEFAULT_SUBSCRIPTION_ID
            if (subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
                subId = getDefaultSubId();
            }

            // create the new service state
            ServiceState newSS = new ServiceState();
            newSS.setVoiceRegState(values.getAsInteger(VOICE_REG_STATE));
            newSS.setDataRegState(values.getAsInteger(DATA_REG_STATE));
            newSS.setVoiceOperatorName(values.getAsString(VOICE_OPERATOR_ALPHA_LONG),
                        values.getAsString(VOICE_OPERATOR_ALPHA_SHORT),
                        values.getAsString(VOICE_OPERATOR_NUMERIC));
            newSS.setDataOperatorName(values.getAsString(DATA_OPERATOR_ALPHA_LONG),
                    values.getAsString(DATA_OPERATOR_ALPHA_SHORT),
                    values.getAsString(DATA_OPERATOR_NUMERIC));
            newSS.setIsManualSelection(values.getAsBoolean(IS_MANUAL_NETWORK_SELECTION));
            newSS.setRilVoiceRadioTechnology(values.getAsInteger(RIL_VOICE_RADIO_TECHNOLOGY));
            newSS.setRilDataRadioTechnology(values.getAsInteger(RIL_DATA_RADIO_TECHNOLOGY));
            newSS.setCssIndicator(values.getAsInteger(CSS_INDICATOR));
            newSS.setSystemAndNetworkId(values.getAsInteger(SYSTEM_ID),
                    values.getAsInteger(NETWORK_ID));
            newSS.setCdmaRoamingIndicator(values.getAsInteger(CDMA_ROAMING_INDICATOR));
            newSS.setCdmaDefaultRoamingIndicator(
                    values.getAsInteger(CDMA_DEFAULT_ROAMING_INDICATOR));
            newSS.setCdmaEriIconIndex(values.getAsInteger(CDMA_ERI_ICON_INDEX));
            newSS.setCdmaEriIconMode(values.getAsInteger(CDMA_ERI_ICON_MODE));
            newSS.setEmergencyOnly(values.getAsBoolean(IS_EMERGENCY_ONLY));
            newSS.setDataRoamingFromRegistration(
                    values.getAsBoolean(IS_DATA_ROAMING_FROM_REGISTRATION));
            newSS.setIsUsingCarrierAggregation(values.getAsBoolean(IS_USING_CARRIER_AGGREGATION));

            // notify listeners
            // if ss is null (e.g. first service state update) we will notify for all fields
            ServiceState ss = getServiceState(subId);
            notifyChangeForSubIdAndField(getContext(), ss, newSS, subId);
            notifyChangeForSubId(getContext(), ss, newSS, subId);

            // store the new service state
            mServiceStates.put(subId, newSS);
            return uri;
        }
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new RuntimeException("Not supported");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new RuntimeException("Not supported");
    }

    @Override
    public String getType(Uri uri) {
        throw new RuntimeException("Not supported");
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        if (!uri.isPathPrefixMatch(CONTENT_URI)) {
            throw new IllegalArgumentException("Invalid URI: " + uri);
        } else {
            // Parse the subId
            int subId = 0;
            try {
                subId = Integer.parseInt(uri.getLastPathSegment());
            } catch (NumberFormatException e) {
                Log.d(TAG, "query: no subId provided in uri, using default.");
                subId = getDefaultSubId();
            }
            Log.d(TAG, "subId=" + subId);

            // handle DEFAULT_SUBSCRIPTION_ID
            if (subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
                subId = getDefaultSubId();
            }

            // Get the service state
            ServiceState ss = getServiceState(subId);
            if (ss == null) {
                Log.d(TAG, "returning null");
                return null;
            }

            // Build the result
            final int voice_reg_state = ss.getVoiceRegState();
            final int data_reg_state = ss.getDataRegState();
            final int voice_roaming_type = ss.getVoiceRoamingType();
            final int data_roaming_type = ss.getDataRoamingType();
            final String voice_operator_alpha_long = ss.getVoiceOperatorAlphaLong();
            final String voice_operator_alpha_short = ss.getVoiceOperatorAlphaShort();
            final String voice_operator_numeric = ss.getVoiceOperatorNumeric();
            final String data_operator_alpha_long = ss.getDataOperatorAlphaLong();
            final String data_operator_alpha_short = ss.getDataOperatorAlphaShort();
            final String data_operator_numeric = ss.getDataOperatorNumeric();
            final int is_manual_network_selection = (ss.getIsManualSelection()) ? 1 : 0;
            final int ril_voice_radio_technology = ss.getRilVoiceRadioTechnology();
            final int ril_data_radio_technology = ss.getRilDataRadioTechnology();
            final int css_indicator = ss.getCssIndicator();
            final int network_id = ss.getNetworkId();
            final int system_id = ss.getSystemId();
            final int cdma_roaming_indicator = ss.getCdmaRoamingIndicator();
            final int cdma_default_roaming_indicator = ss.getCdmaDefaultRoamingIndicator();
            final int cdma_eri_icon_index = ss.getCdmaEriIconIndex();
            final int cdma_eri_icon_mode = ss.getCdmaEriIconMode();
            final int is_emergency_only = (ss.isEmergencyOnly()) ? 1 : 0;
            final int is_data_roaming_from_registration =
                    (ss.getDataRoamingFromRegistration()) ? 1 : 0;
            final int is_using_carrier_aggregation = (ss.isUsingCarrierAggregation()) ? 1 : 0;

            return buildSingleRowResult(projection, sColumns, new Object[] {
                        voice_reg_state,
                        data_reg_state,
                        voice_roaming_type,
                        data_roaming_type,
                        voice_operator_alpha_long,
                        voice_operator_alpha_short,
                        voice_operator_numeric,
                        data_operator_alpha_long,
                        data_operator_alpha_short,
                        data_operator_numeric,
                        is_manual_network_selection,
                        ril_voice_radio_technology,
                        ril_data_radio_technology,
                        css_indicator,
                        network_id,
                        system_id,
                        cdma_roaming_indicator,
                        cdma_default_roaming_indicator,
                        cdma_eri_icon_index,
                        cdma_eri_icon_mode,
                        is_emergency_only,
                        is_data_roaming_from_registration,
                        is_using_carrier_aggregation,
            });
        }
    }

    private static Cursor buildSingleRowResult(String[] projection, String[] availableColumns,
            Object[] data) {
        if (projection == null) {
            projection = availableColumns;
        }
        final MatrixCursor c = new MatrixCursor(projection, 1);
        final RowBuilder row = c.newRow();
        for (int i = 0; i < c.getColumnCount(); i++) {
            final String columnName = c.getColumnName(i);
            boolean found = false;
            for (int j = 0; j < availableColumns.length; j++) {
                if (availableColumns[j].equals(columnName)) {
                    row.add(data[j]);
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new IllegalArgumentException("Invalid column " + projection[i]);
            }
        }
        return c;
    }

    /**
     * Notify interested apps that certain fields of the ServiceState have changed.
     *
     * Apps which want to wake when specific fields change can use
     * JobScheduler's TriggerContentUri.  This replaces the waking functionality of the implicit
     * broadcast of ACTION_SERVICE_STATE_CHANGED for apps targetting version O.
     *
     * We will only notify for certain fields. This is an intentional change from the behavior of
     * the broadcast. Listeners will be notified when the voice or data registration state or
     * roaming type changes.
     */
    @VisibleForTesting
    public static void notifyChangeForSubIdAndField(Context context, ServiceState oldSS,
            ServiceState newSS, int subId) {
        final boolean firstUpdate = (oldSS == null) ? true : false;

        // for every field, if the field has changed values, notify via the provider
        if (firstUpdate || voiceRegStateChanged(oldSS, newSS)) {
            context.getContentResolver().notifyChange(
                    getUriForSubscriptionIdAndField(subId, VOICE_REG_STATE),
                    /* observer= */ null, /* syncToNetwork= */ false);
        }
        if (firstUpdate || dataRegStateChanged(oldSS, newSS)) {
            context.getContentResolver().notifyChange(
                    getUriForSubscriptionIdAndField(subId, DATA_REG_STATE), null, false);
        }
        if (firstUpdate || voiceRoamingTypeChanged(oldSS, newSS)) {
            context.getContentResolver().notifyChange(
                    getUriForSubscriptionIdAndField(subId, VOICE_ROAMING_TYPE), null, false);
        }
        if (firstUpdate || dataRoamingTypeChanged(oldSS, newSS)) {
            context.getContentResolver().notifyChange(
                    getUriForSubscriptionIdAndField(subId, DATA_ROAMING_TYPE), null, false);
        }
    }

    private static boolean voiceRegStateChanged(ServiceState oldSS, ServiceState newSS) {
        return oldSS.getVoiceRegState() != newSS.getVoiceRegState();
    }

    private static boolean dataRegStateChanged(ServiceState oldSS, ServiceState newSS) {
        return oldSS.getDataRegState() != newSS.getDataRegState();
    }

    private static boolean voiceRoamingTypeChanged(ServiceState oldSS, ServiceState newSS) {
        return oldSS.getVoiceRoamingType() != newSS.getVoiceRoamingType();
    }

    private static boolean dataRoamingTypeChanged(ServiceState oldSS, ServiceState newSS) {
        return oldSS.getDataRoamingType() != newSS.getDataRoamingType();
    }

    /**
     * Notify interested apps that the ServiceState has changed.
     *
     * Apps which want to wake when any field in the ServiceState has changed can use
     * JobScheduler's TriggerContentUri.  This replaces the waking functionality of the implicit
     * broadcast of ACTION_SERVICE_STATE_CHANGED for apps targetting version O.
     *
     * We will only notify for certain fields. This is an intentional change from the behavior of
     * the broadcast. Listeners will be notified when the voice or data registration state or
     * roaming type changes.
     */
    @VisibleForTesting
    public static void notifyChangeForSubId(Context context, ServiceState oldSS, ServiceState newSS,
            int subId) {
        // if the voice or data registration or roaming state field has changed values, notify via
        // the provider.
        // If oldSS is null and newSS is not (e.g. first update of service state) this will also
        // notify
        if (oldSS == null || voiceRegStateChanged(oldSS, newSS) || dataRegStateChanged(oldSS, newSS)
                || voiceRoamingTypeChanged(oldSS, newSS) || dataRoamingTypeChanged(oldSS, newSS)) {
            context.getContentResolver().notifyChange(getUriForSubscriptionId(subId), null, false);
        }
    }
}
