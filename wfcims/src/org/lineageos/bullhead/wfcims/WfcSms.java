/*
 * SPDX-FileCopyrightText: 2026 Romain Théry
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.bullhead.wfcims;

import android.os.Handler;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.telephony.ims.stub.ImsSmsImplBase;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SMS over the IMS connection (TS 24.341): the transport PDU travels inside a SIP MESSAGE whose
 * body is a relay-protocol message. Used over Wi-Fi, where there is no cellular path for SMS.
 */
final class WfcSms extends ImsSmsImplBase {
    private static final String TAG = "WfcIms";
    static final String CONTENT_TYPE = "application/vnd.3gpp.sms";
    private static final long SEND_TIMEOUT_MS = 60_000;

    /** A message we sent and are waiting for the network to acknowledge. */
    private static final class Pending {
        final int token;
        final int messageRef;
        final Runnable timeout;

        Pending(int token, int messageRef, Runnable timeout) {
            this.token = token;
            this.messageRef = messageRef;
            this.timeout = timeout;
        }
    }

    private final Handler mHandler;
    private final Registrar mRegistrar;
    private final AtomicInteger mReference = new AtomicInteger(0);
    private final AtomicInteger mIncomingToken = new AtomicInteger(0);
    /** Keyed by the relay reference we put on the wire. */
    private final Map<Integer, Pending> mPending = new HashMap<>();
    /** A delivery waiting for the platform to tell us what to answer, and where to answer it. */
    private static final class Delivery {
        final int reference;
        final String target;

        Delivery(int reference, String target) {
            this.reference = reference;
            this.target = target;
        }
    }

    private final Map<Integer, Delivery> mToAcknowledge = new HashMap<>();

    WfcSms(Handler handler, Registrar registrar) {
        mHandler = handler;
        mRegistrar = registrar;
    }

    @Override
    public String getSmsFormat() {
        return SmsMessage.FORMAT_3GPP;
    }

    @Override
    public void sendSms(int token, int messageRef, String format, String smsc, boolean isRetry,
            byte[] pdu) {
        mHandler.post(() -> doSend(token, messageRef, format, smsc, pdu));
    }

    private void doSend(int token, int messageRef, String format, String smsc, byte[] pdu) {
        if (!SmsMessage.FORMAT_3GPP.equals(format)) {
            Log.w(TAG, "SMS format " + format + " is not carried over IMS here");
            fail(token, messageRef, SmsManager.RESULT_ERROR_GENERIC_FAILURE);
            return;
        }
        if (!mRegistrar.isRegistered()) {
            // Nothing to send over; the platform will try the cellular path instead.
            fallback(token, messageRef);
            return;
        }
        byte[] address = WfcSmsRp.hexToBytes(smsc);
        if (address == null || address.length < 2 || (address[0] & 0xff) + 1 > address.length) {
            Log.w(TAG, "no usable service centre address for this message");
            fallback(token, messageRef);
            return;
        }
        int reference = mReference.incrementAndGet() & 0xff;
        byte[] rp = WfcSmsRp.encodeData(reference, address, pdu);
        if (!mRegistrar.sendSms(rp, serviceCentreUri(address))) {
            fallback(token, messageRef);
            return;
        }
        Runnable timeout = () -> {
            if (mPending.remove(reference) != null) {
                Log.w(TAG, "no answer to the message we sent");
                fail(token, messageRef, SmsManager.RESULT_ERROR_NO_SERVICE);
            }
        };
        mPending.put(reference, new Pending(token, messageRef, timeout));
        mHandler.postDelayed(timeout, SEND_TIMEOUT_MS);
        Log.i(TAG, "SMS sent over Wi-Fi, reference " + reference);
    }

    /** A SIP MESSAGE carrying SMS arrived; returns true when it was ours to handle. */
    boolean onMessage(SecAgreeChannel channel, SipMessage msg, SecAgreeChannel.Source from) {
        String type = msg.header("Content-Type");
        if (type == null || !type.toLowerCase().startsWith(CONTENT_TYPE)) return false;
        // Only the P-CSCF may hand us a message. After the security association is installed the
        // channel proves that by itself, but until then anything on the IMS network could claim
        // to be an SMS, and the platform would show it as one.
        if (channel != mRegistrar.channel() || !channel.isProtected()) {
            Log.w(TAG, "refusing an SMS that arrived unprotected");
            mRegistrar.answer(channel, msg, from, 403, "Forbidden");
            return true;
        }
        WfcSmsRp.Incoming in = WfcSmsRp.decode(msg.bodyBytes);
        // Answer the SIP transaction whatever the body turns out to be.
        mRegistrar.answer(channel, msg, from, 200, "OK");
        switch (in.type) {
            case WfcSmsRp.RP_DATA_N_TO_MS: {
                int token = mIncomingToken.incrementAndGet();
                String sender = SipMessage.uri(msg.header("P-Asserted-Identity"));
                if (sender == null) sender = SipMessage.uri(msg.header("From"));
                mToAcknowledge.put(token, new Delivery(in.reference, sender));
                Log.i(TAG, "SMS received over Wi-Fi, reference " + in.reference);
                try {
                    onSmsReceived(token, SmsMessage.FORMAT_3GPP, in.tpdu);
                } catch (RuntimeException e) {
                    Log.w(TAG, "handing the message to the platform: " + e);
                    mToAcknowledge.remove(token);
                }
                return true;
            }
            case WfcSmsRp.RP_ACK_N_TO_MS: {
                Pending p = mPending.remove(in.reference);
                if (p != null) {
                    mHandler.removeCallbacks(p.timeout);
                    Log.i(TAG, "the network acknowledged reference " + in.reference);
                    onSendSmsResultSuccess(p.token, p.messageRef);
                }
                return true;
            }
            case WfcSmsRp.RP_ERROR_N_TO_MS: {
                Pending p = mPending.remove(in.reference);
                Log.w(TAG, "the network refused reference " + in.reference + ", cause " + in.cause);
                if (p != null) {
                    mHandler.removeCallbacks(p.timeout);
                    fail(p.token, p.messageRef, SmsManager.RESULT_ERROR_GENERIC_FAILURE);
                }
                return true;
            }
            default:
                Log.w(TAG, "an SMS body arrived that could not be read");
                return true;
        }
    }

    @Override
    public void acknowledgeSms(int token, int messageRef, int result) {
        mHandler.post(() -> {
            Delivery d = mToAcknowledge.remove(token);
            if (d == null) return;
            byte[] rp = result == DELIVER_STATUS_OK
                    ? WfcSmsRp.encodeAck(d.reference)
                    : WfcSmsRp.encodeError(d.reference,
                            result == DELIVER_STATUS_ERROR_NO_MEMORY ? 22 : 21);
            mRegistrar.sendSms(rp, d.target);
        });
    }

    @Override
    public void acknowledgeSmsReport(int token, int messageRef, int result) {
        mHandler.post(() -> {
            Delivery d = mToAcknowledge.remove(token);
            if (d != null) mRegistrar.sendSms(WfcSmsRp.encodeAck(d.reference), d.target);
        });
    }

    private void fail(int token, int messageRef, int reason) {
        try {
            onSendSmsResultError(token, messageRef, SEND_STATUS_ERROR, reason, 0);
        } catch (RuntimeException e) {
            Log.w(TAG, "reporting a failed message: " + e);
        }
    }

    /** Hands the message back so the platform sends it over the cellular network instead. */
    private void fallback(int token, int messageRef) {
        try {
            onSendSmsResultError(token, messageRef, SEND_STATUS_ERROR_FALLBACK,
                    SmsManager.RESULT_ERROR_NO_SERVICE, 0);
        } catch (RuntimeException e) {
            Log.w(TAG, "asking for the cellular path: " + e);
        }
    }

    /** The service centre as a dialling URI, read back from the address field. */
    private static String serviceCentreUri(byte[] addressField) {
        StringBuilder digits = new StringBuilder();
        int type = addressField[1] & 0xff;
        if ((type & 0x70) == 0x10) digits.append('+');
        for (int i = 2; i < (addressField[0] & 0xff) + 1 && i < addressField.length; i++) {
            int b = addressField[i] & 0xff;
            digits.append(digit(b & 0x0f));
            if ((b >> 4) != 0x0f) digits.append(digit(b >> 4));
        }
        return "tel:" + digits;
    }

    private static char digit(int v) {
        switch (v) {
            case 0x0a: return '*';
            case 0x0b: return '#';
            default: return (char) ('0' + v);
        }
    }
}
