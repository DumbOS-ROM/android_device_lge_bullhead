/*
 * SPDX-FileCopyrightText: 2026 Romain Théry
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.bullhead.wfcims;

import android.content.Context;
import android.os.Handler;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;
import android.telephony.SmsMessage;
import android.telephony.ims.stub.ImsSmsImplBase;
import android.util.Log;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

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
        final int reference;
        final String callId;
        final Runnable timeout;
        /** RP-SMMA rather than a text: its result goes back through a different callback. */
        final boolean memory;

        Pending(int token, int messageRef, int reference, String callId, Runnable timeout,
                boolean memory) {
            this.token = token;
            this.messageRef = messageRef;
            this.reference = reference;
            this.callId = callId;
            this.timeout = timeout;
            this.memory = memory;
        }
    }

    private final Context mContext;
    private final int mSubId;
    private final Handler mHandler;
    /** Looked up per use: the registrar is replaced whenever the IMS connection changes. */
    private final Supplier<Registrar> mRegistrarSupplier;
    private final AtomicInteger mReference = new AtomicInteger(0);
    private final AtomicInteger mIncomingToken = new AtomicInteger(0);
    /** Keyed by the relay reference we put on the wire. */
    private final Map<Integer, Pending> mPending = new HashMap<>();
    /** A delivery waiting for the platform to tell us what to answer, and where to answer it. */
    private static final class Delivery {
        final int reference;
        final String target;
        final String callId;

        Delivery(int reference, String target, String callId) {
            this.reference = reference;
            this.target = target;
            this.callId = callId;
        }
    }

    private final Map<Integer, Delivery> mToAcknowledge = new HashMap<>();
    /** SIP transactions already handled, newest last, so a retransmission is not a second SMS. */
    private final Set<String> mSeen = Collections.newSetFromMap(
            new LinkedHashMap<String, Boolean>(32, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 64;
                }
            });

    WfcSms(Context context, int subId, Handler handler, Supplier<Registrar> registrar) {
        mContext = context;
        mSubId = subId;
        mHandler = handler;
        mRegistrarSupplier = registrar;
    }

    private Registrar registrar() {
        return mRegistrarSupplier.get();
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
        Registrar reg = registrar();
        if (reg == null || !reg.isRegistered()) {
            // Nothing to send over; the platform will try the cellular path instead.
            fallback(token, messageRef);
            return;
        }
        int reference = mReference.incrementAndGet() & 0xff;
        byte[] address = WfcSmsRp.hexToBytes(smsc);
        boolean usable = address != null && address.length >= 2
                && (address[0] & 0xff) + 1 <= address.length;
        byte[] rp;
        String target;
        if (usable) {
            rp = WfcSmsRp.encodeData(reference, address, pdu);
            target = serviceCentreUri(address);
        } else {
            // Apps rarely name a service centre, so fall back to the one on the SIM.
            String number = simServiceCentre();
            if (number == null) {
                Log.w(TAG, "no service centre address for this message");
                fallback(token, messageRef);
                return;
            }
            rp = WfcSmsRp.encodeData(reference, number, pdu);
            target = "tel:" + number;
        }
        String callId = reg.sendSms(rp, target, null);
        if (callId == null) {
            fallback(token, messageRef);
            return;
        }
        track(token, messageRef, reference, callId, false);
        Log.i(TAG, "SMS sent over Wi-Fi, reference " + reference);
    }

    /** Tells the service centre the phone can take messages again after refusing one as full. */
    @Override
    public void onMemoryAvailable(int token) {
        mHandler.post(() -> {
            Registrar reg = registrar();
            String number = simServiceCentre();
            if (reg == null || !reg.isRegistered() || number == null) {
                memoryResult(token, SEND_STATUS_ERROR, RESULT_NO_NETWORK_ERROR);
                return;
            }
            int reference = mReference.incrementAndGet() & 0xff;
            String callId = reg.sendSms(WfcSmsRp.encodeMemoryAvailable(reference),
                    "tel:" + number, null);
            if (callId == null) {
                memoryResult(token, SEND_STATUS_ERROR, RESULT_NO_NETWORK_ERROR);
                return;
            }
            track(token, 0, reference, callId, true);
            Log.i(TAG, "memory available sent over Wi-Fi, reference " + reference);
        });
    }

    private void track(int token, int messageRef, int reference, String callId, boolean memory) {
        Runnable timeout = () -> {
            Pending p = mPending.remove(reference);
            if (p != null) {
                Log.w(TAG, "no answer to reference " + reference);
                unanswered(p);
            }
        };
        mPending.put(reference,
                new Pending(token, messageRef, reference, callId, timeout, memory));
        mHandler.postDelayed(timeout, SEND_TIMEOUT_MS);
    }

    /** A SIP MESSAGE carrying SMS arrived; returns true when it was ours to handle. */
    boolean onMessage(SecAgreeChannel channel, SipMessage msg, SecAgreeChannel.Source from) {
        String type = msg.header("Content-Type");
        if (type == null) type = msg.header("c");
        if (type == null || !type.toLowerCase().startsWith(CONTENT_TYPE)) return false;
        // Only the P-CSCF may hand us a message. After the security association is installed the
        // channel proves that by itself, but until then anything on the IMS network could claim
        // to be an SMS, and the platform would show it as one.
        if (channel != registrar().channel() || !channel.isProtected()) {
            Log.w(TAG, "refusing an SMS that arrived unprotected");
            registrar().answer(channel, msg, from, 403, "Forbidden");
            return true;
        }
        WfcSmsRp.Incoming in = WfcSmsRp.decode(msg.bodyBytes);
        // Answer the SIP transaction whatever the body turns out to be.
        registrar().answer(channel, msg, from, 200, "OK");
        // The network retransmits a request until a response reaches it, and over UDP that
        // happens often enough to show the same message twice.
        String transaction = msg.header("Call-ID") + " " + msg.header("CSeq");
        if (!mSeen.add(transaction)) {
            Log.i(TAG, "a message we already handled arrived again");
            return true;
        }
        switch (in.type) {
            case WfcSmsRp.RP_DATA_N_TO_MS: {
                int token = mIncomingToken.incrementAndGet();
                // TP-MTI 2 is a status report for a message we sent, which has its own callback.
                boolean report = in.tpdu.length > 0 && (in.tpdu[0] & 0x03) == 0x02;
                String sender = SipMessage.uri(msg.header("P-Asserted-Identity"));
                if (sender == null) sender = SipMessage.uri(msg.header("From"));
                mToAcknowledge.put(token, new Delivery(in.reference, sender,
                        msg.header("Call-ID")));
                Log.i(TAG, (report ? "status report" : "SMS") + " received over Wi-Fi, reference "
                        + in.reference);
                // The platform parses an over-the-air PDU, which starts with the service centre
                // address; the relay protocol carries it separately, as the originator.
                byte[] pdu = new byte[in.originator.length + in.tpdu.length];
                System.arraycopy(in.originator, 0, pdu, 0, in.originator.length);
                System.arraycopy(in.tpdu, 0, pdu, in.originator.length, in.tpdu.length);
                try {
                    if (report) {
                        onSmsStatusReportReceived(token, SmsMessage.FORMAT_3GPP, pdu);
                    } else {
                        onSmsReceived(token, SmsMessage.FORMAT_3GPP, pdu);
                    }
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
                    if (p.memory) {
                        memoryResult(p.token, SEND_STATUS_OK, RESULT_NO_NETWORK_ERROR);
                    } else {
                        try {
                            onSendSmsResultSuccess(p.token, p.messageRef);
                        } catch (RuntimeException e) {
                            Log.w(TAG, "reporting a sent message: " + e);
                        }
                    }
                }
                return true;
            }
            case WfcSmsRp.RP_ERROR_N_TO_MS: {
                Pending p = mPending.remove(in.reference);
                Log.w(TAG, "the network refused reference " + in.reference + ", cause " + in.cause);
                if (p != null) {
                    mHandler.removeCallbacks(p.timeout);
                    if (p.memory) {
                        memoryResult(p.token, SEND_STATUS_ERROR, in.cause);
                    } else {
                        fail(p.token, p.messageRef, SmsManager.RESULT_ERROR_GENERIC_FAILURE);
                    }
                }
                return true;
            }
            default:
                Log.w(TAG, "an SMS body arrived that could not be read");
                return true;
        }
    }

    /** A final response to a MESSAGE we sent; the relay acknowledgement comes separately. */
    void onResponse(SipMessage msg) {
        if (!"MESSAGE".equalsIgnoreCase(msg.method())) return;
        int code = msg.code();
        if (code < 300) return;
        String callId = msg.header("Call-ID");
        for (Pending p : new java.util.ArrayList<>(mPending.values())) {
            if (!p.callId.equals(callId)) continue;
            mPending.remove(p.reference);
            mHandler.removeCallbacks(p.timeout);
            Log.w(TAG, "the network rejected our message: " + msg.startLine);
            unanswered(p);
            return;
        }
    }

    /** The service centre stored on the SIM, or null. */
    private String simServiceCentre() {
        try {
            SmsManager sms = mContext.getSystemService(SmsManager.class);
            if (SubscriptionManager.isValidSubscriptionId(mSubId)) {
                sms = sms.createForSubscriptionId(mSubId);
            }
            return sms.getSmscAddress();
        } catch (RuntimeException e) {
            Log.w(TAG, "reading the service centre from the SIM: " + e);
            return null;
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
                            result == DELIVER_STATUS_ERROR_NO_MEMORY ? 22 : 111);
            registrar().sendSms(rp, d.target, d.callId);
        });
    }

    @Override
    public void acknowledgeSmsReport(int token, int messageRef, int result) {
        mHandler.post(() -> {
            Delivery d = mToAcknowledge.remove(token);
            if (d == null) return;
            byte[] rp = result == STATUS_REPORT_STATUS_OK
                    ? WfcSmsRp.encodeAck(d.reference)
                    : WfcSmsRp.encodeError(d.reference, 111);
            registrar().sendSms(rp, d.target, d.callId);
        });
    }

    /** A message the network never settled: a text goes back to the cellular path. */
    private void unanswered(Pending p) {
        if (p.memory) {
            memoryResult(p.token, SEND_STATUS_ERROR, RESULT_NO_NETWORK_ERROR);
        } else {
            fallback(p.token, p.messageRef);
        }
    }

    private void memoryResult(int token, int status, int cause) {
        try {
            onMemoryAvailableResult(token, status, cause);
        } catch (RuntimeException e) {
            Log.w(TAG, "reporting memory available: " + e);
        }
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
