/*
 * SPDX-FileCopyrightText: 2026 Romain Théry
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.bullhead.wfcims;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.telephony.AccessNetworkConstants.AccessNetworkType;
import android.telephony.data.ApnSetting;
import android.telephony.data.QualifiedNetworksService;
import android.util.Log;
import android.util.SparseArray;

import java.util.List;

/**
 * Tells telephony to carry the IMS APN over IWLAN while Wi-Fi calling is on and Wi-Fi is up, and
 * to go back to cellular otherwise. An empty list is how telephony is told to fall back, so VoLTE
 * keeps working whenever this reports nothing.
 */
public class WfcQualifiedNetworks extends QualifiedNetworksService {
    private static final String TAG = "WfcIms";

    private static final SparseArray<Provider> sProviders = new SparseArray<>();
    private static boolean sVoiceWlanEnabled;

    /** Called by the MMTEL feature; the IMS APN only moves to IWLAN while voice over WLAN is on. */
    static synchronized void setVoiceWlanEnabled(boolean enabled) {
        if (sVoiceWlanEnabled == enabled) return;
        sVoiceWlanEnabled = enabled;
        for (int i = 0; i < sProviders.size(); i++) sProviders.valueAt(i).update();
    }

    private static synchronized boolean voiceWlanEnabled() {
        return sVoiceWlanEnabled;
    }

    @Override
    public NetworkAvailabilityProvider onCreateNetworkAvailabilityProvider(int slotIndex) {
        Provider p = new Provider(slotIndex);
        synchronized (WfcQualifiedNetworks.class) {
            sProviders.put(slotIndex, p);
        }
        return p;
    }

    private class Provider extends NetworkAvailabilityProvider {
        private final int mSlot;
        private final ConnectivityManager mCm;
        private final ConnectivityManager.NetworkCallback mCallback;
        private volatile boolean mWifi;
        private boolean mOnIwlan;

        Provider(int slotIndex) {
            super(slotIndex);
            mSlot = slotIndex;
            mCm = getSystemService(ConnectivityManager.class);
            mCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    mWifi = true;
                    update();
                }

                @Override
                public void onLost(Network network) {
                    mWifi = false;
                    update();
                }
            };
            mCm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(), mCallback);
            update();
        }

        synchronized void update() {
            boolean iwlan = mWifi && voiceWlanEnabled();
            if (iwlan == mOnIwlan) return;
            mOnIwlan = iwlan;
            Log.i(TAG, "slot " + mSlot + ": IMS APN on " + (iwlan ? "IWLAN" : "cellular"));
            updateQualifiedNetworkTypes(ApnSetting.TYPE_IMS,
                    iwlan ? List.of(AccessNetworkType.IWLAN) : List.of());
        }

        @Override
        public void close() {
            mCm.unregisterNetworkCallback(mCallback);
            synchronized (WfcQualifiedNetworks.class) {
                sProviders.remove(mSlot);
            }
        }
    }
}
