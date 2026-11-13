/*
 * SPDX-FileCopyrightText: 2026 Romain Théry
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.bullhead.wfcims;

import android.content.Context;
import android.net.IpSecAlgorithm;
import android.net.IpSecManager;
import android.net.IpSecTransform;
import android.net.Network;
import android.os.Handler;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * SIP transport to one P-CSCF with the 3GPP security agreement (TS 33.203): a client and a
 * server UDP socket, protected by four IPsec transport SAs once the P-CSCF has answered.
 */
final class SecAgreeChannel implements AutoCloseable {
    private static final String TAG = "WfcIms";

    interface Listener {
        void onMessage(SecAgreeChannel channel, SipMessage msg, InetSocketAddress from);
    }

    final InetAddress local;
    final InetAddress pcscf;
    private final Context mContext;
    private final Handler mHandler;
    private final Listener mListener;
    private final IpSecManager mIpSec;
    private final DatagramSocket mClient;
    private final DatagramSocket mServer;
    private final IpSecManager.SecurityParameterIndex mSpiC;
    private final IpSecManager.SecurityParameterIndex mSpiS;
    private final List<AutoCloseable> mResources = new ArrayList<>();
    private volatile boolean mOpen = true;
    private String mSecurityVerify;
    private int mPcscfPortS = 5060;

    SecAgreeChannel(Context context, Network network, InetAddress local, InetAddress pcscf,
            Handler handler, Listener listener) throws Exception {
        this.local = local;
        this.pcscf = pcscf;
        mContext = context;
        mHandler = handler;
        mListener = listener;
        mIpSec = context.getSystemService(IpSecManager.class);
        mClient = socket(network);
        mServer = socket(network);
        mSpiC = keep(mIpSec.allocateSecurityParameterIndex(local));
        mSpiS = keep(mIpSec.allocateSecurityParameterIndex(local));
        startReceiver(mClient);
        startReceiver(mServer);
    }

    private DatagramSocket socket(Network network) throws Exception {
        DatagramSocket s = new DatagramSocket(null);
        mResources.add(s);
        s.bind(new InetSocketAddress(local, 0));
        network.bindSocket(s);
        return s;
    }

    private <T extends AutoCloseable> T keep(T t) {
        mResources.add(t);
        return t;
    }

    int pcscfPort() {
        return mPcscfPortS;
    }

    int serverPort() {
        return mServer.getLocalPort();
    }

    boolean isProtected() {
        return mSecurityVerify != null;
    }

    String securityClient() {
        return "ipsec-3gpp;prot=esp;mod=trans;spi-c=" + (mSpiC.getSpi() & 0xffffffffL)
                + ";spi-s=" + (mSpiS.getSpi() & 0xffffffffL) + ";port-c=" + mClient.getLocalPort()
                + ";port-s=" + mServer.getLocalPort() + ";alg=hmac-sha-1-96;ealg=null";
    }

    String securityVerify() {
        return mSecurityVerify;
    }

    /** Installs the four SAs from the P-CSCF's Security-Server header and the AKA IK. */
    void protect(String securityServer, byte[] ik) throws Exception {
        long pSpiC = Long.parseLong(SipMessage.param(securityServer, "spi-c"));
        long pSpiS = Long.parseLong(SipMessage.param(securityServer, "spi-s"));
        int pPortS = Integer.parseInt(SipMessage.param(securityServer, "port-s"));
        String alg = SipMessage.param(securityServer, "alg");
        if (!"hmac-sha-1-96".equalsIgnoreCase(alg)) {
            throw new IllegalStateException("unsupported sec-agree alg " + alg);
        }
        byte[] key = new byte[20];
        System.arraycopy(ik, 0, key, 0, 16);
        IpSecManager.SecurityParameterIndex outC =
                keep(mIpSec.allocateSecurityParameterIndex(pcscf, (int) pSpiS));
        IpSecManager.SecurityParameterIndex outS =
                keep(mIpSec.allocateSecurityParameterIndex(pcscf, (int) pSpiC));
        apply(mClient, IpSecManager.DIRECTION_OUT, local, outC, key);
        apply(mClient, IpSecManager.DIRECTION_IN, pcscf, mSpiC, key);
        apply(mServer, IpSecManager.DIRECTION_OUT, local, outS, key);
        apply(mServer, IpSecManager.DIRECTION_IN, pcscf, mSpiS, key);
        mSecurityVerify = securityServer;
        mPcscfPortS = pPortS;
        Log.i(TAG, "sec-agree SAs installed, P-CSCF protected port " + pPortS);
    }

    private void apply(DatagramSocket s, int direction, InetAddress source,
            IpSecManager.SecurityParameterIndex spi, byte[] key) throws Exception {
        IpSecTransform t = keep(new IpSecTransform.Builder(mContext)
                .setAuthentication(new IpSecAlgorithm(IpSecAlgorithm.AUTH_HMAC_SHA1, key, 96))
                .buildTransportModeTransform(source, spi));
        mIpSec.applyTransportModeTransform(s, direction, t);
    }

    /** Sends a request from the client socket: port 5060 before protection, port-s after. */
    void sendRequest(String msg) throws Exception {
        send(mClient, new InetSocketAddress(pcscf, mPcscfPortS), msg);
    }

    /** Sends a response to a request the P-CSCF sent us. */
    void sendResponse(String msg, InetSocketAddress to) throws Exception {
        send(mServer, to, msg);
    }

    private void send(DatagramSocket s, InetSocketAddress to, String msg) throws Exception {
        byte[] out = msg.getBytes(StandardCharsets.UTF_8);
        s.send(new DatagramPacket(out, out.length, to));
        Log.d(TAG, "sent " + msg.substring(0, msg.indexOf('\r')) + " to port " + to.getPort());
    }

    private void startReceiver(DatagramSocket s) {
        new Thread(() -> {
            byte[] buf = new byte[16384];
            while (mOpen) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (SocketException e) {
                    break;
                } catch (Exception e) {
                    Log.w(TAG, "receive: " + e);
                    continue;
                }
                SipMessage msg;
                try {
                    msg = SipMessage.parse(buf, p.getLength());
                } catch (RuntimeException e) {
                    Log.w(TAG, "unparsable SIP message dropped: " + e);
                    continue;
                }
                InetSocketAddress from = (InetSocketAddress) p.getSocketAddress();
                Log.d(TAG, "received " + msg.summary());
                mHandler.post(() -> {
                    if (mOpen) mListener.onMessage(this, msg, from);
                });
            }
        }, "WfcImsRx").start();
    }

    @Override
    public void close() {
        mOpen = false;
        for (int i = mResources.size() - 1; i >= 0; i--) {
            try {
                mResources.get(i).close();
            } catch (Exception e) {
                Log.w(TAG, "close: " + e);
            }
        }
        mResources.clear();
    }
}
