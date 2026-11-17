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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tells telephony to carry the IMS APN over IWLAN while Wi-Fi calling is on and a usable Wi-Fi
 * network is up, and to go back to cellular otherwise. An empty list is how telephony is told to
 * fall back, so VoLTE keeps working whenever this reports nothing.
 */
public class WfcQualifiedNetworks extends QualifiedNetworksService {
    private static final String TAG = "WfcIms";

    private static final Object sLock = new Object();
    private static final SparseArray<Provider> sProviders = new SparseArray<>();
    /** Volatile and read without a lock: update() must never wait on the class monitor. */
    private static volatile boolean sVoiceWlanEnabled;

    /** Told where the IMS APN goes, on the provider's callback thread. */
    interface TransportListener {
        void onImsApnTransport(int slotIndex, boolean iwlan);
    }

    private static volatile TransportListener sListener;

    /** Also replays the current transport of every slot that has reported one. */
    static void setTransportListener(TransportListener listener) {
        List<Provider> providers;
        synchronized (sLock) {
            sListener = listener;
            providers = new ArrayList<>(sProviders.size());
            for (int i = 0; i < sProviders.size(); i++) providers.add(sProviders.valueAt(i));
        }
        for (Provider p : providers) p.replay(listener);
    }

    /** Only clears it if a replacing feature has not set its own since. */
    static void clearTransportListener(TransportListener listener) {
        synchronized (sLock) {
            if (sListener == listener) sListener = null;
        }
    }

    /** Called by the MMTEL feature; the IMS APN only moves to IWLAN while voice over WLAN is on. */
    static void setVoiceWlanEnabled(boolean enabled) {
        List<Provider> providers;
        synchronized (sLock) {
            if (sVoiceWlanEnabled == enabled) return;
            sVoiceWlanEnabled = enabled;
            providers = new ArrayList<>(sProviders.size());
            for (int i = 0; i < sProviders.size(); i++) providers.add(sProviders.valueAt(i));
        }
        for (Provider p : providers) p.update();
    }

    @Override
    public NetworkAvailabilityProvider onCreateNetworkAvailabilityProvider(int slotIndex) {
        Provider p = new Provider(slotIndex);
        synchronized (sLock) {
            sProviders.put(slotIndex, p);
        }
        // After the provider is reachable, so an enable that raced the constructor is not lost.
        p.update();
        return p;
    }

    private class Provider extends NetworkAvailabilityProvider {
        private final int mSlot;
        private final ConnectivityManager mCm;
        private final ConnectivityManager.NetworkCallback mCallback;
        private final Set<Network> mWifi = ConcurrentHashMap.newKeySet();
        private Boolean mOnIwlan;

        Provider(int slotIndex) {
            super(slotIndex);
            mSlot = slotIndex;
            mCm = getSystemService(ConnectivityManager.class);
            mCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    mWifi.add(network);
                    update();
                }

                @Override
                public void onLost(Network network) {
                    mWifi.remove(network);
                    update();
                }
            };
            mCm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    .build(), mCallback);
        }

        /** Reports the transport for the IMS APN; the first call always reports, even unchanged. */
        void update() {
            boolean iwlan = !mWifi.isEmpty() && sVoiceWlanEnabled;
            synchronized (this) {
                if (mOnIwlan != null && mOnIwlan == iwlan) return;
                mOnIwlan = iwlan;
            }
            Log.i(TAG, "slot " + mSlot + ": IMS APN on " + (iwlan ? "IWLAN" : "cellular"));
            updateQualifiedNetworkTypes(ApnSetting.TYPE_IMS,
                    iwlan ? List.of(AccessNetworkType.IWLAN) : List.of());
            TransportListener listener = sListener;
            if (listener != null) listener.onImsApnTransport(mSlot, iwlan);
        }

        void replay(TransportListener listener) {
            Boolean iwlan;
            synchronized (this) {
                iwlan = mOnIwlan;
            }
            if (iwlan != null) listener.onImsApnTransport(mSlot, iwlan);
        }

        @Override
        public void close() {
            mCm.unregisterNetworkCallback(mCallback);
            synchronized (sLock) {
                if (sProviders.get(mSlot) == this) sProviders.remove(mSlot);
            }
        }
    }
}
