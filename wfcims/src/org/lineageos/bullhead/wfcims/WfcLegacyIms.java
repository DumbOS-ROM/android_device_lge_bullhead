/*
 * SPDX-FileCopyrightText: 2026 Romain Théry
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.bullhead.wfcims;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.feature.MmTelFeature.MmTelCapabilities;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.util.Log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.android.ims.internal.IImsCallSession;
import com.android.ims.internal.IImsConfig;
import com.android.ims.internal.IImsRegistrationListener;
import com.android.ims.internal.IImsService;
import com.android.ims.internal.IImsUt;

/**
 * The cellular half of the MMTEL feature: the modem's own IMS, reached through the legacy
 * {@code ims} service that the vendor ims.apk publishes. This is what the separate compat
 * bridge used to do, moved in here so one service can serve both transports.
 *
 * <p>The modem cannot do VoWiFi at all, so anything it registers is treated as LTE.
 */
final class WfcLegacyIms {
    private static final String TAG = "WfcIms";
    private static final String LEGACY_SERVICE = "ims";
    private static final String ACTION_IMS_INCOMING_CALL = "com.android.ims.IMS_INCOMING_CALL";
    /** com.android.ims.ImsServiceClass.MMTEL */
    private static final int SERVICE_CLASS_MMTEL = 1;

    private static final int FEATURE_TYPE_VOICE_OVER_LTE = 0;
    private static final int FEATURE_TYPE_VIDEO_OVER_LTE = 1;
    private static final int FEATURE_TYPE_VOICE_OVER_WIFI = 2;
    private static final int FEATURE_TYPE_VIDEO_OVER_WIFI = 3;
    private static final int FEATURE_TYPE_UT_OVER_LTE = 4;
    private static final int FEATURE_TYPE_UT_OVER_WIFI = 5;

    interface Callback {
        void onLegacyRegistered();

        void onLegacyDeregistered(ImsReasonInfo reason);

        void onLegacyCapabilities(MmTelCapabilities capabilities);

        void onLegacyIncomingCall(IImsCallSession session, Bundle extras);
    }

    private final Context mContext;
    private final int mSlotId;
    private final Callback mCallback;
    private volatile IImsService mLegacy;
    private volatile boolean mRegistered;
    private volatile int mServiceId = -1;
    private volatile boolean mStarted;
    /** LTE capability requests, replayed whenever the vendor service comes back. */
    private final Map<Integer, Boolean> mWantedFeatures = new ConcurrentHashMap<>();

    private final IBinder.DeathRecipient mDeath = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            Log.w(TAG, "the legacy ims service died");
            mLegacy = null;
            mServiceId = -1;
            setRegistered(false, new ImsReasonInfo(ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN, 0,
                    "legacy ims service died"));
            if (mStarted) waitAndAttach();
        }
    };

    private final BroadcastReceiver mIncoming = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_IMS_INCOMING_CALL.equals(intent.getAction())) return;
            String callId = intent.getStringExtra("android:imsCallID");
            try {
                IImsCallSession session = service().getPendingCallSession(mServiceId, callId);
                if (session == null) {
                    Log.w(TAG, "no pending legacy call session for " + callId);
                    return;
                }
                Log.i(TAG, "incoming call from the modem");
                mCallback.onLegacyIncomingCall(session, intent.getExtras());
            } catch (RemoteException e) {
                Log.w(TAG, "pending legacy call session: " + e);
            }
        }
    };

    /** Translates the modem's registration events; everything it does is over LTE. */
    private final IImsRegistrationListener mListener = new IImsRegistrationListener.Stub() {
        @Override
        public void registrationConnected() {
            setRegistered(true, null);
        }

        private boolean gone() {
            // The legacy interface cannot remove a listener, so a stopped feature must be quiet.
            return !mStarted;
        }

        @Override
        public void registrationProgressing() {
        }

        @Override
        public void registrationConnectedWithRadioTech(int imsRadioTech) {
            setRegistered(true, null);
        }

        @Override
        public void registrationProgressingWithRadioTech(int imsRadioTech) {
        }

        @Override
        public void registrationDisconnected(ImsReasonInfo reason) {
            setRegistered(false, reason);
        }

        @Override
        public void registrationResumed() {
        }

        @Override
        public void registrationSuspended() {
        }

        @Override
        public void registrationServiceCapabilityChanged(int serviceClass, int event) {
        }

        @Override
        public void registrationFeatureCapabilityChanged(int serviceClass, int[] enabledFeatures,
                int[] disabledFeatures) {
            if (gone()) return;
            mCallback.onLegacyCapabilities(convertCapabilities(enabledFeatures));
        }

        @Override
        public void voiceMessageCountUpdate(int count) {
        }

        @Override
        public void registrationAssociatedUriChanged(Uri[] uris) {
        }

        @Override
        public void registrationChangeFailed(int targetAccessTech, ImsReasonInfo reason) {
        }
    };

    WfcLegacyIms(Context context, int slotId, Callback callback) {
        mContext = context;
        mSlotId = slotId;
        mCallback = callback;
    }

    boolean isRegistered() {
        return mRegistered;
    }

    boolean isConnected() {
        return mLegacy != null && mServiceId != -1;
    }

    void start() {
        if (mStarted) return;
        mStarted = true;
        // The modem fires our own PendingIntent, so the broadcast carries this uid and needs
        // no export; anyone else must not be able to fake an incoming call.
        mContext.registerReceiver(mIncoming, new IntentFilter(ACTION_IMS_INCOMING_CALL),
                Context.RECEIVER_NOT_EXPORTED);
        connect();
    }

    synchronized void stop() {
        if (!mStarted) return;
        mStarted = false;
        try {
            mContext.unregisterReceiver(mIncoming);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "receiver was not registered: " + e);
        }
        IImsService service = mLegacy;
        if (service != null && mServiceId != -1) {
            try {
                service.close(mServiceId);
            } catch (RemoteException e) {
                Log.w(TAG, "closing the legacy session: " + e);
            }
        }
        mServiceId = -1;
        mLegacy = null;
        // The feature is going away; nothing to tell it, and its registration object is shared
        // with whatever feature replaces it.
        mRegistered = false;
    }

    // ims.apk publishes the service from its boot receiver, possibly after we are bound.
    private void connect() {
        IBinder binder = ServiceManager.checkService(LEGACY_SERVICE);
        if (binder != null) {
            attach(binder);
            return;
        }
        waitAndAttach();
    }

    /** Waits off the caller's thread, and keeps waiting if the binder dies as we attach. */
    private void waitAndAttach() {
        new Thread(() -> {
            while (mStarted && mLegacy == null) {
                IBinder waited = ServiceManager.waitForService(LEGACY_SERVICE);
                if (waited == null) {
                    Log.e(TAG, "the legacy ims service never appeared");
                    return;
                }
                attach(waited);
                if (mStarted && mLegacy == null) {
                    // waitForService hands back the same dead binder; do not spin on it.
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "WfcImsLegacy").start();
    }

    private synchronized void attach(IBinder binder) {
        if (!mStarted) return;
        try {
            binder.linkToDeath(mDeath, 0);
        } catch (RemoteException e) {
            Log.w(TAG, "the legacy ims service died while attaching");
            return;
        }
        IImsService service = IImsService.Stub.asInterface(binder);
        mLegacy = service;
        try {
            Intent intent = new Intent(ACTION_IMS_INCOMING_CALL).setPackage(
                    mContext.getPackageName());
            PendingIntent pending = PendingIntent.getBroadcast(mContext, 0, intent,
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            mServiceId = service.open(mSlotId, SERVICE_CLASS_MMTEL, pending, mListener);
            service.addRegistrationListener(mSlotId, SERVICE_CLASS_MMTEL, mListener);
            Log.i(TAG, "modem IMS session " + mServiceId + " open for slot " + mSlotId);
            for (Map.Entry<Integer, Boolean> e : mWantedFeatures.entrySet()) {
                pushFeature(e.getKey(), e.getValue());
            }
        } catch (RemoteException e) {
            Log.e(TAG, "opening the legacy session: " + e);
            mLegacy = null;
        }
    }

    private void setRegistered(boolean registered, ImsReasonInfo reason) {
        // A stopped feature must stay quiet: the legacy interface cannot remove our listener and
        // the registration object is shared with the feature that replaces us.
        if (!mStarted) return;
        if (mRegistered == registered) return;
        mRegistered = registered;
        Log.i(TAG, "modem IMS " + (registered ? "registered" : "deregistered"));
        if (registered) {
            mCallback.onLegacyRegistered();
        } else {
            mCallback.onLegacyCapabilities(new MmTelCapabilities());
            mCallback.onLegacyDeregistered(reason != null ? reason
                    : new ImsReasonInfo(ImsReasonInfo.CODE_REGISTRATION_ERROR, 0, "deregistered"));
        }
    }

    private IImsService service() throws RemoteException {
        IImsService service = mLegacy;
        if (service == null) throw new RemoteException("the legacy ims service is not connected");
        return service;
    }

    ImsCallProfile createCallProfile(int callSessionType, int callType) {
        try {
            return service().createCallProfile(mServiceId, callSessionType, callType);
        } catch (RemoteException e) {
            Log.w(TAG, "legacy createCallProfile: " + e);
            return null;
        }
    }

    IImsCallSession createCallSession(ImsCallProfile profile) throws RemoteException {
        return service().createCallSession(mServiceId, profile, null);
    }

    IImsUt getUt() throws RemoteException {
        return service().getUtInterface(mServiceId);
    }

    IImsConfig getConfig() throws RemoteException {
        return service().getConfigInterface(mSlotId);
    }

    /** Pushes a capability the framework asked for into the modem's provisioning. */
    void setFeature(int capability, int radioTech, boolean enabled) {
        int feature = radioTech == ImsRegistrationImplBase.REGISTRATION_TECH_LTE
                ? (capability == MmTelCapabilities.CAPABILITY_TYPE_VOICE
                        ? FEATURE_TYPE_VOICE_OVER_LTE
                        : capability == MmTelCapabilities.CAPABILITY_TYPE_VIDEO
                                ? FEATURE_TYPE_VIDEO_OVER_LTE : -1)
                : -1;
        if (feature < 0) return;
        mWantedFeatures.put(feature, enabled);
        pushFeature(feature, enabled);
    }

    private void pushFeature(int feature, boolean enabled) {
        try {
            IImsConfig config = getConfig();
            if (config == null) return;
            config.setFeatureValue(feature, TelephonyManager.NETWORK_TYPE_LTE,
                    enabled ? 1 : 0, null);
            Log.i(TAG, "modem feature " + feature + (enabled ? " enabled" : " disabled"));
        } catch (RemoteException e) {
            // Kept in mWantedFeatures and replayed once the vendor service is back.
            Log.w(TAG, "setFeatureValue: " + e);
        }
    }

    void turnOn() {
        try {
            service().turnOnIms(mSlotId);
        } catch (RemoteException e) {
            Log.w(TAG, "turnOnIms: " + e);
        }
    }

    void turnOff() {
        try {
            service().turnOffIms(mSlotId);
        } catch (RemoteException e) {
            Log.w(TAG, "turnOffIms: " + e);
        }
    }

    /** The modem reports its features as an array indexed by feature type. */
    private static MmTelCapabilities convertCapabilities(int[] enabled) {
        boolean[] on = new boolean[FEATURE_TYPE_UT_OVER_WIFI + 1];
        for (int i = FEATURE_TYPE_VOICE_OVER_LTE; i < on.length && i < enabled.length; i++) {
            on[i] = enabled[i] == i;
        }
        MmTelCapabilities caps = new MmTelCapabilities();
        if (on[FEATURE_TYPE_VOICE_OVER_LTE] || on[FEATURE_TYPE_VOICE_OVER_WIFI]) {
            caps.addCapabilities(MmTelCapabilities.CAPABILITY_TYPE_VOICE);
        }
        if (on[FEATURE_TYPE_VIDEO_OVER_LTE] || on[FEATURE_TYPE_VIDEO_OVER_WIFI]) {
            caps.addCapabilities(MmTelCapabilities.CAPABILITY_TYPE_VIDEO);
        }
        if (on[FEATURE_TYPE_UT_OVER_LTE] || on[FEATURE_TYPE_UT_OVER_WIFI]) {
            caps.addCapabilities(MmTelCapabilities.CAPABILITY_TYPE_UT);
        }
        return caps;
    }

    static int registrationTech() {
        return ImsRegistrationImplBase.REGISTRATION_TECH_LTE;
    }
}
