/*
 * SPDX-FileCopyrightText: 2026 Romain Théry
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.bullhead.wfcims;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.telephony.AccessNetworkConstants;
import android.telephony.DataFailCause;
import android.telephony.PreciseDataConnectionState;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;
import android.telephony.ims.ImsMmTelManager;
import android.util.Log;
import android.widget.Toast;

/**
 * Watches Wi-Fi calling on a SIM it has never registered with, and switches it back off with a
 * toast when the carrier refuses it or cannot be reached. Once it has worked with a SIM it is left
 * alone, so a Wi-Fi network that blocks IPsec cannot turn off a setup that works elsewhere.
 */
final class WfcTrial {
    private static final String TAG = "WfcIms";
    private static final String PREFS = "wfc_trial";
    private static final long WINDOW_MS = 90_000;
    private static final int MAX_FAILURES = 3;

    private final Context mContext;
    private final Handler mHandler;
    private final int mSubId;
    private final Runnable mExpire = () -> giveUp(false, "no registration within the window");
    private boolean mArmed;
    private boolean mWifiUp;
    private int mFailures;
    private ConnectivityManager.NetworkCallback mWifiCallback;
    private DataListener mDataListener;

    WfcTrial(Context context, Handler handler, int subId) {
        mContext = context;
        mHandler = handler;
        mSubId = subId;
    }

    private SharedPreferences prefs() {
        // Device-protected: the phone process runs before the first unlock.
        return mContext.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private String key() {
        return "worked_" + mSubId;
    }

    /** Wi-Fi calling was switched on, by the user or because it was on at boot. */
    void start() {
        if (mArmed || prefs().getBoolean(key(), false)) return;
        mArmed = true;
        mFailures = 0;
        Log.i(TAG, "Wi-Fi calling never registered with this SIM, watching the first attempts");
        mWifiCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                if (!mArmed || mWifiUp) return;
                mWifiUp = true;
                mHandler.postDelayed(mExpire, WINDOW_MS);
            }

            @Override
            public void onLost(Network network) {
                mWifiUp = false;
                mHandler.removeCallbacks(mExpire);
            }
        };
        // Only validated Wi-Fi counts: behind a captive portal nothing could have worked.
        mContext.getSystemService(ConnectivityManager.class).registerNetworkCallback(
                new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                        .build(),
                mWifiCallback, mHandler);
        mDataListener = new DataListener();
        try {
            mContext.getSystemService(TelephonyManager.class).createForSubscriptionId(mSubId)
                    .registerTelephonyCallback(mHandler::post, mDataListener);
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot watch the IWLAN data connection: " + e);
            mDataListener = null;
        }
    }

    /** Wi-Fi calling was switched off, or the feature is going away. */
    void stop() {
        if (!mArmed) return;
        mArmed = false;
        mWifiUp = false;
        mHandler.removeCallbacks(mExpire);
        if (mWifiCallback != null) {
            try {
                mContext.getSystemService(ConnectivityManager.class)
                        .unregisterNetworkCallback(mWifiCallback);
            } catch (IllegalArgumentException e) {
                // Already gone.
            }
            mWifiCallback = null;
        }
        if (mDataListener != null) {
            mContext.getSystemService(TelephonyManager.class)
                    .unregisterTelephonyCallback(mDataListener);
            mDataListener = null;
        }
    }

    void onRegistered() {
        if (!prefs().getBoolean(key(), false)) {
            prefs().edit().putBoolean(key(), true).apply();
            Log.i(TAG, "Wi-Fi calling registered with this SIM, no longer watching");
        }
        stop();
    }

    /** A SIP registration failed; refused means the network answered and said no. */
    void onRegistrationFailed(boolean refused) {
        failure(refused, "SIP registration");
    }

    private void failure(boolean refused, String what) {
        if (!mArmed || !mWifiUp) return;
        if (refused) {
            giveUp(true, what + " refused");
        } else if (++mFailures >= MAX_FAILURES) {
            giveUp(false, what + " failed " + mFailures + " times");
        }
    }

    private void giveUp(boolean refused, String why) {
        if (!mArmed) return;
        stop();
        Log.w(TAG, "switching Wi-Fi calling off: " + why);
        String carrier = mContext.getSystemService(TelephonyManager.class)
                .createForSubscriptionId(mSubId).getSimOperatorName();
        if (carrier == null || carrier.isEmpty()) {
            carrier = mContext.getString(R.string.wfc_trial_your_carrier);
        }
        String text = mContext.getString(refused
                ? R.string.wfc_trial_refused : R.string.wfc_trial_unreachable, carrier);
        new Handler(Looper.getMainLooper()).post(
                () -> Toast.makeText(mContext, text, Toast.LENGTH_LONG).show());
        try {
            ImsMmTelManager.createForSubscriptionId(mSubId).setVoWiFiSettingEnabled(false);
        } catch (RuntimeException e) {
            Log.w(TAG, "switching Wi-Fi calling off: " + e);
        }
    }

    /** Causes with which the network or the carrier said no, rather than not answering. */
    private static boolean refused(int cause) {
        switch (cause) {
            case DataFailCause.IWLAN_PDN_CONNECTION_REJECTION:
            case DataFailCause.IWLAN_NON_3GPP_ACCESS_TO_EPC_NOT_ALLOWED:
            case DataFailCause.IWLAN_USER_UNKNOWN:
            case DataFailCause.IWLAN_NO_APN_SUBSCRIPTION:
            case DataFailCause.IWLAN_AUTHORIZATION_REJECTED:
            case DataFailCause.IWLAN_ILLEGAL_ME:
            case DataFailCause.IWLAN_RAT_TYPE_NOT_ALLOWED:
            case DataFailCause.IWLAN_IMEI_NOT_ACCEPTED:
            case DataFailCause.IWLAN_PLMN_NOT_ALLOWED:
            case DataFailCause.IWLAN_IKEV2_CONFIG_FAILURE:
            case DataFailCause.IWLAN_IKEV2_AUTH_FAILURE:
            case DataFailCause.IWLAN_IKEV2_CERT_INVALID:
            // On validated Wi-Fi a missing name means the carrier has no ePDG.
            case DataFailCause.IWLAN_DNS_RESOLUTION_NAME_FAILURE:
                return true;
            default:
                return false;
        }
    }

    private final class DataListener extends TelephonyCallback
            implements TelephonyCallback.PreciseDataConnectionStateListener {
        @Override
        public void onPreciseDataConnectionStateChanged(PreciseDataConnectionState state) {
            if (state.getTransportType() != AccessNetworkConstants.TRANSPORT_TYPE_WLAN) return;
            ApnSetting apn = state.getApnSetting();
            if (apn == null || !apn.canHandleType(ApnSetting.TYPE_IMS)) return;
            if (state.getState() != TelephonyManager.DATA_DISCONNECTED) return;
            int cause = state.getLastCauseCode();
            if (cause == DataFailCause.NONE) return;
            Log.w(TAG, "IWLAN data setup failed: " + DataFailCause.toString(cause));
            failure(refused(cause), "IWLAN " + DataFailCause.toString(cause));
        }
    }
}
