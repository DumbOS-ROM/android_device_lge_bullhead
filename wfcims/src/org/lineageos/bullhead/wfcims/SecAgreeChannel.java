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
import android.os.Build;
import android.os.Handler;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.FileDescriptor;
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
 * server UDP socket plus a TCP listener on the server port, protected by the four IPsec
 * transport SAs once the P-CSCF has answered. The network switches to TCP for anything over
 * 1300 bytes (RFC 3261 18.1.1), which is every terminating INVITE.
 */
final class SecAgreeChannel implements AutoCloseable {
    private static final String TAG = "WfcIms";
    /**
     * Whole-message tracing. The text carries subscriber identities, so it is off unless someone
     * asks for it on a debuggable build: {@code setprop log.tag.WfcImsSip VERBOSE}.
     */
    private static boolean logSipEnabled() {
        return Build.IS_DEBUGGABLE && Log.isLoggable("WfcImsSip", Log.VERBOSE);
    }
    private static final int MAX_MESSAGE = 65536;

    interface Listener {
        void onMessage(SecAgreeChannel channel, SipMessage msg, Source from);
    }

    /** Where a request came from, and the connection a response must go back on. */
    static final class Source {
        final InetSocketAddress peer;
        private final FileDescriptor mConn;

        private Source(InetSocketAddress peer, FileDescriptor conn) {
            this.peer = peer;
            mConn = conn;
        }

        @Override
        public String toString() {
            return (mConn != null ? "tcp " : "udp ") + peer.getPort();
        }
    }

    final InetAddress local;
    final InetAddress pcscf;
    private final Context mContext;
    private final Handler mHandler;
    private final Listener mListener;
    private final IpSecManager mIpSec;
    private final DatagramSocket mClient;
    private final DatagramSocket mServer;
    private final FileDescriptor mTcpListener;
    private final IpSecManager.SecurityParameterIndex mSpiC;
    private final IpSecManager.SecurityParameterIndex mSpiS;
    private final List<AutoCloseable> mResources = new ArrayList<>();
    private final List<FileDescriptor> mFds = new ArrayList<>();
    private IpSecTransform mServerIn;
    private IpSecTransform mServerOut;
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
        mClient = socket(network, 0);
        FileDescriptor tcp = null;
        DatagramSocket server = null;
        for (int i = 0; i < 8 && server == null; i++) {
            tcp = listener(network);
            int port = ((InetSocketAddress) Os.getsockname(tcp)).getPort();
            try {
                server = socket(network, port);
            } catch (Exception e) {
                Log.w(TAG, "UDP port " + port + " is taken, retrying: " + e);
                closeFd(tcp);
                tcp = null;
            }
        }
        if (server == null) throw new IllegalStateException("no free UDP and TCP port pair");
        mServer = server;
        mTcpListener = tcp;
        mSpiC = keep(mIpSec.allocateSecurityParameterIndex(local));
        mSpiS = keep(mIpSec.allocateSecurityParameterIndex(local));
        startReceiver(mClient, "udp-c");
        startReceiver(mServer, "udp-s");
        startAcceptor();
    }

    private DatagramSocket socket(Network network, int port) throws Exception {
        DatagramSocket s = new DatagramSocket(null);
        try {
            s.bind(new InetSocketAddress(local, port));
        } catch (Exception e) {
            s.close();
            throw e;
        }
        mResources.add(s);
        network.bindSocket(s);
        return s;
    }

    /** A bound, listening TCP socket on an ephemeral port; transforms come later. */
    private FileDescriptor listener(Network network) throws Exception {
        FileDescriptor fd = Os.socket(OsConstants.AF_INET6, OsConstants.SOCK_STREAM, 0);
        try {
            network.bindSocket(fd);
            Os.bind(fd, local, 0);
            Os.listen(fd, 4);
        } catch (Exception e) {
            closeFd(fd);
            throw e;
        }
        synchronized (mFds) {
            mFds.add(fd);
        }
        return fd;
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
        String pPortC = SipMessage.param(securityServer, "port-c");
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
        apply(mClient, IpSecManager.DIRECTION_OUT, transform(local, outC, key));
        apply(mClient, IpSecManager.DIRECTION_IN, transform(pcscf, mSpiC, key));
        mServerOut = transform(local, outS, key);
        mServerIn = transform(pcscf, mSpiS, key);
        apply(mServer, IpSecManager.DIRECTION_OUT, mServerOut);
        apply(mServer, IpSecManager.DIRECTION_IN, mServerIn);
        if (!applyToFd(mTcpListener, "TCP listener")) {
            // Never listen unprotected: without the SAs anyone on the IMS network could inject
            // SIP. UDP still carries the registration, so drop TCP instead of failing outright.
            Log.e(TAG, "no IPsec on the TCP listener, closing it; large requests will not arrive");
            closeFd(mTcpListener);
        }
        mSecurityVerify = securityServer;
        mPcscfPortS = pPortS;
        Log.i(TAG, "sec-agree SAs installed, P-CSCF port-s " + pPortS + " port-c " + pPortC
                + ", UE port-c " + mClient.getLocalPort() + " port-s " + serverPort()
                + " (UDP and TCP)");
    }

    private IpSecTransform transform(InetAddress source, IpSecManager.SecurityParameterIndex spi,
            byte[] key) throws Exception {
        return keep(new IpSecTransform.Builder(mContext)
                .setAuthentication(new IpSecAlgorithm(IpSecAlgorithm.AUTH_HMAC_SHA1, key, 96))
                .buildTransportModeTransform(source, spi));
    }

    private void apply(DatagramSocket s, int direction, IpSecTransform t) throws Exception {
        mIpSec.applyTransportModeTransform(s, direction, t);
    }

    /** Applies the server-port SAs to a TCP socket; accepted sockets inherit, this is a belt. */
    private boolean applyToFd(FileDescriptor fd, String what) {
        if (mServerIn == null || mServerOut == null) return false;
        try {
            mIpSec.applyTransportModeTransform(fd, IpSecManager.DIRECTION_IN, mServerIn);
            mIpSec.applyTransportModeTransform(fd, IpSecManager.DIRECTION_OUT, mServerOut);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "transforms on the " + what + ": " + e);
            return false;
        }
    }

    /** Sends a request from the client socket: port 5060 before protection, port-s after. */
    void sendRequest(String msg) throws Exception {
        byte[] out = msg.getBytes(StandardCharsets.UTF_8);
        mClient.send(new DatagramPacket(out, out.length, new InetSocketAddress(pcscf,
                mPcscfPortS)));
        logSip(">>> udp-c to port " + mPcscfPortS, msg);
    }

    /** Sends a response on the transport its request arrived on. */
    void sendResponse(String msg, Source to) throws Exception {
        byte[] out = msg.getBytes(StandardCharsets.UTF_8);
        if (to.mConn != null) {
            synchronized (to.mConn) {
                Os.write(to.mConn, out, 0, out.length);
            }
            logSip(">>> tcp to port " + to.peer.getPort(), msg);
        } else {
            mServer.send(new DatagramPacket(out, out.length, to.peer));
            logSip(">>> udp-s to port " + to.peer.getPort(), msg);
        }
    }

    private void startReceiver(DatagramSocket s, String name) {
        new Thread(() -> {
            byte[] buf = new byte[MAX_MESSAGE];
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
                deliver(buf, p.getLength(), (InetSocketAddress) p.getSocketAddress(), null, name);
            }
        }, "WfcImsRx-" + name).start();
    }

    private void startAcceptor() {
        new Thread(() -> {
            while (mOpen) {
                FileDescriptor conn;
                InetSocketAddress peer = new InetSocketAddress(0);
                try {
                    conn = Os.accept(mTcpListener, peer);
                } catch (ErrnoException e) {
                    if (mOpen) Log.w(TAG, "accept: " + e);
                    break;
                } catch (Exception e) {
                    if (mOpen) Log.w(TAG, "accept: " + e);
                    continue;
                }
                if (conn == null) continue;
                if (mServerIn == null || !applyToFd(conn, "accepted connection")) {
                    Log.w(TAG, "dropping an unprotected TCP connection from port "
                            + peer.getPort());
                    closeFd(conn);
                    continue;
                }
                synchronized (mFds) {
                    mFds.add(conn);
                }
                Log.i(TAG, "TCP connection from port " + peer.getPort());
                startConnectionReader(conn, peer);
            }
        }, "WfcImsAccept").start();
    }

    private void startConnectionReader(FileDescriptor conn, InetSocketAddress peer) {
        new Thread(() -> {
            byte[] buf = new byte[MAX_MESSAGE];
            int have = 0;
            while (mOpen) {
                int n;
                try {
                    n = Os.read(conn, buf, have, buf.length - have);
                } catch (Exception e) {
                    if (mOpen) Log.i(TAG, "TCP connection closed: " + e);
                    break;
                }
                if (n <= 0) {
                    Log.i(TAG, "TCP connection from port " + peer.getPort() + " ended");
                    break;
                }
                have += n;
                int skip = 0;
                while (skip + 1 < have && buf[skip] == '\r' && buf[skip + 1] == '\n') skip += 2;
                if (skip > 0) {
                    System.arraycopy(buf, skip, buf, 0, have - skip);
                    have -= skip;
                }
                int used = 0;
                while (have > 0 && (used = messageLength(buf, have)) > 0) {
                    deliver(buf, used, peer, conn, "tcp");
                    System.arraycopy(buf, used, buf, 0, have - used);
                    have -= used;
                }
                if (used < 0) {
                    Log.w(TAG, "TCP stream out of sync, closing");
                    break;
                }
                if (have == buf.length) {
                    Log.w(TAG, "TCP message over " + MAX_MESSAGE + " bytes, closing");
                    break;
                }
            }
            closeFd(conn);
        }, "WfcImsTcpRx").start();
    }

    /**
     * Length of the first complete SIP message in the buffer, 0 when more is needed and -1 when
     * the stream cannot be framed. The caller has already dropped leading CRLF keep-alives.
     */
    private static int messageLength(byte[] buf, int len) {
        String text = new String(buf, 0, len, StandardCharsets.UTF_8);
        int head = text.indexOf("\r\n\r\n");
        if (head < 0) return 0;
        int body = 0;
        for (String line : text.substring(0, head).replaceAll("\r\n[ \t]+", " ").split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String name = line.substring(0, colon).trim();
            if (name.equalsIgnoreCase("Content-Length") || name.equals("l")) {
                try {
                    body = Integer.parseInt(line.substring(colon + 1).trim());
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        if (body < 0) return -1;
        int total = head + 4 + body;
        return total <= len ? total : 0;
    }

    private void deliver(byte[] buf, int len, InetSocketAddress peer, FileDescriptor conn,
            String name) {
        SipMessage msg;
        try {
            msg = SipMessage.parse(buf, len);
        } catch (RuntimeException e) {
            Log.w(TAG, "unparsable SIP message dropped on " + name + ": " + e);
            return;
        }
        logSip("<<< " + name + " from port " + peer.getPort(),
                new String(buf, 0, len, StandardCharsets.UTF_8));
        Source from = new Source(peer, conn);
        mHandler.post(() -> {
            if (mOpen) mListener.onMessage(this, msg, from);
        });
    }

    private static void logSip(String what, String text) {
        if (logSipEnabled()) {
            Log.d(TAG, what + " (" + text.getBytes(StandardCharsets.UTF_8).length + " bytes)\n"
                    + text.replaceAll("(?i)(response|cnonce)=\"[^\"]*\"", "$1=\"...\""));
        } else {
            Log.d(TAG, what + " " + text.substring(0, Math.max(0, text.indexOf('\r'))));
        }
    }

    private static void closeFd(FileDescriptor fd) {
        try {
            Os.close(fd);
        } catch (Exception e) {
            Log.w(TAG, "close: " + e);
        }
    }

    @Override
    public void close() {
        mOpen = false;
        List<FileDescriptor> fds;
        synchronized (mFds) {
            fds = new ArrayList<>(mFds);
            mFds.clear();
        }
        for (FileDescriptor fd : fds) closeFd(fd);
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
