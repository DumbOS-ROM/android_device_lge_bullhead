/*
 * SPDX-FileCopyrightText: 2026 Romain Théry
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.bullhead.wfcims;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Handler;
import android.util.Log;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;

/** IMS registration over one IMS network, with IMS-AKA and sec-agree. Runs on its handler. */
final class Registrar implements SecAgreeChannel.Listener {
    private static final String TAG = "WfcIms";
    private static final int REQUESTED_EXPIRES = 3600;
    private static final long RETRY_MS = 60_000;
    private static final long[] RETRANSMIT_MS = {500, 1000, 2000, 4000};

    interface Callback {
        void onRegistering();

        void onRegistered(String identity);

        void onDeregistered(String reason);

        /** A request other than REGISTER responses arrived; return true if handled. */
        boolean onRequest(SecAgreeChannel channel, SipMessage msg, InetSocketAddress from);

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

    /** Loose route to the P-CSCF's protected port, for requests outside a dialog. */
    String initialRoute() {
        return "<sip:[" + mChannel.pcscf.getHostAddress() + "]:" + mChannel.pcscfPort() + ";lr>";
    }

    InetAddress localAddress() {
        return mChannel.local;
    }

    String contact() {
        return "<sip:" + mId.user + "@" + localHost() + ":" + localPort()
                + ">;+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel\"";
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
        mCallback.onRegistering();
        sendRegister(REQUESTED_EXPIRES);
    }

    private void fail(String reason) {
        Log.w(TAG, "registration failed: " + reason);
        closeChannel();
        mRegistered = false;
        mCallback.onDeregistered(reason);
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
                + "Contact: <sip:" + mId.user + "@" + host + ":" + portS + ">;"
                + "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel\";+g.3gpp.smsip\r\n"
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
    public void onMessage(SecAgreeChannel channel, SipMessage msg, InetSocketAddress from) {
        if (channel != mChannel) return;
        if (!msg.isResponse()) {
            if (!mCallback.onRequest(channel, msg, from)) answer(channel, msg, from, 200, "OK");
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
            fail("REGISTER " + msg.startLine);
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
            fail("AKA failed");
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
        boolean first = !mRegistered;
        mRegistered = true;
        Log.i(TAG, "registered for " + expires + " s");
        if (first) mCallback.onRegistered(mIdentity);
        mHandler.postDelayed(() -> sendRegister(REQUESTED_EXPIRES), expires * 500L);
    }

    void answer(SecAgreeChannel channel, SipMessage req, InetSocketAddress to, int code,
            String reason) {
        respond(channel, req, to, code, reason, null, null);
    }

    /** Answers a request; extra is whole header lines, body is SDP when not null. */
    void respond(SecAgreeChannel channel, SipMessage req, InetSocketAddress to, int code,
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
