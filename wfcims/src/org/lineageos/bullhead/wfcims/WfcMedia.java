/*
 * SPDX-FileCopyrightText: 2026 Romain Théry
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.bullhead.wfcims;

import android.content.Context;
import android.net.Network;
import android.os.Handler;
import android.telephony.AccessNetworkConstants.AccessNetworkType;
import android.telephony.imsmedia.AmrParams;
import android.telephony.imsmedia.AudioConfig;
import android.telephony.imsmedia.AudioSessionCallback;
import android.telephony.imsmedia.EvsParams;
import android.telephony.imsmedia.ImsAudioSession;
import android.telephony.imsmedia.ImsMediaManager;
import android.telephony.imsmedia.ImsMediaSession;
import android.telephony.imsmedia.RtcpConfig;
import android.telephony.imsmedia.RtpConfig;
import android.util.Log;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/** One call's audio: an RTP/RTCP socket pair and an ImsMedia audio session. Handler thread only. */
final class WfcMedia {
    private static final String TAG = "WfcIms";

    /** Negotiated audio parameters from the SDP answer. */
    static final class Params {
        InetSocketAddress remote;
        boolean wideband;
        int payloadType;
        int dtmfPayloadType = -1;
        int dtmfRateKHz = 8;
        int modeSet;
        boolean octetAligned;

        boolean sameAs(Params o) {
            return o != null && remote.equals(o.remote) && wideband == o.wideband
                    && payloadType == o.payloadType && dtmfPayloadType == o.dtmfPayloadType
                    && modeSet == o.modeSet && octetAligned == o.octetAligned;
        }

        @Override
        public String toString() {
            return (wideband ? "AMR-WB" : "AMR") + " pt " + payloadType + " modes 0x"
                    + Integer.toHexString(modeSet) + (octetAligned ? " octet-aligned" : "")
                    + " DTMF pt " + dtmfPayloadType + " to " + remote;
        }
    }

    private final Context mContext;
    private final Handler mHandler;
    private final DatagramSocket mRtp;
    private final DatagramSocket mRtcp;
    private ImsMediaManager mManager;
    private boolean mConnected;
    private boolean mOpening;
    private ImsAudioSession mSession;
    private Params mParams;
    private boolean mClosed;

    WfcMedia(Context context, Handler handler, Network network, InetAddress local)
            throws Exception {
        mContext = context;
        mHandler = handler;
        DatagramSocket rtp = null;
        DatagramSocket rtcp = null;
        for (int i = 0; i < 32 && rtcp == null; i++) {
            rtp = new DatagramSocket(null);
            rtp.bind(new InetSocketAddress(local, 0));
            int port = rtp.getLocalPort();
            if (port % 2 == 0) {
                try {
                    rtcp = new DatagramSocket(null);
                    rtcp.bind(new InetSocketAddress(local, port + 1));
                    continue;
                } catch (Exception e) {
                    rtcp.close();
                    rtcp = null;
                }
            }
            rtp.close();
        }
        if (rtcp == null) throw new IllegalStateException("no RTP port pair");
        network.bindSocket(rtp);
        network.bindSocket(rtcp);
        mRtp = rtp;
        mRtcp = rtcp;
    }

    int port() {
        return mRtp.getLocalPort();
    }

    /** Opens the session with these parameters, or modifies it if they changed. */
    void apply(Params p) {
        if (mClosed || p.sameAs(mParams)) return;
        mParams = p;
        Log.i(TAG, "media " + p);
        if (mSession != null) {
            mSession.modifySession(config(p));
        } else if (mManager == null) {
            mManager = new ImsMediaManager(mContext, mHandler::post,
                    new ImsMediaManager.OnConnectedCallback() {
                        @Override
                        public void onConnected() {
                            mConnected = true;
                            open();
                        }

                        @Override
                        public void onDisconnected() {
                            Log.w(TAG, "ImsMedia disconnected");
                            mConnected = false;
                            mSession = null;
                        }
                    });
        } else {
            open();
        }
    }

    private void open() {
        if (mClosed || !mConnected || mOpening || mSession != null || mParams == null) return;
        mOpening = true;
        mManager.openSession(mRtp, mRtcp, ImsMediaSession.SESSION_TYPE_AUDIO, config(mParams),
                mHandler::post, new AudioSessionCallback() {
                    @Override
                    public void onOpenSessionSuccess(ImsMediaSession session) {
                        mOpening = false;
                        mSession = (ImsAudioSession) session;
                        Log.i(TAG, "audio session open");
                        if (mClosed) {
                            mManager.closeSession(mSession);
                            mManager.release();
                        }
                    }

                    @Override
                    public void onOpenSessionFailure(int error) {
                        mOpening = false;
                        Log.w(TAG, "audio session failed: " + error);
                    }

                    @Override
                    public void onFirstMediaPacketReceived(AudioConfig config) {
                        Log.i(TAG, "first media packet received");
                    }
                });
    }

    private static AudioConfig config(Params p) {
        int rate = p.wideband ? 16 : 8;
        int dtmf = p.dtmfPayloadType >= 0 ? p.dtmfPayloadType : (p.wideband ? 101 : 100);
        return new AudioConfig.Builder()
                .setMediaDirection(RtpConfig.MEDIA_DIRECTION_SEND_RECEIVE)
                .setAccessNetwork(AccessNetworkType.IWLAN)
                .setRemoteRtpAddress(p.remote)
                .setRtcpConfig(new RtcpConfig.Builder()
                        .setCanonicalName("wfcims")
                        .setTransmitPort(p.remote.getPort() + 1)
                        .setIntervalSec(5)
                        .setRtcpXrBlockTypes(0)
                        .build())
                .setRxPayloadTypeNumber((byte) p.payloadType)
                .setTxPayloadTypeNumber((byte) p.payloadType)
                .setSamplingRateKHz((byte) rate)
                .setPtimeMillis((byte) 20)
                .setMaxPtimeMillis(240)
                .setDtxEnabled(false)
                .setTxDtmfPayloadTypeNumber((byte) dtmf)
                .setRxDtmfPayloadTypeNumber((byte) dtmf)
                .setDtmfSamplingRateKHz((byte) p.dtmfRateKHz)
                .setCodecType(p.wideband ? AudioConfig.CODEC_AMR_WB : AudioConfig.CODEC_AMR)
                .setAmrParams(new AmrParams.Builder()
                        .setAmrMode(p.modeSet)
                        .setOctetAligned(p.octetAligned)
                        .setMaxRedundancyMillis(0)
                        .build())
                .setEvsParams(new EvsParams.Builder()
                        .setEvsbandwidth(EvsParams.EVS_BAND_NONE)
                        .setEvsMode(EvsParams.EVS_MODE_0)
                        .setChannelAwareMode((byte) 0)
                        .setHeaderFullOnly(false)
                        .setCodecModeRequest((byte) 15)
                        .build())
                .build();
    }

    void sendDtmf(char digit) {
        if (mSession != null) mSession.sendDtmf(digit, 160);
    }

    void startDtmf(char digit) {
        if (mSession != null) mSession.startDtmf(digit);
    }

    void stopDtmf() {
        if (mSession != null) mSession.stopDtmf();
    }

    void close() {
        if (mClosed) return;
        mClosed = true;
        if (mManager != null) {
            if (mSession != null) mManager.closeSession(mSession);
            if (!mOpening) mManager.release();
        }
        mSession = null;
        mRtp.close();
        mRtcp.close();
    }
}
