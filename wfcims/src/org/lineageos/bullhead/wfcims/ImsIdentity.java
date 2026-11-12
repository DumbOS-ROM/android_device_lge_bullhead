/*
 * SPDX-FileCopyrightText: 2026 Romain Théry
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.bullhead.wfcims;

import android.content.Context;
import android.telephony.TelephonyManager;
import android.util.Base64;
import android.util.Log;

/** IMS identities derived from the USIM (no ISIM on the cards this was written for). */
final class ImsIdentity {
    private static final String TAG = "WfcIms";

    final int subId;
    final String user;
    final String domain;
    final String impi;
    final String impu;
    private final TelephonyManager mTm;

    ImsIdentity(Context context, int subId) {
        this.subId = subId;
        mTm = context.getSystemService(TelephonyManager.class).createForSubscriptionId(subId);
        String imsi = mTm.getSubscriberId();
        String mccMnc = mTm.getSimOperator();
        String mnc = mccMnc.substring(3);
        if (mnc.length() == 2) mnc = "0" + mnc;
        domain = "ims.mnc" + mnc + ".mcc" + mccMnc.substring(0, 3) + ".3gppnetwork.org";
        user = imsi;
        impi = imsi + "@" + domain;
        impu = "sip:" + imsi + "@" + domain;
    }

    /** Runs USIM AKA on a SIP AKAv1 nonce. Returns {RES, CK, IK}, or null on failure. */
    byte[][] aka(String nonce) {
        byte[] n = Base64.decode(nonce, Base64.DEFAULT);
        if (n.length < 32) return null;
        byte[] in = new byte[34];
        in[0] = 16;
        System.arraycopy(n, 0, in, 1, 16);
        in[17] = 16;
        System.arraycopy(n, 16, in, 18, 16);
        String r = mTm.getIccAuthentication(TelephonyManager.APPTYPE_USIM,
                TelephonyManager.AUTHTYPE_EAP_AKA, Base64.encodeToString(in, Base64.NO_WRAP));
        if (r == null) {
            Log.w(TAG, "USIM gave no AKA answer");
            return null;
        }
        byte[] a = Base64.decode(r, Base64.DEFAULT);
        if ((a[0] & 0xff) != 0xdb) {
            Log.w(TAG, "USIM AKA tag 0x" + Integer.toHexString(a[0] & 0xff));
            return null;
        }
        byte[][] out = new byte[3][];
        int p = 1;
        for (int i = 0; i < 3; i++) {
            out[i] = new byte[a[p] & 0xff];
            System.arraycopy(a, p + 1, out[i], 0, out[i].length);
            p += 1 + out[i].length;
        }
        return out;
    }
}
