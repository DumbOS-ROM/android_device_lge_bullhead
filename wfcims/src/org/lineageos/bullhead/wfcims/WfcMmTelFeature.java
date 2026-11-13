/*
 * SPDX-FileCopyrightText: 2026 Romain Théry
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.bullhead.wfcims;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.TelephonyNetworkSpecifier;
import android.os.Handler;
import android.os.Looper;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.feature.CapabilityChangeRequest;
import android.telephony.ims.feature.MmTelFeature;
import android.telephony.ims.stub.ImsCallSessionImplBase;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.util.Log;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** MMTEL voice over IWLAN, registered by {@link Registrar}. Outgoing calls only. */
final class WfcMmTelFeature extends MmTelFeature
        implements Registrar.Callback, WfcCallSession.Owner {
    private static final String TAG = "WfcIms";

    private final Context mContext;
    private final Handler mHandler;
    private final int mSubId;
    private final ImsRegistrationImplBase mRegistration;
    private Registrar mRegistrar;
    private ConnectivityManager.NetworkCallback mNetworkCallback;
    private Network mImsNetwork;
    private boolean mVoiceWlanEnabled;
    private final Map<String, WfcCallSession> mCalls = new HashMap<>();

    WfcMmTelFeature(Context context, Looper looper, int subId, ImsRegistrationImplBase reg) {
        mContext = context;
        mHandler = new Handler(looper);
        mSubId = subId;
        mRegistration = reg;
    }

    @Override
    public void onFeatureReady() {
    }

    @Override
    public void onFeatureRemoved() {
        shutdown();
    }

    @Override
    public boolean queryCapabilityConfiguration(int capability, int radioTech) {
        return capability == MmTelCapabilities.CAPABILITY_TYPE_VOICE
                && radioTech == ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN;
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
    }

    private static boolean isVoiceWlan(CapabilityChangeRequest.CapabilityPair p) {
        return p.getCapability() == MmTelCapabilities.CAPABILITY_TYPE_VOICE
                && p.getRadioTech() == ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN;
    }

    private void setVoiceWlanEnabled(boolean enabled) {
        if (enabled == mVoiceWlanEnabled) return;
        mVoiceWlanEnabled = enabled;
        Log.i(TAG, "voice over WLAN " + (enabled ? "enabled" : "disabled"));
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
        Log.i(TAG, "IMS network " + network + " available");
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

    private void updateCapabilities() {
        boolean voice = mVoiceWlanEnabled && mRegistrar != null && mRegistrar.isRegistered();
        notifyCapabilitiesStatusChanged(voice
                ? new MmTelCapabilities(MmTelCapabilities.CAPABILITY_TYPE_VOICE)
                : new MmTelCapabilities());
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
        mRegistration.onDeregistered(new ImsReasonInfo(
                ImsReasonInfo.CODE_REGISTRATION_ERROR, 0, reason));
        updateCapabilities();
    }

    @Override
    public boolean onRequest(SecAgreeChannel channel, SipMessage msg, InetSocketAddress from) {
        WfcCallSession call = mCalls.get(msg.header("Call-ID"));
        if (call != null) {
            call.onRequest(channel, msg, from);
            return true;
        }
        if ("INVITE".equalsIgnoreCase(msg.method())) {
            Log.i(TAG, "incoming call declined: incoming calls are not implemented yet");
            mRegistrar.answer(channel, msg, from, 486, "Busy Here");
            return true;
        }
        return false;
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
        mCalls.remove(session.getCallId());
    }

    private void endCalls(String why) {
        for (WfcCallSession call : new ArrayList<>(mCalls.values())) {
            call.finish(new ImsReasonInfo(ImsReasonInfo.CODE_LOCAL_CALL_TERMINATED, 0, why),
                    call.getState() != ImsCallSessionImplBase.State.ESTABLISHED);
        }
        mCalls.clear();
    }
}
