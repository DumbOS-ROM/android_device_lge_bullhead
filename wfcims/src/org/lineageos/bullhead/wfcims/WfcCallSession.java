/*
 * SPDX-FileCopyrightText: 2026 Romain Théry
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.bullhead.wfcims;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.ImsCallSessionListener;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsStreamMediaProfile;
import android.telephony.ims.stub.ImsCallSessionImplBase;
import android.util.Log;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One voice call over the registered IMS channel. All SIP work runs on the handler. */
final class WfcCallSession extends ImsCallSessionImplBase {
    private static final String TAG = "WfcIms";
    private static final long[] INVITE_RETRANSMIT_MS = {500, 1000, 2000, 4000, 4000, 4000, 4000};
    private static final long BYE_TIMEOUT_MS = 4000;
    private static final long CANCEL_TIMEOUT_MS = 32000;
    /** Timer E/G style retransmissions, about 32 s in total (timers F and H). */
    private static final long[] RETRANSMIT_MS = {500, 1000, 2000, 4000, 4000, 4000, 4000,
            4000, 4000, 4000};

    interface Owner {
        /** Telecom should route this call's audio like a VoIP call (ImsMedia, not the modem). */
        void useAndroidAudio();

        void onSessionEnded(WfcCallSession session);

        /** Reports an incoming call to the framework; returns its session listener. */
        ImsCallSessionListener notifyIncoming(WfcCallSession session, Bundle extras);
    }

    private final Context mContext;
    private final Handler mHandler;
    /** Retransmissions; unlike mHandler it is not cleared when the call ends. */
    private final Handler mTx;
    private final Map<String, Runnable> mPending = new HashMap<>();
    private final Registrar mReg;
    private final Owner mOwner;
    private final ImsCallProfile mProfile;
    private final String mCallId;
    private final String mTag = Registrar.hex(4);
    private final Set<String> mPracked = new HashSet<>();
    private volatile int mState = State.IDLE;
    private volatile ImsCallSessionListener mListener;
    private WfcMedia mMedia;
    private String mRuri;
    private String mInitialRoute;
    private String mInviteBranch;
    private String mInvite;
    private int mInviteSends;
    private boolean mInviteAnswered;
    private boolean mProvisional;
    private boolean mCancelWanted;
    private int mCseq = 1;
    private String mTo;
    private String mRemoteTarget;
    private List<String> mRouteSet = new ArrayList<>();
    private String mLocalSdp;
    private String mLocalFrom;
    private final boolean mIncoming;
    private SipMessage mInviteReq;
    private SecAgreeChannel mInviteChannel;
    private SecAgreeChannel.Source mInviteSource;
    private WfcMedia.Params mOffer;
    private String mLastResponse;
    private String mAnswer;
    private int mAnswerSends;
    private boolean mAcked;
    private boolean mStarted;
    private boolean mByeSent;
    private boolean mCancelSent;
    private Runnable mFinalRetransmit;

    WfcCallSession(Context context, Handler handler, Registrar reg, ImsCallProfile profile,
            Owner owner) {
        mContext = context;
        mHandler = new Handler(handler.getLooper());
        mTx = new Handler(handler.getLooper());
        mReg = reg;
        mProfile = profile;
        markWifi(mProfile);
        mOwner = owner;
        mIncoming = false;
        mCallId = Registrar.hex(12) + "@wfcims";
    }

    /** An incoming call; {@link #onInitialInvite} must follow on the handler. */
    WfcCallSession(Context context, Handler handler, Registrar reg, SipMessage invite,
            Owner owner) {
        mContext = context;
        mHandler = new Handler(handler.getLooper());
        mTx = new Handler(handler.getLooper());
        mReg = reg;
        mOwner = owner;
        mIncoming = true;
        mCallId = invite.header("Call-ID");
        mProfile = new ImsCallProfile(ImsCallProfile.SERVICE_TYPE_NORMAL,
                ImsCallProfile.CALL_TYPE_VOICE);
        markWifi(mProfile);
        String caller = callerNumber(invite);
        mProfile.setCallExtra(ImsCallProfile.EXTRA_OI, caller == null ? "" : caller);
        mProfile.setCallExtraInt(ImsCallProfile.EXTRA_OIR, caller == null
                ? ImsCallProfile.OIR_PRESENTATION_RESTRICTED
                : ImsCallProfile.OIR_PRESENTATION_NOT_RESTRICTED);
        mProfile.setCallExtraInt(ImsCallProfile.EXTRA_CNAP,
                ImsCallProfile.OIR_PRESENTATION_NOT_RESTRICTED);
    }

    /** Telephony labels a call as Wi-Fi only from this extra (ImsCall.isWifiCall). */
    private static void markWifi(ImsCallProfile profile) {
        profile.setCallExtraInt(ImsCallProfile.EXTRA_CALL_NETWORK_TYPE,
                TelephonyManager.NETWORK_TYPE_IWLAN);
    }

    /** Number from P-Asserted-Identity (tel: first), else From; null when withheld. */
    static String callerNumber(SipMessage invite) {
        String privacy = invite.header("Privacy");
        if (privacy != null && privacy.toLowerCase().matches(".*\\b(id|header)\\b.*")) {
            return null;
        }
        List<String> ids = new ArrayList<>();
        for (String v : invite.headerValues("P-Asserted-Identity")) {
            for (String part : v.split(",")) ids.add(SipMessage.uri(part.trim()));
        }
        ids.add(SipMessage.uri(invite.header("From")));
        String best = null;
        for (String u : ids) {
            if (u == null) continue;
            String n = null;
            if (u.startsWith("tel:")) {
                n = u.substring(4).split(";")[0];
            } else if (u.startsWith("sip:") || u.startsWith("sips:")) {
                String user = u.substring(u.indexOf(':') + 1).split("[@;]")[0];
                if (user.matches("\\+?[0-9*#]+")) n = user;
            }
            if (n == null || n.isEmpty()) continue;
            if (u.startsWith("tel:")) return n;
            if (best == null) best = n;
        }
        return best;
    }

    @Override
    public void setListener(ImsCallSessionListener listener) {
        mListener = listener;
    }

    @Override
    public String getCallId() {
        return mCallId;
    }

    @Override
    public ImsCallProfile getCallProfile() {
        return mProfile;
    }

    @Override
    public ImsCallProfile getLocalCallProfile() {
        return mProfile;
    }

    @Override
    public ImsCallProfile getRemoteCallProfile() {
        return mProfile;
    }

    @Override
    public int getState() {
        return mState;
    }

    boolean isEnded() {
        return mState == State.TERMINATED;
    }

    @Override
    public boolean isInCall() {
        return mState == State.ESTABLISHED;
    }

    @Override
    public void setMute(boolean muted) {
        // Telecom mutes the microphone through AudioManager, which covers ImsMedia's capture.
    }

    @Override
    public void start(String callee, ImsCallProfile profile) {
        mHandler.post(() -> doStart(callee));
    }

    @Override
    public void terminate(int reason) {
        mHandler.post(this::doTerminate);
    }

    @Override
    public void hold(ImsStreamMediaProfile profile) {
        mHandler.post(() -> {
            if (mListener != null) mListener.callSessionHoldFailed(reason(
                    ImsReasonInfo.CODE_LOCAL_INTERNAL_ERROR, "hold not supported"));
        });
    }

    @Override
    public void resume(ImsStreamMediaProfile profile) {
        mHandler.post(() -> {
            if (mListener != null) mListener.callSessionResumeFailed(reason(
                    ImsReasonInfo.CODE_LOCAL_INTERNAL_ERROR, "resume not supported"));
        });
    }

    @Override
    public void update(int callType, ImsStreamMediaProfile profile) {
        mHandler.post(() -> {
            if (mListener != null) mListener.callSessionUpdateFailed(reason(
                    ImsReasonInfo.CODE_LOCAL_INTERNAL_ERROR, "update not supported"));
        });
    }

    @Override
    public void sendDtmf(char c, Message result) {
        mHandler.post(() -> {
            if (mMedia != null) mMedia.sendDtmf(c);
            if (result != null && result.replyTo != null) {
                try {
                    result.replyTo.send(result);
                } catch (RemoteException e) {
                    Log.w(TAG, "DTMF completion: " + e);
                }
            }
        });
    }

    @Override
    public void startDtmf(char c) {
        mHandler.post(() -> {
            if (mMedia != null) mMedia.startDtmf(c);
        });
    }

    @Override
    public void stopDtmf() {
        mHandler.post(() -> {
            if (mMedia != null) mMedia.stopDtmf();
        });
    }

    @Override
    public void close() {
        mHandler.post(() -> {
            if (mState == State.IDLE) {
                mState = State.TERMINATED;
                mOwner.onSessionEnded(this);
            }
        });
    }

    private void doStart(String callee) {
        if (mState != State.IDLE) return;
        if (!mReg.isRegistered()) {
            failInitiating(ImsReasonInfo.CODE_LOCAL_NOT_REGISTERED, "not registered");
            return;
        }
        try {
            mMedia = new WfcMedia(mContext, mHandler, mReg.network(), mReg.localAddress());
        } catch (Exception e) {
            failInitiating(ImsReasonInfo.CODE_LOCAL_INTERNAL_ERROR, "media: " + e);
            return;
        }
        mRuri = requestUri(callee);
        mInitialRoute = mReg.initialRoute();
        mLocalSdp = localSdp();
        mInviteBranch = "z9hG4bK" + Registrar.hex(8);
        mInvite = "INVITE " + mRuri + " SIP/2.0\r\n"
                + via(mInviteBranch)
                + "Max-Forwards: 70\r\n"
                + "Route: " + mInitialRoute + "\r\n"
                + "From: <" + mReg.identity() + ">;tag=" + mTag + "\r\n"
                + "To: <" + mRuri + ">\r\n"
                + "Call-ID: " + mCallId + "\r\n"
                + "CSeq: 1 INVITE\r\n"
                + "Contact: " + mReg.contact() + "\r\n"
                + "P-Preferred-Identity: <" + mReg.identity() + ">\r\n"
                + "P-Preferred-Service: urn:urn-7:3gpp-service.ims.icsi.mmtel\r\n"
                + "Accept-Contact: *;+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel\"\r\n"
                + "P-Access-Network-Info: IEEE-802.11\r\n"
                + "Supported: 100rel,timer\r\n"
                + "Allow: INVITE,ACK,CANCEL,BYE,UPDATE,PRACK,MESSAGE,OPTIONS,NOTIFY,REFER\r\n"
                + "User-Agent: LineageOS-bullhead-wfcims\r\n"
                + "Content-Type: application/sdp\r\n"
                + "Content-Length: " + mLocalSdp.getBytes(StandardCharsets.UTF_8).length
                + "\r\n\r\n" + mLocalSdp;
        mState = State.INITIATED;
        Log.i(TAG, "calling " + mRuri);
        if (mListener != null) mListener.callSessionInitiating(mProfile);
        mInviteSends = 0;
        sendInvite();
    }

    private void sendInvite() {
        if (mInviteAnswered || mState == State.TERMINATED) return;
        if (!mReg.send(mInvite)) {
            failInitiating(ImsReasonInfo.CODE_LOCAL_NOT_REGISTERED, "IMS channel lost");
            return;
        }
        if (mInviteSends < INVITE_RETRANSMIT_MS.length) {
            mHandler.postDelayed(this::sendInvite, INVITE_RETRANSMIT_MS[mInviteSends++]);
        } else {
            mHandler.postDelayed(() -> {
                if (!mInviteAnswered) {
                    failInitiating(ImsReasonInfo.CODE_SIP_REQUEST_TIMEOUT, "no answer to INVITE");
                }
            }, 4000);
        }
    }

    private String requestUri(String callee) {
        String c = callee.trim();
        if (c.startsWith("sip:") || c.startsWith("tel:")) return c;
        c = c.replaceAll("[^0-9+*#]", "");
        if (c.startsWith("+")) return "tel:" + c;
        return "tel:" + c + ";phone-context=" + mReg.id().domain;
    }

    private String localSdp() {
        String a = mReg.localAddress().getHostAddress();
        return "v=0\r\no=wfcims 1 1 IN IP6 " + a + "\r\ns=-\r\n"
                + "c=IN IP6 " + a + "\r\nb=AS:49\r\nt=0 0\r\n"
                + "m=audio " + mMedia.port() + " RTP/AVP 104 102 105 100\r\nb=AS:49\r\n"
                + "a=rtpmap:104 AMR-WB/16000/1\r\na=fmtp:104 mode-change-capability=2;max-red=0\r\n"
                + "a=rtpmap:102 AMR/8000/1\r\na=fmtp:102 mode-change-capability=2;max-red=0\r\n"
                + "a=rtpmap:105 telephone-event/16000\r\na=fmtp:105 0-15\r\n"
                + "a=rtpmap:100 telephone-event/8000\r\na=fmtp:100 0-15\r\n"
                + "a=ptime:20\r\na=maxptime:240\r\na=sendrecv\r\n";
    }

    private String via(String branch) {
        return "Via: SIP/2.0/UDP " + mReg.localHost() + ":" + mReg.localPort() + ";branch="
                + branch + ";rport\r\n";
    }

    /** Responses routed here by Call-ID. */
    void onResponse(SipMessage msg) {
        String method = msg.method();
        int code = msg.code();
        if (code >= 200) {
            Runnable r = mPending.remove(msg.branch() + " " + method.toUpperCase());
            if (r != null) mTx.removeCallbacks(r);
        }
        if (mState == State.TERMINATED) {
            if ("INVITE".equalsIgnoreCase(method) && code >= 200 && code < 300) {
                onInviteResponse(msg, code);
            }
            return;
        }
        if ("INVITE".equalsIgnoreCase(method)) {
            onInviteResponse(msg, code);
        } else if ("BYE".equalsIgnoreCase(method) && mState == State.TERMINATING) {
            finish(reason(ImsReasonInfo.CODE_USER_TERMINATED, "BYE " + code), false);
        }
    }

    private void onInviteResponse(SipMessage msg, int code) {
        mInviteAnswered = true;
        if (code < 200) {
            mProvisional = true;
            if (mCancelWanted) {
                sendCancel();
                return;
            }
            if (code == 100) return;
            if (msg.body.contains("m=audio")) onRemoteSdp(msg.body);
            String rseq = msg.header("RSeq");
            String require = msg.header("Require");
            if (rseq != null && require != null && require.contains("100rel")
                    && mPracked.add(rseq.trim())) {
                sendInDialog("PRACK", SipMessage.uri(msg.header("Contact")),
                        routeSet(msg), msg.header("To"), ++mCseq,
                        "RAck: " + rseq.trim() + " 1 INVITE\r\n");
            }
            mOwner.useAndroidAudio();
            if (mListener != null && mState == State.INITIATED) {
                mListener.callSessionProgressing(mediaProfile());
            }
        } else if (code < 300) {
            if (mTo == null) {
                mTo = msg.header("To");
                mRouteSet = routeSet(msg);
                mRemoteTarget = SipMessage.uri(msg.header("Contact"));
            }
            sendAck();
            if (mState == State.ESTABLISHED) return;
            if (mState == State.TERMINATING || mState == State.TERMINATED || mCancelWanted) {
                // Answered after we hung up: the dialog exists, so end it.
                sendByeRequest();
                return;
            }
            if (msg.body.contains("m=audio")) onRemoteSdp(msg.body);
            mState = State.ESTABLISHED;
            Log.i(TAG, "call answered");
            mOwner.useAndroidAudio();
            scheduleSessionRefresh(msg.header("Session-Expires"));
            reportStarted();
        } else {
            String ack = "ACK " + mRuri + " SIP/2.0\r\n" + via(mInviteBranch)
                    + "Max-Forwards: 70\r\nRoute: " + mInitialRoute + "\r\n"
                    + "From: <" + mReg.identity() + ">;tag=" + mTag + "\r\n"
                    + "To: " + msg.header("To") + "\r\nCall-ID: " + mCallId + "\r\n"
                    + "CSeq: 1 ACK\r\nContent-Length: 0\r\n\r\n";
            mReg.send(ack);
            if (mState == State.TERMINATED) return;
            if (mCancelWanted || mState == State.TERMINATING) {
                finish(reason(ImsReasonInfo.CODE_USER_TERMINATED, "cancelled"), false);
            } else {
                failInitiating(sipReason(code), msg.startLine);
            }
        }
    }

    /** In-dialog requests routed here by Call-ID. */
    void onRequest(SecAgreeChannel channel, SipMessage msg, SecAgreeChannel.Source from) {
        String method = msg.method().toUpperCase();
        if ("ACK".equals(method) && mFinalRetransmit != null) {
            mTx.removeCallbacks(mFinalRetransmit);
            mFinalRetransmit = null;
        }
        if (mState == State.TERMINATED) {
            if ("INVITE".equals(method) && mIncoming && mInviteReq != null
                    && msg.branch() != null && msg.branch().equals(mInviteReq.branch())) {
                resendInviteResponse();
            } else if (!"ACK".equals(method)) {
                mReg.answer(channel, msg, from, "BYE".equals(method) ? 200 : 481,
                        "BYE".equals(method) ? "OK" : "Call/Transaction Does Not Exist");
            }
            return;
        }
        switch (method) {
            case "ACK":
                if (mIncoming && mAnswer != null && !mAcked) onAck();
                return;
            case "CANCEL":
                mReg.answer(channel, msg, from, 200, "OK");
                if (mIncoming && mState == State.INITIATED && mAnswer == null) {
                    respondInvite(487, "Request Terminated", null, null);
                    Log.i(TAG, "caller cancelled");
                    finish(reason(ImsReasonInfo.CODE_USER_TERMINATED_BY_REMOTE, "CANCEL"),
                            false);
                }
                return;
            case "BYE":
                mReg.answer(channel, msg, from, 200, "OK");
                Log.i(TAG, "remote hung up");
                finish(reason(ImsReasonInfo.CODE_USER_TERMINATED_BY_REMOTE, "BYE"), false);
                return;
            case "INVITE":
                if (mIncoming && mInviteReq != null
                        && mInviteReq.branch() != null
                        && mInviteReq.branch().equals(msg.branch())) {
                    resendInviteResponse();
                    return;
                }
                // fall through: re-INVITE
            case "UPDATE": {
                boolean offer = msg.body.contains("m=audio");
                if (offer) onRemoteSdp(msg.body);
                String se = msg.header("Session-Expires");
                String extra = "Contact: " + mReg.contact() + "\r\n"
                        + (se != null ? "Session-Expires: " + se + "\r\nRequire: timer\r\n" : "");
                mReg.respond(channel, msg, from, 200, "OK", extra,
                        offer || "INVITE".equals(method) ? mLocalSdp : null);
                return;
            }
            default:
                mReg.answer(channel, msg, from, 200, "OK");
        }
    }

    @Override
    public void accept(int callType, ImsStreamMediaProfile profile) {
        mHandler.post(this::doAccept);
    }

    @Override
    public void reject(int reason) {
        mHandler.post(() -> doReject(reason == ImsReasonInfo.CODE_USER_DECLINE ? 603 : 486,
                reason == ImsReasonInfo.CODE_USER_DECLINE ? "Decline" : "Busy Here"));
    }

    /** Answers a new INVITE with 100 and 180, then reports the call; false if refused. */
    boolean onInitialInvite(SecAgreeChannel channel, SipMessage invite,
            SecAgreeChannel.Source from) {
        mInviteReq = invite;
        mInviteChannel = channel;
        mInviteSource = from;
        mLocalFrom = invite.header("To") + ";tag=" + mTag;
        mTo = invite.header("From");
        mRemoteTarget = SipMessage.uri(invite.header("Contact"));
        mRouteSet = new ArrayList<>();
        for (String v : invite.headerValues("Record-Route")) {
            Matcher um = Pattern.compile("<[^>]+>").matcher(v);
            while (um.find()) mRouteSet.add(um.group());
        }
        respondInvite(100, "Trying", null, null);
        String require = invite.header("Require");
        if (require != null && require.toLowerCase().contains("precondition")) {
            Log.w(TAG, "incoming call requires preconditions, not supported");
            respondInvite(420, "Bad Extension", "Unsupported: precondition\r\n", null);
            mState = State.TERMINATED;
            return false;
        }
        mOffer = invite.body.contains("m=audio") ? parseSdp(invite.body) : null;
        if (mOffer == null) {
            Log.w(TAG, "incoming call without a usable AMR offer");
            respondInvite(488, "Not Acceptable Here", null, null);
            mState = State.TERMINATED;
            return false;
        }
        try {
            mMedia = new WfcMedia(mContext, mHandler, mReg.network(), mReg.localAddress());
        } catch (Exception e) {
            Log.w(TAG, "media: " + e);
            respondInvite(500, "Server Internal Error", null, null);
            mState = State.TERMINATED;
            return false;
        }
        mLocalSdp = answerSdp(invite.body, mOffer);
        mState = State.INITIATED;
        respondInvite(180, "Ringing", "Contact: " + mReg.contact() + "\r\n", null);
        Bundle extras = new Bundle();
        ImsCallSessionListener l = mOwner.notifyIncoming(this, extras);
        if (l == null) {
            Log.w(TAG, "framework did not take the incoming call");
            respondInvite(480, "Temporarily Unavailable", null, null);
            finish(reason(ImsReasonInfo.CODE_LOCAL_INTERNAL_ERROR, "not taken"), false);
            return true;
        }
        mListener = l;
        Log.i(TAG, "incoming call ringing");
        return true;
    }

    private void doAccept() {
        if (!mIncoming || mState != State.INITIATED || mAnswer != null) return;
        String se = mInviteReq.header("Session-Expires");
        String extra = "Contact: " + mReg.contact() + "\r\n"
                + "Allow: INVITE,ACK,CANCEL,BYE,UPDATE,PRACK,MESSAGE,OPTIONS,NOTIFY,REFER\r\n"
                + (se != null ? "Session-Expires: " + se.split(";")[0].trim()
                        + ";refresher=uac\r\nRequire: timer\r\n" : "");
        mOwner.useAndroidAudio();
        mMedia.apply(mOffer);
        mAnswer = buildInviteResponse(200, "OK", extra, mLocalSdp);
        mAnswerSends = 0;
        sendAnswer();
        Log.i(TAG, "incoming call answered");
    }

    private void sendAnswer() {
        if (mAcked || mState == State.TERMINATED) return;
        sendRaw(mAnswer);
        if (mAnswerSends < RETRANSMIT_MS.length) {
            mHandler.postDelayed(this::sendAnswer, RETRANSMIT_MS[mAnswerSends++]);
        } else {
            Log.w(TAG, "no ACK for the answer");
            sendBye();
        }
    }

    private void onAck() {
        mAcked = true;
        if (mState != State.INITIATED) return;
        mState = State.ESTABLISHED;
        reportStarted();
    }

    private void reportStarted() {
        mStarted = true;
        if (mListener != null) {
            mListener.callSessionInitiated(new ImsCallProfile(mProfile.getServiceType(),
                    mProfile.getCallType(), mProfile.getCallExtras(), mediaProfile()));
        }
    }

    /** Ends the call without signalling, e.g. when the IMS registration is gone. */
    void abort(String why) {
        finish(reason(ImsReasonInfo.CODE_LOCAL_CALL_TERMINATED, why), !mIncoming && !mStarted);
    }

    /** RFC 4028: refresh with UPDATE when the 2xx makes us (the UAC) the refresher. */
    private void scheduleSessionRefresh(String sessionExpires) {
        if (sessionExpires == null) return;
        String refresher = SipMessage.param(sessionExpires, "refresher");
        if (!"uac".equalsIgnoreCase(refresher)) return;
        final int seconds;
        try {
            seconds = Integer.parseInt(sessionExpires.split(";")[0].trim());
        } catch (NumberFormatException e) {
            return;
        }
        mHandler.postDelayed(() -> {
            if (mState != State.ESTABLISHED) return;
            sendInDialog("UPDATE", mRemoteTarget, mRouteSet, mTo, ++mCseq,
                    "Session-Expires: " + seconds + ";refresher=uac\r\nSupported: timer\r\n"
                            + "Contact: " + mReg.contact() + "\r\n");
            scheduleSessionRefresh(sessionExpires);
        }, seconds * 500L);
    }

    private void doReject(int code, String why) {
        if (!mIncoming || mState != State.INITIATED || mAnswer != null) return;
        respondInvite(code, why, null, null);
        finish(reason(ImsReasonInfo.CODE_USER_DECLINE, why), false);
    }

    private void resendInviteResponse() {
        if (mAnswer != null) {
            sendRaw(mAnswer);
        } else if (mLastResponse != null) {
            sendRaw(mLastResponse);
        }
    }

    private void respondInvite(int code, String reason, String extra, String body) {
        String r = buildInviteResponse(code, reason, extra, body);
        if (code > 100) mLastResponse = r;
        sendRaw(r);
        if (code >= 300) {
            final int[] n = {0};
            mFinalRetransmit = new Runnable() {
                @Override
                public void run() {
                    if (n[0] >= RETRANSMIT_MS.length || mFinalRetransmit != this) return;
                    sendRaw(r);
                    mTx.postDelayed(this, RETRANSMIT_MS[n[0]++]);
                }
            };
            mTx.postDelayed(mFinalRetransmit, RETRANSMIT_MS[n[0]++]);
        }
    }

    private String buildInviteResponse(int code, String reason, String extra, String body) {
        StringBuilder sb = new StringBuilder("SIP/2.0 ").append(code).append(' ').append(reason)
                .append("\r\n");
        for (String[] h : mInviteReq.headers) {
            String n = h[0].toLowerCase();
            if (n.equals("via") || n.equals("from") || n.equals("call-id") || n.equals("cseq")
                    || (n.equals("record-route") && code > 100)) {
                sb.append(h[0]).append(": ").append(h[1]).append("\r\n");
            }
        }
        sb.append("To: ").append(code == 100 ? mInviteReq.header("To") : mLocalFrom)
                .append("\r\n");
        if (extra != null) sb.append(extra);
        if (body != null) {
            sb.append("Content-Type: application/sdp\r\nContent-Length: ")
                    .append(body.getBytes(StandardCharsets.UTF_8).length).append("\r\n\r\n")
                    .append(body);
        } else {
            sb.append("Content-Length: 0\r\n\r\n");
        }
        return sb.toString();
    }

    private void sendRaw(String response) {
        try {
            mInviteChannel.sendResponse(response, mInviteSource);
        } catch (Exception e) {
            Log.w(TAG, "respond: " + e);
        }
    }

    /** SDP answer: the offered AMR payload type and its telephone-event, nothing else. */
    private String answerSdp(String offer, WfcMedia.Params p) {
        String a = mReg.localAddress().getHostAddress();
        String codec = p.wideband ? "AMR-WB/16000/1" : "AMR/8000/1";
        StringBuilder sb = new StringBuilder("v=0\r\no=wfcims 1 1 IN IP6 ").append(a)
                .append("\r\ns=-\r\nc=IN IP6 ").append(a).append("\r\nt=0 0\r\n")
                .append("m=audio ").append(mMedia.port()).append(" RTP/AVP ").append(p.payloadType);
        if (p.dtmfPayloadType >= 0) sb.append(' ').append(p.dtmfPayloadType);
        sb.append("\r\na=rtpmap:").append(p.payloadType).append(' ').append(codec).append("\r\n");
        String fmtp = fmtp(offer, p.payloadType);
        if (fmtp != null) {
            sb.append("a=fmtp:").append(p.payloadType).append(' ').append(fmtp).append("\r\n");
        }
        if (p.dtmfPayloadType >= 0) {
            sb.append("a=rtpmap:").append(p.dtmfPayloadType).append(" telephone-event/")
                    .append(p.dtmfRateKHz * 1000).append("\r\na=fmtp:")
                    .append(p.dtmfPayloadType).append(" 0-15\r\n");
        }
        return sb.append("a=ptime:20\r\na=maxptime:240\r\na=sendrecv\r\n").toString();
    }

    private void onRemoteSdp(String sdp) {
        WfcMedia.Params p = parseSdp(sdp);
        if (p == null) {
            Log.w(TAG, "no usable AMR in the remote SDP");
            return;
        }
        if (mMedia != null) mMedia.apply(p);
    }

    static WfcMedia.Params parseSdp(String sdp) {
        try {
            return parseSdpUnchecked(sdp);
        } catch (RuntimeException e) {
            Log.w(TAG, "bad SDP: " + e);
            return null;
        }
    }

    private static WfcMedia.Params parseSdpUnchecked(String sdp) {
        String conn = null;
        String media = null;
        for (String l : sdp.split("\r?\n")) {
            if (l.startsWith("c=") && conn == null) conn = l;
            if (l.startsWith("m=audio")) media = l;
        }
        if (conn == null || media == null) return null;
        String[] m = media.trim().split(" ");
        int port = Integer.parseInt(m[1]);
        if (port == 0) return null;
        WfcMedia.Params p = new WfcMedia.Params();
        try {
            p.remote = new InetSocketAddress(InetAddress.getByName(conn.trim().split(" ")[2]), port);
        } catch (Exception e) {
            return null;
        }
        p.payloadType = -1;
        for (int i = 3; i < m.length && p.payloadType < 0; i++) {
            String map = rtpmap(sdp, m[i]);
            if (map == null) continue;
            if (map.startsWith("AMR-WB/16000")) {
                p.wideband = true;
                p.payloadType = Integer.parseInt(m[i]);
            } else if (map.startsWith("AMR/8000")) {
                p.payloadType = Integer.parseInt(m[i]);
            }
        }
        if (p.payloadType < 0) return null;
        String rate = p.wideband ? "16000" : "8000";
        for (int i = 3; i < m.length; i++) {
            String map = rtpmap(sdp, m[i]);
            if (map != null && map.startsWith("telephone-event/" + rate)) {
                p.dtmfPayloadType = Integer.parseInt(m[i]);
                p.dtmfRateKHz = p.wideband ? 16 : 8;
            }
        }
        String fmtp = fmtp(sdp, p.payloadType);
        p.octetAligned = fmtp != null && "1".equals(SipMessage.param(fmtp, "octet-align"));
        int modes = 0;
        String set = fmtp == null ? null : modeSet(fmtp);
        if (set != null) {
            for (String s : set.split(",")) {
                try {
                    modes |= 1 << Integer.parseInt(s.trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        p.modeSet = modes != 0 ? modes : (p.wideband ? 0x1ff : 0xff);
        return p;
    }

    private static String rtpmap(String sdp, String pt) {
        Matcher mm = Pattern.compile("a=rtpmap:" + Pattern.quote(pt) + "\\s+(\\S+)").matcher(sdp);
        return mm.find() ? mm.group(1) : null;
    }

    private static String fmtp(String sdp, int pt) {
        Matcher mm = Pattern.compile("a=fmtp:" + pt + "\\s+(.*)").matcher(sdp);
        return mm.find() ? mm.group(1).trim() : null;
    }

    private static String modeSet(String fmtp) {
        Matcher mm = Pattern.compile("mode-set\\s*=\\s*([0-9,\\s]+)").matcher(fmtp);
        return mm.find() ? mm.group(1) : null;
    }

    private void doTerminate() {
        if (mIncoming && mState == State.INITIATED) {
            if (mAnswer == null) {
                doReject(480, "Temporarily Unavailable");
            } else {
                sendBye();
            }
            return;
        }
        switch (mState) {
            case State.ESTABLISHED:
                sendBye();
                break;
            case State.INITIATED:
                mCancelWanted = true;
                mState = State.TERMINATING;
                if (mProvisional) sendCancel();
                mHandler.postDelayed(() -> finish(reason(ImsReasonInfo.CODE_USER_TERMINATED,
                        "cancel timed out"), false), CANCEL_TIMEOUT_MS);
                break;
            case State.TERMINATING:
            case State.TERMINATED:
                break;
            default:
                finish(reason(ImsReasonInfo.CODE_USER_TERMINATED, "not started"), false);
        }
    }

    private void sendBye() {
        mState = State.TERMINATING;
        sendByeRequest();
        mHandler.postDelayed(() -> finish(reason(ImsReasonInfo.CODE_USER_TERMINATED,
                "BYE timed out"), false), BYE_TIMEOUT_MS);
    }

    private void sendByeRequest() {
        if (mByeSent || mTo == null) return;
        mByeSent = true;
        sendInDialog("BYE", mRemoteTarget, mRouteSet, mTo, ++mCseq, "");
    }

    /** Sends a non-INVITE request and retransmits it until a final response or timer F. */
    private void sendReliably(String request, String key) {
        if (!mReg.send(request)) return;
        final int[] n = {0};
        Runnable r = new Runnable() {
            @Override
            public void run() {
                if (mPending.get(key) != this) return;
                if (n[0] >= RETRANSMIT_MS.length) {
                    mPending.remove(key);
                    return;
                }
                mReg.send(request);
                mTx.postDelayed(this, RETRANSMIT_MS[n[0]++]);
            }
        };
        mPending.put(key, r);
        mTx.postDelayed(r, RETRANSMIT_MS[n[0]++]);
    }

    private void sendCancel() {
        if (mCancelSent) return;
        mCancelSent = true;
        sendReliably("CANCEL " + mRuri + " SIP/2.0\r\n" + via(mInviteBranch)
                + "Max-Forwards: 70\r\nRoute: " + mInitialRoute + "\r\n"
                + "From: <" + mReg.identity() + ">;tag=" + mTag + "\r\n"
                + "To: <" + mRuri + ">\r\nCall-ID: " + mCallId + "\r\n"
                + "CSeq: 1 CANCEL\r\nContent-Length: 0\r\n\r\n", mInviteBranch + " CANCEL");
    }

    private void sendAck() {
        sendInDialog("ACK", mRemoteTarget, mRouteSet, mTo, 1, "");
    }

    private void sendInDialog(String method, String target, List<String> routes, String to,
            int cseq, String extra) {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(target).append(" SIP/2.0\r\n");
        String branch = "z9hG4bK" + Registrar.hex(8);
        sb.append(via(branch));
        sb.append("Max-Forwards: 70\r\n");
        for (String r : routes) sb.append("Route: ").append(r).append("\r\n");
        sb.append("From: ").append(localFrom()).append("\r\n");
        sb.append("To: ").append(to).append("\r\n");
        sb.append("Call-ID: ").append(mCallId).append("\r\n");
        sb.append("CSeq: ").append(cseq).append(' ').append(method).append("\r\n");
        sb.append(extra);
        sb.append("Content-Length: 0\r\n\r\n");
        if ("ACK".equals(method)) {
            mReg.send(sb.toString());
        } else {
            sendReliably(sb.toString(), branch + " " + method);
        }
    }

    private String localFrom() {
        return mLocalFrom != null ? mLocalFrom : "<" + mReg.identity() + ">;tag=" + mTag;
    }

    private static List<String> routeSet(SipMessage msg) {
        List<String> rr = new ArrayList<>();
        for (String v : msg.headerValues("Record-Route")) {
            Matcher um = Pattern.compile("<[^>]+>").matcher(v);
            while (um.find()) rr.add(um.group());
        }
        Collections.reverse(rr);
        return rr;
    }

    private static ImsStreamMediaProfile mediaProfile() {
        return new ImsStreamMediaProfile(ImsStreamMediaProfile.AUDIO_QUALITY_AMR,
                ImsStreamMediaProfile.DIRECTION_SEND_RECEIVE,
                ImsStreamMediaProfile.VIDEO_QUALITY_NONE,
                ImsStreamMediaProfile.DIRECTION_INVALID,
                ImsStreamMediaProfile.RTT_MODE_DISABLED);
    }

    private static int sipReason(int code) {
        switch (code) {
            case 403: return ImsReasonInfo.CODE_SIP_FORBIDDEN;
            case 404: return ImsReasonInfo.CODE_SIP_NOT_FOUND;
            case 408: return ImsReasonInfo.CODE_SIP_REQUEST_TIMEOUT;
            case 480: return ImsReasonInfo.CODE_SIP_TEMPRARILY_UNAVAILABLE;
            case 486:
            case 600: return ImsReasonInfo.CODE_SIP_BUSY;
            case 487: return ImsReasonInfo.CODE_SIP_REQUEST_CANCELLED;
            case 603: return ImsReasonInfo.CODE_SIP_USER_REJECTED;
            default:
                return code >= 500 ? ImsReasonInfo.CODE_SIP_SERVER_ERROR
                        : ImsReasonInfo.CODE_SIP_NOT_REACHABLE;
        }
    }

    private static ImsReasonInfo reason(int code, String extra) {
        return new ImsReasonInfo(code, 0, extra);
    }

    private void failInitiating(int code, String why) {
        Log.i(TAG, "call failed: " + why);
        finish(reason(code, why), true);
    }

    /** Ends the call once: closes the media and reports to the framework. */
    void finish(ImsReasonInfo why, boolean initiating) {
        if (mState == State.TERMINATED) return;
        mState = State.TERMINATED;
        mHandler.removeCallbacksAndMessages(null);
        if (mMedia != null) {
            mMedia.close();
            mMedia = null;
        }
        Log.i(TAG, "call ended: " + why);
        if (mListener != null) {
            if (initiating) {
                mListener.callSessionInitiatingFailed(why);
            } else {
                mListener.callSessionTerminated(why);
            }
        }
        mOwner.onSessionEnded(this);
    }
}
