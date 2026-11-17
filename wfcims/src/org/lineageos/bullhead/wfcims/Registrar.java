/*
 * SPDX-FileCopyrightText: 2026 Romain Théry
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.bullhead.wfcims;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.InetAddresses;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Handler;
import android.util.Log;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** IMS registration over one IMS network, with IMS-AKA and sec-agree. Runs on its handler. */
final class Registrar implements SecAgreeChannel.Listener {
    private static final String TAG = "WfcIms";
    private static final int REQUESTED_EXPIRES = 3600;
    private static final long RETRY_MS = 60_000;
    private static final long[] RETRANSMIT_MS = {500, 1000, 2000, 4000};
    private static final int SUBSCRIBE_EXPIRES = 600000;

    interface Callback {
        void onRegistering();

        void onRegistered(String identity);

        void onDeregistered(String reason);

        /** A registration attempt failed; refused when the network answered with a refusal. */
        void onRegistrationFailed(boolean refused);

        /** A request other than REGISTER responses arrived; return true if handled. */
        boolean onRequest(SecAgreeChannel channel, SipMessage msg, SecAgreeChannel.Source from);

        /** A response to a request other than REGISTER arrived. */
        void onResponse(SipMessage msg);
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Context mContext;
    private final Handler mHandler;
    private final ImsIdentity mId;
    private final Callback mCallback;
    private Network mNetwork;
    private SecAgreeChannel mChannel;
    private String mCallId;
    private String mTag;
    private int mCseq;
    private String mPendingBranch;
    private String mPendingRequest;
    private int mRetransmits;
    private int mExpires;
    private boolean mRegistered;
    private boolean mStopping;
    private String mIdentity;
    private String mRealm;
    private String mNonce;
    private String mOpaque;
    private byte[] mRes;
    private int mNc;
    private String mInstanceId;
    private final List<String> mServiceRoute = new ArrayList<>();
    private String mSubCallId;
    private String mSubTag;
    private String mSubBranch;
    private String mSubToTag;
    private int mSubCseq;
    private boolean mSubscribed;
    private int mSmsCseq;

    Registrar(Context context, Handler handler, ImsIdentity id, Callback callback) {
        mContext = context;
        mHandler = handler;
        mId = id;
        mCallback = callback;
    }

    String identity() {
        return mIdentity;
    }

    SecAgreeChannel channel() {
        return mChannel;
    }

    boolean isRegistered() {
        return mRegistered;
    }

    ImsIdentity id() {
        return mId;
    }

    Network network() {
        return mNetwork;
    }

    /** Our address as it appears in Via and Contact, bracketed. */
    String localHost() {
        return "[" + mChannel.local.getHostAddress() + "]";
    }

    int localPort() {
        return mChannel.serverPort();
    }

    /**
     * Sends a SIP MESSAGE carrying an SMS relay message to the service centre, or back to
     * whoever delivered one. False when there is no registration to send it over.
     */
    String sendSms(byte[] rp, String requestUri, String inReplyTo) {
        if (mChannel == null || !mChannel.isProtected() || mIdentity == null) return null;
        String target = requestUri != null ? requestUri : mIdentity;
        StringBuilder sb = new StringBuilder();
        sb.append("MESSAGE ").append(target).append(" SIP/2.0\r\n");
        sb.append("Via: SIP/2.0/UDP ").append(localHost()).append(':').append(localPort())
                .append(";branch=z9hG4bK").append(hex(8)).append(";rport\r\n");
        sb.append("Max-Forwards: 70\r\n");
        sb.append("Route: ").append(initialRoute()).append("\r\n");
        for (String r : mServiceRoute) sb.append("Route: ").append(r).append("\r\n");
        sb.append("From: <").append(mIdentity).append(">;tag=").append(hex(4)).append("\r\n");
        sb.append("To: <").append(target).append(">\r\n");
        String callId = hex(12) + "@wfcims";
        sb.append("Call-ID: ").append(callId).append("\r\n");
        sb.append("CSeq: ").append(++mSmsCseq).append(" MESSAGE\r\n");
        sb.append("P-Preferred-Identity: <").append(mIdentity).append(">\r\n");
        sb.append("Request-Disposition: no-fork\r\n");
        // TS 24.341: an acknowledgement names the delivery it answers.
        if (inReplyTo != null) sb.append("In-Reply-To: ").append(inReplyTo).append("\r\n");
        sb.append("P-Access-Network-Info: IEEE-802.11\r\n");
        sb.append("Security-Verify: ").append(mChannel.securityVerify()).append("\r\n");
        sb.append("Require: sec-agree\r\nProxy-Require: sec-agree\r\n");
        sb.append("User-Agent: LineageOS-bullhead-wfcims\r\n");
        sb.append("Content-Type: ").append(WfcSms.CONTENT_TYPE).append("\r\n");
        sb.append("Content-Length: ").append(rp.length).append("\r\n\r\n");
        try {
            mChannel.sendRequest(sb.toString(), rp);
            return callId;
        } catch (Exception e) {
            Log.w(TAG, "sending an SMS: " + e);
            return null;
        }
    }

    /** Loose route to the P-CSCF's protected port, for requests outside a dialog. */
    String initialRoute() {
        return "<sip:[" + mChannel.pcscf.getHostAddress() + "]:" + mChannel.pcscfPort() + ";lr>";
    }

    InetAddress localAddress() {
        return mChannel.local;
    }

    String contact() {
        return "<sip:" + mId.user + "@" + localHost() + ":" + localPort() + ">"
                + (mInstanceId != null ? ";+sip.instance=\"<" + mInstanceId + ">\"" : "")
                + ";+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel\";audio"
                + ";+g.3gpp.smsip";
    }

    /** Sends a request to the P-CSCF over the protected channel; false if that failed. */
    boolean send(String request) {
        if (mChannel == null || !mChannel.isProtected()) return false;
        try {
            mChannel.sendRequest(request);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "send: " + e);
            return false;
        }
    }

    void start(Network network) {
        mNetwork = network;
        mStopping = false;
        startFresh();
    }

    /** Deregisters if registered, then closes the channel. */
    void stop() {
        mStopping = true;
        mHandler.removeCallbacksAndMessages(null);
        if (mRegistered && mChannel != null && mChannel.isProtected()) {
            sendRegister(0);
            mHandler.postDelayed(this::closeChannel, 3000);
        } else {
            closeChannel();
        }
        if (mRegistered) {
            mRegistered = false;
            mCallback.onDeregistered("stopped");
        }
    }

    private void closeChannel() {
        if (mChannel != null) {
            mChannel.close();
            mChannel = null;
        }
    }

    private void startFresh() {
        closeChannel();
        mHandler.removeCallbacksAndMessages(null);
        try {
            LinkProperties lp = mContext.getSystemService(ConnectivityManager.class)
                    .getLinkProperties(mNetwork);
            if (lp == null) {
                // The network is gone; the feature starts a new registrar when one comes back.
                mStopping = true;
                fail("IMS network " + mNetwork + " is gone");
                return;
            }
            InetAddress local = null;
            for (LinkAddress la : lp.getLinkAddresses()) {
                InetAddress a = la.getAddress();
                if (a instanceof Inet6Address && !a.isLinkLocalAddress()) local = a;
            }
            List<InetAddress> pcscfs = lp.getPcscfServers();
            if (local == null || pcscfs.isEmpty()) {
                fail("no IPv6 address or P-CSCF on the IMS network");
                return;
            }
            mChannel = new SecAgreeChannel(mContext, mNetwork, local, pcscfs.get(0), mHandler, this);
        } catch (Exception e) {
            fail("channel: " + e);
            return;
        }
        mCallId = hex(12) + "@wfcims";
        mTag = hex(4);
        mCseq = 0;
        mNonce = null;
        mInstanceId = mId.instanceId();
        mServiceRoute.clear();
        mSubscribed = false;
        mSubCallId = null;
        mSubToTag = null;
        mCallback.onRegistering();
        sendRegister(REQUESTED_EXPIRES);
    }

    private void fail(String reason) {
        fail(reason, false);
    }

    private void fail(String reason, boolean refused) {
        Log.w(TAG, "registration failed: " + reason);
        closeChannel();
        mRegistered = false;
        mCallback.onDeregistered(reason);
        mCallback.onRegistrationFailed(refused);
        if (!mStopping) mHandler.postDelayed(this::startFresh, RETRY_MS);
    }

    private void sendRegister(int expires) {
        String host = "[" + mChannel.local.getHostAddress() + "]";
        int portS = mChannel.serverPort();
        mPendingBranch = "z9hG4bK" + hex(8);
        String auth;
        try {
            auth = mNonce == null ? emptyAuth() : akaAuth();
        } catch (Exception e) {
            fail("digest: " + e);
            return;
        }
        mPendingRequest = "REGISTER sip:" + mId.domain + " SIP/2.0\r\n"
                + "Via: SIP/2.0/UDP " + host + ":" + portS + ";branch=" + mPendingBranch + ";rport\r\n"
                + "Max-Forwards: 70\r\n"
                + "From: <" + mId.impu + ">;tag=" + mTag + "\r\n"
                + "To: <" + mId.impu + ">\r\n"
                + "Call-ID: " + mCallId + "\r\n"
                + "CSeq: " + (++mCseq) + " REGISTER\r\n"
                + "Contact: " + contact() + "\r\n"
                + "Expires: " + expires + "\r\n"
                + "Authorization: " + auth + "\r\n"
                + "Security-Client: " + mChannel.securityClient() + "\r\n"
                + (mChannel.isProtected() ? "Security-Verify: " + mChannel.securityVerify() + "\r\n" : "")
                + "Require: sec-agree\r\n"
                + "Proxy-Require: sec-agree\r\n"
                + "Supported: path,gruu,sec-agree\r\n"
                + "P-Access-Network-Info: IEEE-802.11\r\n"
                + "Allow: INVITE,ACK,CANCEL,BYE,UPDATE,PRACK,MESSAGE,OPTIONS,NOTIFY,REFER\r\n"
                + "User-Agent: LineageOS-bullhead-wfcims\r\n"
                + "Content-Length: 0\r\n\r\n";
        mRetransmits = 0;
        transmit();
    }

    private void transmit() {
        if (mChannel == null || mPendingRequest == null) return;
        try {
            mChannel.sendRequest(mPendingRequest);
        } catch (Exception e) {
            fail("send: " + e);
            return;
        }
        final String branch = mPendingBranch;
        if (mRetransmits < RETRANSMIT_MS.length) {
            mHandler.postDelayed(() -> {
                if (branch.equals(mPendingBranch)) {
                    mRetransmits++;
                    transmit();
                }
            }, RETRANSMIT_MS[mRetransmits]);
        } else {
            mHandler.postDelayed(() -> {
                if (branch.equals(mPendingBranch)) fail("REGISTER timed out");
            }, 8000);
        }
    }

    @Override
    public void onMessage(SecAgreeChannel channel, SipMessage msg, SecAgreeChannel.Source from) {
        if (channel != mChannel) return;
        if (!msg.isResponse()) {
            // Until the security associations exist anything on the IMS network can reach these
            // ports, so a call, a text or a deregistration could be forged; refuse them all.
            if (!channel.isProtected()) {
                Log.w(TAG, "refusing " + msg.method() + " before the security associations exist");
                answer(channel, msg, from, 403, "Forbidden");
                return;
            }
            if (mSubCallId != null && mSubCallId.equals(msg.header("Call-ID"))
                    && "NOTIFY".equalsIgnoreCase(msg.method())) {
                answer(channel, msg, from, 200, "OK");
                onRegNotify(msg);
                return;
            }
            if (!mCallback.onRequest(channel, msg, from)) answer(channel, msg, from, 200, "OK");
            return;
        }
        if ("SUBSCRIBE".equalsIgnoreCase(msg.method())
                && mSubCallId != null && mSubCallId.equals(msg.header("Call-ID"))) {
            onSubscribeResponse(msg);
            return;
        }
        if (!"REGISTER".equalsIgnoreCase(msg.method())) {
            mCallback.onResponse(msg);
            return;
        }
        if (mPendingBranch == null || !mPendingBranch.equals(msg.branch())) return;
        int code = msg.code();
        if (code < 200) return;
        mPendingBranch = null;
        mPendingRequest = null;
        if (code == 401) {
            onChallenge(msg);
        } else if (code >= 200 && code < 300) {
            onRegisterOk(msg);
        } else {
            // 403 and 404 say this subscriber may not register here; anything else may pass.
            fail("REGISTER " + msg.startLine, code == 403 || code == 404);
        }
    }

    private void onChallenge(SipMessage msg) {
        if (mChannel.isProtected()) {
            Log.i(TAG, "re-registration challenged, starting a new security association");
            startFresh();
            return;
        }
        String server = msg.header("Security-Server");
        String www = msg.header("WWW-Authenticate");
        if (server == null || www == null) {
            fail("401 without Security-Server or WWW-Authenticate");
            return;
        }
        mRealm = SipMessage.param(www, "realm");
        mNonce = SipMessage.param(www, "nonce");
        mOpaque = SipMessage.param(www, "opaque");
        mNc = 0;
        byte[][] aka = mId.aka(mNonce);
        if (aka == null) {
            fail("AKA failed", true);
            return;
        }
        mRes = aka[0];
        try {
            mChannel.protect(server, aka[2]);
        } catch (Exception e) {
            fail("sec-agree: " + e);
            return;
        }
        sendRegister(REQUESTED_EXPIRES);
    }

    private void onRegisterOk(SipMessage msg) {
        if (mStopping) {
            Log.i(TAG, "deregistered");
            return;
        }
        int expires = REQUESTED_EXPIRES;
        for (String c : msg.headerValues("Contact")) {
            String e = SipMessage.param(c, "expires");
            if (e != null) expires = Integer.parseInt(e.trim());
        }
        mExpires = expires;
        String pau = msg.header("P-Associated-URI");
        mIdentity = pau != null ? SipMessage.uri(pau) : mId.impu;
        mServiceRoute.clear();
        for (String v : msg.headerValues("Service-Route")) {
            Matcher m = Pattern.compile("<[^>]+>").matcher(v);
            while (m.find()) mServiceRoute.add(m.group());
        }
        boolean first = !mRegistered;
        mRegistered = true;
        Log.i(TAG, "registered for " + expires + " s, service route " + mServiceRoute);
        if (first) mCallback.onRegistered(mIdentity);
        mHandler.postDelayed(() -> sendRegister(REQUESTED_EXPIRES), expires * 500L);
        if (!mSubscribed) sendSubscribe(SUBSCRIBE_EXPIRES);
    }

    /**
     * Subscribes to our own registration state (TS 24.229 5.1.1.3). The network uses it to tell
     * us when a binding goes away; a failure here never disturbs the registration itself.
     */
    private void sendSubscribe(int expires) {
        if (mChannel == null || !mChannel.isProtected()) return;
        if (mSubCallId == null) {
            mSubCallId = hex(12) + "@wfcims";
            mSubTag = hex(4);
            mSubCseq = 0;
        }
        mSubBranch = "z9hG4bK" + hex(8);
        StringBuilder sb = new StringBuilder();
        sb.append("SUBSCRIBE ").append(mIdentity).append(" SIP/2.0\r\n");
        sb.append("Via: SIP/2.0/UDP ").append(localHost()).append(':').append(localPort())
                .append(";branch=").append(mSubBranch).append(";rport\r\n");
        sb.append("Max-Forwards: 70\r\n");
        sb.append("Route: ").append(initialRoute()).append("\r\n");
        for (String r : mServiceRoute) sb.append("Route: ").append(r).append("\r\n");
        sb.append("From: <").append(mIdentity).append(">;tag=").append(mSubTag).append("\r\n");
        sb.append("To: <").append(mIdentity).append('>')
                .append(mSubToTag != null ? ";tag=" + mSubToTag : "").append("\r\n");
        sb.append("Call-ID: ").append(mSubCallId).append("\r\n");
        sb.append("CSeq: ").append(++mSubCseq).append(" SUBSCRIBE\r\n");
        sb.append("Event: reg\r\n");
        sb.append("Expires: ").append(expires).append("\r\n");
        sb.append("Accept: application/reginfo+xml\r\n");
        sb.append("Contact: ").append(contact()).append("\r\n");
        sb.append("Security-Verify: ").append(mChannel.securityVerify()).append("\r\n");
        sb.append("Require: sec-agree\r\nProxy-Require: sec-agree\r\n");
        sb.append("P-Access-Network-Info: IEEE-802.11\r\n");
        sb.append("Content-Length: 0\r\n\r\n");
        if (!send(sb.toString())) Log.w(TAG, "could not send the reg-event SUBSCRIBE");
    }

    private void onSubscribeResponse(SipMessage msg) {
        int code = msg.code();
        if (code < 200) return;
        if (code >= 300) {
            Log.w(TAG, "reg-event subscription refused: " + msg.startLine);
            return;
        }
        int expires = 0;
        String e = msg.header("Expires");
        if (e != null) {
            try {
                expires = Integer.parseInt(e.trim());
            } catch (NumberFormatException ignored) {
                // Keep 0: the NOTIFY's Subscription-State carries the authoritative value.
            }
        }
        mSubscribed = true;
        mSubToTag = SipMessage.param(msg.header("To"), "tag");
        Log.i(TAG, "subscribed to our registration state for " + expires + " s");
        if (expires > 60) {
            mHandler.postDelayed(() -> {
                if (mRegistered) sendSubscribe(SUBSCRIBE_EXPIRES);
            }, expires * 500L);
        }
    }

    private static final Pattern REGISTRATION =
            Pattern.compile("<registration\\b[^>]*?\\bstate=\"([a-z]+)\"");
    private static final Pattern CONTACT = Pattern.compile(
            "<contact\\b([^>]*)>(?:(?!</contact>).)*?<uri>([^<]*)</uri>", Pattern.DOTALL);
    private static final Pattern STATE = Pattern.compile("\\bstate=\"([a-z]+)\"");
    private static final Pattern EVENT = Pattern.compile("\\bevent=\"([a-z]+)\"");
    private static final Pattern URI_HOST = Pattern.compile("@\\[([0-9A-Fa-f:.]+)\\]");

    /**
     * A binding the network ended would otherwise stay shown as registered until the next
     * refresh, with incoming calls lost meanwhile (TS 24.229 5.1.1.5).
     */
    private void onRegNotify(SipMessage msg) {
        Matcher m = Pattern.compile("state=\"([a-z]+)\"(?:[^>]*?\\bevent=\"([a-z]+)\")?")
                .matcher(msg.body);
        StringBuilder states = new StringBuilder();
        while (m.find()) {
            states.append(m.group(1));
            if (m.group(2) != null) states.append('/').append(m.group(2));
            states.append(' ');
        }
        Log.i(TAG, "registration state notified: " + states.toString().trim());
        if (!mRegistered || mStopping) return;
        String event = endedEvent(msg.body);
        if (event == null) return;
        boolean later = event.equals("rejected") || event.equals("probation");
        Log.w(TAG, "the network ended our registration (" + event + "), registering again"
                + (later ? " in " + RETRY_MS / 1000 + " s" : ""));
        closeChannel();
        mHandler.removeCallbacksAndMessages(null);
        mRegistered = false;
        mCallback.onDeregistered("ended by the network: " + event);
        mHandler.postDelayed(this::startFresh, later ? RETRY_MS : 0);
    }

    /** The event that ended our binding, or null while it is still active or not listed. */
    private String endedEvent(String body) {
        String ended = null;
        Matcher c = CONTACT.matcher(body);
        while (c.find()) {
            if (!isOurs(c.group(2))) continue;
            Matcher state = STATE.matcher(c.group(1));
            if (!state.find() || !state.group(1).equals("terminated")) return null;
            Matcher event = EVENT.matcher(c.group(1));
            ended = event.find() ? event.group(1) : "terminated";
        }
        if (ended != null) return ended;
        // No contact of ours listed: only a registration terminated as a whole ends ours.
        Matcher r = REGISTRATION.matcher(body);
        boolean any = false;
        while (r.find()) {
            if (!r.group(1).equals("terminated")) return null;
            any = true;
        }
        return any ? "terminated" : null;
    }

    private boolean isOurs(String uri) {
        Matcher h = URI_HOST.matcher(uri);
        if (!h.find() || mChannel == null) return false;
        try {
            return InetAddresses.parseNumericAddress(h.group(1)).equals(mChannel.local);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    void answer(SecAgreeChannel channel, SipMessage req, SecAgreeChannel.Source to, int code,
            String reason) {
        respond(channel, req, to, code, reason, null, null);
    }

    /** Answers a request; extra is whole header lines, body is SDP when not null. */
    void respond(SecAgreeChannel channel, SipMessage req, SecAgreeChannel.Source to, int code,
            String reason, String extra, String body) {
        if ("ACK".equalsIgnoreCase(req.method())) return;
        StringBuilder sb = new StringBuilder("SIP/2.0 ").append(code).append(' ').append(reason)
                .append("\r\n");
        for (String[] h : req.headers) {
            String n = h[0].toLowerCase();
            if (n.equals("via") || n.equals("from") || n.equals("call-id") || n.equals("cseq")
                    || n.equals("record-route")) {
                sb.append(h[0]).append(": ").append(h[1]).append("\r\n");
            } else if (n.equals("to")) {
                sb.append(h[0]).append(": ").append(h[1])
                        .append(h[1].contains(";tag=") ? "" : ";tag=" + hex(4)).append("\r\n");
            }
        }
        if (extra != null) sb.append(extra);
        if (body != null) {
            sb.append("Content-Type: application/sdp\r\nContent-Length: ")
                    .append(body.getBytes(StandardCharsets.UTF_8).length).append("\r\n\r\n")
                    .append(body);
        } else {
            sb.append("Content-Length: 0\r\n\r\n");
        }
        try {
            channel.sendResponse(sb.toString(), to);
        } catch (Exception e) {
            Log.w(TAG, "answer: " + e);
        }
    }

    private String emptyAuth() {
        return "Digest username=\"" + mId.impi + "\",realm=\"" + mId.domain + "\",uri=\"sip:"
                + mId.domain + "\",nonce=\"\",response=\"\"";
    }

    private String akaAuth() throws Exception {
        String uri = "sip:" + mId.domain;
        String nc = String.format("%08x", ++mNc);
        String cnonce = hex(8);
        byte[] prefix = (mId.impi + ":" + mRealm + ":").getBytes(StandardCharsets.UTF_8);
        byte[] ha1In = new byte[prefix.length + mRes.length];
        System.arraycopy(prefix, 0, ha1In, 0, prefix.length);
        System.arraycopy(mRes, 0, ha1In, prefix.length, mRes.length);
        String ha1 = md5(ha1In);
        String ha2 = md5(("REGISTER:" + uri).getBytes(StandardCharsets.UTF_8));
        String response = md5((ha1 + ":" + mNonce + ":" + nc + ":" + cnonce + ":auth:" + ha2)
                .getBytes(StandardCharsets.UTF_8));
        return "Digest username=\"" + mId.impi + "\",realm=\"" + mRealm + "\",uri=\"" + uri
                + "\",nonce=\"" + mNonce + "\",response=\"" + response
                + "\",algorithm=AKAv1-MD5,qop=auth,nc=" + nc + ",cnonce=\"" + cnonce + "\""
                + (mOpaque != null ? ",opaque=\"" + mOpaque + "\"" : "");
    }

    private static String md5(byte[] in) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("MD5").digest(in)) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    static String hex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
