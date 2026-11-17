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
import android.os.Message;
import android.os.Looper;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.ImsCallSessionListener;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.feature.CapabilityChangeRequest;
import android.telephony.ims.feature.MmTelFeature;
import android.telephony.ims.stub.ImsCallSessionImplBase;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.telephony.ims.stub.ImsConfigImplBase;
import android.telephony.ims.stub.ImsSmsImplBase;
import android.telephony.ims.stub.ImsUtImplBase;
import android.util.Log;

import com.android.ims.internal.IImsCallSession;
import com.android.ims.internal.IImsConfig;
import com.android.ims.internal.IImsEcbm;
import com.android.ims.internal.IImsMultiEndpoint;
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
        implements Registrar.Callback, WfcCallSession.Owner, WfcLegacyIms.Callback,
        WfcQualifiedNetworks.TransportListener {
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
    private WfcSms mSms;
    private final WfcTrial mTrial;
    private final int mSlotId;
    /** What the framework last asked for through enable/disableIms, null before it did. */
    private Boolean mModemImsWanted;
    private boolean mImsApnOnIwlan;
    private Boolean mModemImsOn;
    private CallStateListener mCallStateListener;

    WfcMmTelFeature(Context context, Looper looper, int slotId, int subId,
            ImsRegistrationImplBase reg) {
        mContext = context;
        mHandler = new Handler(looper);
        mSubId = subId;
        mSlotId = slotId;
        mRegistration = reg;
        mLegacy = new WfcLegacyIms(context, slotId, this);
        mTrial = new WfcTrial(context, mHandler, subId);
    }

    @Override
    public void onFeatureReady() {
        mLegacy.start();
        mHandler.post(this::watchCallState);
        WfcQualifiedNetworks.setTransportListener(this);
    }

    @Override
    public void onFeatureRemoved() {
        WfcQualifiedNetworks.clearTransportListener(this);
        mLegacy.stop();
        shutdown();
    }

    @Override
    public boolean queryCapabilityConfiguration(int capability, int radioTech) {
        if (radioTech == ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN) {
            return capability == MmTelCapabilities.CAPABILITY_TYPE_VOICE
                    || capability == MmTelCapabilities.CAPABILITY_TYPE_SMS;
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
            mTrial.start();
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
        mTrial.stop();
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
        // The IMS APN can also come up over cellular, and this stack only speaks to the ePDG
        // tunnel the IWLAN data service builds, so wait for an ipsec interface.
        LinkProperties lp = mContext.getSystemService(ConnectivityManager.class)
                .getLinkProperties(network);
        String iface = lp != null ? lp.getInterfaceName() : null;
        boolean tunnel = iface != null && iface.startsWith("ipsec");
        if (network.equals(mImsNetwork)) {
            if (iface != null && !tunnel) dropImsNetwork("IMS network " + network
                    + " moved to " + iface);
            return;
        }
        if (!tunnel) {
            Log.i(TAG, "IMS network " + network + " is on " + iface + ", not the ePDG tunnel");
            // The request moved to a cellular IMS network; no onLost comes for the tunnel.
            if (iface != null && mImsNetwork != null) dropImsNetwork("IMS network "
                    + mImsNetwork + " replaced by " + network);
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
        dropImsNetwork("IMS network " + network + " lost");
    }

    private void dropImsNetwork(String why) {
        Log.i(TAG, why);
        mImsNetwork = null;
        endCalls(why);
        if (mRegistrar != null) {
            mRegistrar.stop();
            mRegistrar = null;
        }
        updateRegistration();
    }

    private boolean wifiRegistered() {
        return mVoiceWlanEnabled && mRegistrar != null && mRegistrar.isRegistered();
    }

    private void updateCapabilities() {
        MmTelCapabilities caps;
        if (wifiRegistered()) {
            caps = new MmTelCapabilities(MmTelCapabilities.CAPABILITY_TYPE_VOICE);
            // Without this the platform never offers us an SMS to carry.
            caps.addCapabilities(MmTelCapabilities.CAPABILITY_TYPE_SMS);
        } else {
            caps = new MmTelCapabilities(mLegacyCaps);
        }
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
    IImsConfig legacyConfig() {
        try {
            return mLegacy.getConfig();
        } catch (RemoteException e) {
            Log.w(TAG, "legacy config: " + e);
            return null;
        }
    }

    void enableModemIms() {
        mHandler.post(() -> {
            mModemImsWanted = true;
            mModemImsOn = null;
            updateModemIms();
        });
    }

    void disableModemIms() {
        mHandler.post(() -> {
            mModemImsWanted = false;
            mModemImsOn = null;
            updateModemIms();
        });
    }

    @Override
    public void onImsApnTransport(int slotIndex, boolean iwlan) {
        if (slotIndex != mSlotId) return;
        mHandler.post(() -> {
            mImsApnOnIwlan = iwlan;
            updateModemIms();
        });
    }

    /**
     * SFR keeps one IMS session per subscriber: while the modem's is up on LTE the ePDG deletes
     * ours, so the modem's IMS is off whenever the IMS APN is on IWLAN.
     */
    private void updateModemIms() {
        boolean on;
        if (mImsApnOnIwlan) {
            on = false;
        } else if (mModemImsWanted != null) {
            on = mModemImsWanted;
        } else if (Boolean.FALSE.equals(mModemImsOn)) {
            on = true;
        } else {
            return;
        }
        if (mModemImsOn != null && mModemImsOn == on) return;
        if (!on && mImsApnOnIwlan && modemCallActive()) {
            Log.i(TAG, "modem IMS stays on until the call ends");
            return;
        }
        mModemImsOn = on;
        Log.i(TAG, "modem IMS turned " + (on ? "on" : "off")
                + (mImsApnOnIwlan ? ", IMS APN on IWLAN" : ""));
        if (on) {
            mLegacy.turnOn();
        } else {
            mLegacy.turnOff();
        }
    }

    private boolean modemCallActive() {
        if (!mCalls.isEmpty()) return false;
        try {
            return mContext.getSystemService(TelephonyManager.class)
                    .createForSubscriptionId(mSubId).getCallStateForSubscription()
                    != TelephonyManager.CALL_STATE_IDLE;
        } catch (RuntimeException e) {
            Log.w(TAG, "call state: " + e);
            return false;
        }
    }

    private void watchCallState() {
        if (mCallStateListener != null) return;
        mCallStateListener = new CallStateListener();
        try {
            mContext.getSystemService(TelephonyManager.class).createForSubscriptionId(mSubId)
                    .registerTelephonyCallback(mHandler::post, mCallStateListener);
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot watch the call state: " + e);
            mCallStateListener = null;
        }
    }

    private void unwatchCallState() {
        if (mCallStateListener == null) return;
        mContext.getSystemService(TelephonyManager.class)
                .unregisterTelephonyCallback(mCallStateListener);
        mCallStateListener = null;
        WfcQualifiedNetworks.setCallActive(false);
    }

    private final class CallStateListener extends TelephonyCallback
            implements TelephonyCallback.CallStateListener {
        @Override
        public void onCallStateChanged(int state) {
            WfcQualifiedNetworks.setCallActive(state != TelephonyManager.CALL_STATE_IDLE);
            if (state == TelephonyManager.CALL_STATE_IDLE) updateModemIms();
        }
    }

    /** Emergency callback mode, TTY and multi-endpoint are the modem's, as they were before. */
    @Override
    protected IImsEcbm getEcbmInterface() throws RemoteException {
        return mLegacy.getEcbm();
    }

    @Override
    public IImsMultiEndpoint getMultiEndpointInterface() throws RemoteException {
        return mLegacy.getMultiEndpoint();
    }

    @Override
    public void setUiTtyMode(int mode, Message onCompleteMessage) {
        mLegacy.setUiTtyMode(mode, onCompleteMessage);
    }

    @Override
    public ImsUtImplBase getUt() {
        try {
            IImsUt legacyUt = mLegacy.getUt();
            if (legacyUt == null) return super.getUt();
            // Wrapped: ims.apk shares one ImsUtImpl that close() breaks for good, and the
            // framework closes its Ut whenever the feature is re-added.
            IImsUt ut = new WfcLegacyUt(legacyUt);
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
        mHandler.post(() -> {
            unwatchCallState();
            releaseImsNetwork();
        });
    }

    @Override
    public void onRegistering() {
        // Never while the modem is carrying us: ImsPhone treats it as out of service.
        if (!mLegacy.isRegistered()) {
            mReportedTech = -1;
            mRegistration.onRegistering(ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN);
        }
    }

    @Override
    public void onRegistered(String identity) {
        mTrial.onRegistered();
        updateRegistration();
    }

    @Override
    public void onRegistrationFailed(boolean refused) {
        mTrial.onRegistrationFailed(refused);
    }

    @Override
    public void onDeregistered(String reason) {
        endCalls("deregistered: " + reason);
        updateRegistration();
    }

    @Override
    public synchronized ImsSmsImplBase getSmsImplementation() {
        if (mSms == null) mSms = new WfcSms(mContext, mSubId, mHandler, () -> mRegistrar);
        return mSms;
    }

    @Override
    public boolean onRequest(SecAgreeChannel channel, SipMessage msg, SecAgreeChannel.Source from) {
        if ("MESSAGE".equalsIgnoreCase(msg.method())) {
            WfcSms sms;
            synchronized (this) {
                sms = mSms;
            }
            if (sms != null && sms.onMessage(channel, msg, from)) return true;
        }
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
        if (call != null) {
            call.onResponse(msg);
            return;
        }
        WfcSms sms;
        synchronized (this) {
            sms = mSms;
        }
        if (sms != null) sms.onResponse(msg);
    }

    @Override
    public int shouldProcessCall(String[] numbers) {
        TelephonyManager tm = mContext.getSystemService(TelephonyManager.class);
        boolean emergency = false;
        for (String n : numbers) {
            try {
                emergency |= tm.isEmergencyNumber(n);
            } catch (RuntimeException e) {
                // Cannot tell, so take the path that always works.
                return PROCESS_CALL_CSFB;
            }
        }
        if (emergency) {
            // Only the modem can carry these: it registers for emergency service, gives the
            // network a location and handles callback mode afterwards. Our Wi-Fi stack does
            // none of that, so while Wi-Fi is carrying us an emergency call goes to the
            // circuit-switched network instead. Telephony retries there by itself if the
            // modem's attempt fails.
            return !wifiRegistered() && mLegacy.isRegistered()
                    ? PROCESS_CALL_IMS : PROCESS_CALL_CSFB;
        }
        return wifiRegistered() || mLegacy.isRegistered() ? PROCESS_CALL_IMS : PROCESS_CALL_CSFB;
    }

    @Override
    public ImsCallProfile createCallProfile(int callSessionType, int callType) {
        if (!wifiRegistered() && mLegacy.isRegistered()) {
            ImsCallProfile profile = mLegacy.createCallProfile(callSessionType, callType);
            if (profile != null) return profile;
        }
        return new ImsCallProfile(callSessionType, callType);
    }

    /**
     * Cellular calls are the modem's own sessions, passed through as they are. Overridden here
     * rather than in createCallSession so the legacy binder is returned untouched.
     */
    @Override
    public IImsCallSession createCallSessionInterface(ImsCallProfile profile)
            throws RemoteException {
        if (!wifiRegistered() && mLegacy.isRegistered()) {
            Log.i(TAG, "placing the call through the modem");
            return mLegacy.createCallSession(profile);
        }
        return super.createCallSessionInterface(profile);
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
