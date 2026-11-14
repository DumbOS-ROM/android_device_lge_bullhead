/*
 * SPDX-FileCopyrightText: 2026 Romain Théry
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.bullhead.wfcims;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.TelephonyNetworkSpecifier;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.ImsCallSessionListener;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.feature.CapabilityChangeRequest;
import android.telephony.ims.feature.MmTelFeature;
import android.telephony.ims.stub.ImsCallSessionImplBase;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.telephony.ims.stub.ImsConfigImplBase;
import android.telephony.ims.stub.ImsUtImplBase;
import android.util.Log;

import com.android.ims.internal.IImsCallSession;
import com.android.ims.internal.IImsConfig;
import com.android.ims.internal.IImsUt;

import android.os.RemoteException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * MMTEL for both transports: our own SIP stack over Wi-Fi, and the modem's IMS over cellular
 * through {@link WfcLegacyIms}. Wi-Fi wins whenever it is registered. One Wi-Fi call at a time;
 * cellular calls are the modem's own sessions and are passed straight through.
 */
final class WfcMmTelFeature extends MmTelFeature
        implements Registrar.Callback, WfcCallSession.Owner, WfcLegacyIms.Callback {
    private static final String TAG = "WfcIms";

    private final Context mContext;
    private final Handler mHandler;
    private final int mSubId;
    private final ImsRegistrationImplBase mRegistration;
    private volatile Registrar mRegistrar;
    private ConnectivityManager.NetworkCallback mNetworkCallback;
    private Network mImsNetwork;
    private boolean mVoiceWlanEnabled;
    private final Map<String, WfcCallSession> mCalls = new HashMap<>();
    private final WfcLegacyIms mLegacy;
    private MmTelCapabilities mLegacyCaps = new MmTelCapabilities();
    private int mReportedTech = -1;

    WfcMmTelFeature(Context context, Looper looper, int slotId, int subId,
            ImsRegistrationImplBase reg) {
        mContext = context;
        mHandler = new Handler(looper);
        mSubId = subId;
        mRegistration = reg;
        mLegacy = new WfcLegacyIms(context, slotId, this);
    }

    @Override
    public void onFeatureReady() {
        mLegacy.start();
    }

    @Override
    public void onFeatureRemoved() {
        mLegacy.stop();
        shutdown();
    }

    @Override
    public boolean queryCapabilityConfiguration(int capability, int radioTech) {
        if (radioTech == ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN) {
            return capability == MmTelCapabilities.CAPABILITY_TYPE_VOICE;
        }
        return mLegacyCaps.isCapable(capability);
    }

    @Override
    public void changeEnabledCapabilities(CapabilityChangeRequest request,
            CapabilityCallbackProxy callback) {
        boolean enabled = mVoiceWlanEnabled;
        for (CapabilityChangeRequest.CapabilityPair p : request.getCapabilitiesToEnable()) {
            if (isVoiceWlan(p)) enabled = true;
        }
        for (CapabilityChangeRequest.CapabilityPair p : request.getCapabilitiesToDisable()) {
            if (isVoiceWlan(p)) enabled = false;
        }
        final boolean want = enabled;
        mHandler.post(() -> setVoiceWlanEnabled(want));
        // Anything that is not voice over Wi-Fi belongs to the modem's provisioning.
        for (CapabilityChangeRequest.CapabilityPair p : request.getCapabilitiesToEnable()) {
            if (!isVoiceWlan(p)) mLegacy.setFeature(p.getCapability(), p.getRadioTech(), true);
        }
        for (CapabilityChangeRequest.CapabilityPair p : request.getCapabilitiesToDisable()) {
            if (!isVoiceWlan(p)) mLegacy.setFeature(p.getCapability(), p.getRadioTech(), false);
        }
    }

    private static boolean isVoiceWlan(CapabilityChangeRequest.CapabilityPair p) {
        return p.getCapability() == MmTelCapabilities.CAPABILITY_TYPE_VOICE
                && p.getRadioTech() == ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN;
    }

    private void setVoiceWlanEnabled(boolean enabled) {
        if (enabled == mVoiceWlanEnabled) return;
        mVoiceWlanEnabled = enabled;
        Log.i(TAG, "voice over WLAN " + (enabled ? "enabled" : "disabled"));
        // Lets the qualified networks service move the IMS APN to IWLAN, and only then.
        WfcQualifiedNetworks.setVoiceWlanEnabled(enabled);
        if (enabled) {
            requestImsNetwork();
        } else {
            releaseImsNetwork();
        }
        updateCapabilities();
    }

    private void requestImsNetwork() {
        if (mNetworkCallback != null) return;
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_IMS)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .setNetworkSpecifier(new TelephonyNetworkSpecifier.Builder()
                        .setSubscriptionId(mSubId).build())
                .build();
        mNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                mHandler.post(() -> onImsNetwork(network));
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
                // The interface is often not known yet at onAvailable.
                mHandler.post(() -> onImsNetwork(network));
            }

            @Override
            public void onLost(Network network) {
                mHandler.post(() -> onImsNetworkLost(network));
            }
        };
        mContext.getSystemService(ConnectivityManager.class)
                .requestNetwork(request, mNetworkCallback, mHandler);
        Log.i(TAG, "IMS network requested for sub " + mSubId);
    }

    private void releaseImsNetwork() {
        endCalls("IMS network released");
        WfcQualifiedNetworks.setVoiceWlanEnabled(false);
        if (mRegistrar != null) {
            mRegistrar.stop();
            mRegistrar = null;
        }
        if (mNetworkCallback != null) {
            mContext.getSystemService(ConnectivityManager.class)
                    .unregisterNetworkCallback(mNetworkCallback);
            mNetworkCallback = null;
        }
        mImsNetwork = null;
    }

    private void onImsNetwork(Network network) {
        if (network.equals(mImsNetwork)) return;
        // The IMS APN can also come up over cellular, and this stack only speaks to the ePDG
        // tunnel the IWLAN data service builds, so wait for an ipsec interface.
        LinkProperties lp = mContext.getSystemService(ConnectivityManager.class)
                .getLinkProperties(network);
        String iface = lp != null ? lp.getInterfaceName() : null;
        if (iface == null || !iface.startsWith("ipsec")) {
            Log.i(TAG, "IMS network " + network + " is on " + iface + ", not the ePDG tunnel");
            return;
        }
        Log.i(TAG, "IMS network " + network + " available on " + iface);
        if (mRegistrar != null) mRegistrar.stop();
        mImsNetwork = network;
        mRegistrar = new Registrar(mContext, new Handler(mHandler.getLooper()),
                new ImsIdentity(mContext, mSubId), this);
        mRegistrar.start(network);
    }

    private void onImsNetworkLost(Network network) {
        if (!network.equals(mImsNetwork)) return;
        Log.i(TAG, "IMS network " + network + " lost");
        mImsNetwork = null;
        endCalls("IMS network lost");
        if (mRegistrar != null) {
            mRegistrar.stop();
            mRegistrar = null;
        }
    }

    private boolean wifiRegistered() {
        return mVoiceWlanEnabled && mRegistrar != null && mRegistrar.isRegistered();
    }

    private void updateCapabilities() {
        MmTelCapabilities caps = wifiRegistered()
                ? new MmTelCapabilities(MmTelCapabilities.CAPABILITY_TYPE_VOICE)
                : new MmTelCapabilities(mLegacyCaps);
        notifyCapabilitiesStatusChanged(caps);
    }

    /** One registration is reported to the framework; Wi-Fi wins while it is up. */
    private void updateRegistration() {
        int tech = wifiRegistered() ? ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN
                : mLegacy.isRegistered() ? WfcLegacyIms.registrationTech() : -1;
        if (tech == mReportedTech) return;
        mReportedTech = tech;
        if (tech == -1) {
            mRegistration.onDeregistered(new ImsReasonInfo(
                    ImsReasonInfo.CODE_REGISTRATION_ERROR, 0, "no transport registered"));
        } else {
            Log.i(TAG, "registered over " + (tech
                    == ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN ? "Wi-Fi" : "cellular"));
            mRegistration.onRegistered(tech);
        }
        updateCapabilities();
    }

    @Override
    public void onLegacyRegistered() {
        mHandler.post(this::updateRegistration);
    }

    @Override
    public void onLegacyDeregistered(ImsReasonInfo reason) {
        mHandler.post(this::updateRegistration);
    }

    @Override
    public void onLegacyCapabilities(MmTelCapabilities capabilities) {
        mHandler.post(() -> {
            mLegacyCaps = capabilities;
            updateCapabilities();
        });
    }

    @Override
    public void onLegacyIncomingCall(IImsCallSession session, Bundle extras) {
        try {
            notifyIncomingCallSession(session, extras);
        } catch (RuntimeException e) {
            Log.w(TAG, "reporting the modem's incoming call: " + e);
        }
    }

    /** The modem's provisioning, or null before the legacy service is up. */
    ImsConfigImplBase legacyConfig() {
        try {
            IImsConfig config = mLegacy.getConfig();
            return config == null ? null : new WfcLegacyConfig(config);
        } catch (RemoteException e) {
            Log.w(TAG, "legacy config: " + e);
            return null;
        }
    }

    @Override
    public ImsUtImplBase getUt() {
        try {
            IImsUt ut = mLegacy.getUt();
            if (ut == null) return super.getUt();
            return new ImsUtImplBase() {
                @Override
                public IImsUt getInterface() {
                    return ut;
                }
            };
        } catch (RemoteException e) {
            Log.w(TAG, "legacy Ut: " + e);
            return super.getUt();
        }
    }

    void shutdown() {
        mHandler.post(this::releaseImsNetwork);
    }

    @Override
    public void onRegistering() {
        mRegistration.onRegistering(ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN);
    }

    @Override
    public void onRegistered(String identity) {
        Log.i(TAG, "registered over IWLAN");
        mRegistration.onRegistered(ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN);
        updateCapabilities();
    }

    @Override
    public void onDeregistered(String reason) {
        endCalls("deregistered: " + reason);
        updateRegistration();
    }

    @Override
    public boolean onRequest(SecAgreeChannel channel, SipMessage msg, SecAgreeChannel.Source from) {
        WfcCallSession call = mCalls.get(msg.header("Call-ID"));
        if (call != null) {
            call.onRequest(channel, msg, from);
            return true;
        }
        if ("INVITE".equalsIgnoreCase(msg.method())) {
            boolean busy = false;
            for (WfcCallSession c : mCalls.values()) busy |= !c.isEnded();
            if (!mVoiceWlanEnabled || busy) {
                Log.i(TAG, "incoming call declined: " + (!busy
                        ? "voice over WLAN off" : "already in a call"));
                mRegistrar.answer(channel, msg, from, 486, "Busy Here");
                return true;
            }
            WfcCallSession incoming =
                    new WfcCallSession(mContext, mHandler, mRegistrar, msg, this);
            mCalls.put(incoming.getCallId(), incoming);
            if (!incoming.onInitialInvite(channel, msg, from)) onSessionEnded(incoming);
            return true;
        }
        return false;
    }

    @Override
    public ImsCallSessionListener notifyIncoming(WfcCallSession session, Bundle extras) {
        try {
            return notifyIncomingCall(session, session.getCallId(), extras);
        } catch (RuntimeException e) {
            Log.w(TAG, "notifyIncomingCall: " + e);
            return null;
        }
    }

    @Override
    public void onResponse(SipMessage msg) {
        WfcCallSession call = mCalls.get(msg.header("Call-ID"));
        if (call != null) call.onResponse(msg);
    }

    @Override
    public int shouldProcessCall(String[] numbers) {
        Registrar reg = mRegistrar;
        if (reg == null || !reg.isRegistered()) return PROCESS_CALL_CSFB;
        TelephonyManager tm = mContext.getSystemService(TelephonyManager.class);
        for (String n : numbers) {
            try {
                if (tm.isEmergencyNumber(n)) return PROCESS_CALL_CSFB;
            } catch (RuntimeException e) {
                return PROCESS_CALL_CSFB;
            }
        }
        return PROCESS_CALL_IMS;
    }

    @Override
    public ImsCallProfile createCallProfile(int callSessionType, int callType) {
        return new ImsCallProfile(callSessionType, callType);
    }

    @Override
    public ImsCallSessionImplBase createCallSession(ImsCallProfile profile) {
        Registrar reg = mRegistrar;
        if (reg == null) return null;
        WfcCallSession call = new WfcCallSession(mContext, mHandler, reg, profile, this);
        mHandler.post(() -> mCalls.put(call.getCallId(), call));
        return call;
    }

    @Override
    public void useAndroidAudio() {
        try {
            setCallAudioHandler(AUDIO_HANDLER_ANDROID);
        } catch (RuntimeException e) {
            Log.w(TAG, "setCallAudioHandler: " + e);
        }
    }

    @Override
    public void onSessionEnded(WfcCallSession session) {
        // Kept for 32 s (SIP timer H/J) to absorb retransmitted requests.
        mHandler.postDelayed(() -> {
            if (mCalls.get(session.getCallId()) == session) mCalls.remove(session.getCallId());
        }, 32000);
    }

    private void endCalls(String why) {
        for (WfcCallSession call : new ArrayList<>(mCalls.values())) {
            if (!call.isEnded()) call.abort(why);
        }
    }
}
