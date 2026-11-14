/*
 * SPDX-FileCopyrightText: 2026 Romain Théry
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.bullhead.wfcims;

import java.io.ByteArrayOutputStream;

/**
 * The SMS relay protocol of TS 24.011, which carries a transport PDU between the phone and the
 * service centre. Android hands the IMS stack a bare transport PDU and the service centre
 * address, so this layer is ours to build and to read.
 */
final class WfcSmsRp {
    /** Message type indicators, TS 24.011 table 8.3. */
    static final int RP_DATA_MS_TO_N = 0;
    static final int RP_DATA_N_TO_MS = 1;
    static final int RP_ACK_MS_TO_N = 2;
    static final int RP_ACK_N_TO_MS = 3;
    static final int RP_ERROR_MS_TO_N = 4;
    static final int RP_ERROR_N_TO_MS = 5;
    static final int RP_SMMA_MS_TO_N = 6;

    /** What a message from the network turned out to be. */
    static final class Incoming {
        int type = -1;
        int reference;
        /** The transport PDU for RP-DATA, empty otherwise. */
        byte[] tpdu = new byte[0];
        /**
         * The originator address field of an RP-DATA, length octet included. For a delivery it
         * holds the service centre, which the platform's parser expects in front of the PDU.
         */
        byte[] originator = new byte[0];
        /** The cause value for RP-ERROR, otherwise 0. */
        int cause;
    }

    private WfcSmsRp() {
    }

    /**
     * Wraps a transport PDU as RP-DATA for the service centre. The address field is the one the
     * platform hands over, which already carries its own length octet, type of number and digits.
     */
    static byte[] encodeData(int reference, byte[] serviceCentreAddressField, byte[] tpdu) {
        if (tpdu == null) tpdu = new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(RP_DATA_MS_TO_N);
        out.write(reference & 0xff);
        // Originator address is empty from the phone.
        out.write(0);
        out.write(serviceCentreAddressField, 0, serviceCentreAddressField.length);
        out.write(tpdu.length);
        out.write(tpdu, 0, tpdu.length);
        return out.toByteArray();
    }

    /** The same, for a service centre known only as a dialling number. */
    static byte[] encodeData(int reference, String serviceCentre, byte[] tpdu) {
        byte[] address = encodeAddress(serviceCentre);
        byte[] field = new byte[address.length + 1];
        field[0] = (byte) address.length;
        System.arraycopy(address, 0, field, 1, address.length);
        return encodeData(reference, field, tpdu);
    }

    /** Decodes the platform's hex form of the service centre address field. */
    static byte[] hexToBytes(String hex) {
        if (hex == null) return null;
        String clean = hex.trim();
        if (clean.length() < 2 || (clean.length() & 1) != 0) return null;
        byte[] out = new byte[clean.length() / 2];
        try {
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    /** Acknowledges a message the network delivered. */
    static byte[] encodeAck(int reference) {
        return new byte[] {(byte) RP_ACK_MS_TO_N, (byte) (reference & 0xff)};
    }

    /** Tells the network the phone has room again after a memory-full refusal. */
    static byte[] encodeMemoryAvailable(int reference) {
        return new byte[] {(byte) RP_SMMA_MS_TO_N, (byte) (reference & 0xff)};
    }

    /** Refuses a delivery with a cause from TS 24.011 table 8.4; the length counts the cause. */
    static byte[] encodeError(int reference, int cause) {
        return new byte[] {(byte) RP_ERROR_MS_TO_N, (byte) (reference & 0xff), 1,
                (byte) (0x80 | (cause & 0x7f))};
    }

    /** Reads a message from the network; type is -1 when it cannot be understood. */
    static Incoming decode(byte[] rp) {
        Incoming in = new Incoming();
        if (rp == null || rp.length < 2) return in;
        int type = rp[0] & 0x07;
        in.reference = rp[1] & 0xff;
        int p = 2;
        if (type == RP_DATA_N_TO_MS) {
            if (p >= rp.length) return in;
            int originatorLength = rp[p] & 0xff;
            if (p + 1 + originatorLength > rp.length) return in;
            in.originator = new byte[1 + originatorLength];
            System.arraycopy(rp, p, in.originator, 0, in.originator.length);
            p += 1 + originatorLength;
            if (p >= rp.length) return in;
            int destinationLength = rp[p++] & 0xff;
            p += destinationLength;
            if (p >= rp.length) return in;
            int userLength = rp[p++] & 0xff;
            if (p + userLength > rp.length) return in;
            in.tpdu = new byte[userLength];
            System.arraycopy(rp, p, in.tpdu, 0, userLength);
        } else if (type == RP_ERROR_N_TO_MS) {
            if (p + 1 < rp.length) {
                int causeLength = rp[p++] & 0xff;
                if (causeLength > 0 && p < rp.length) in.cause = rp[p] & 0x7f;
            }
        } else if (type != RP_ACK_N_TO_MS) {
            return in;
        }
        in.type = type;
        return in;
    }

    /**
     * An address as TS 24.011 carries it: a type-of-number octet then the digits, two per octet,
     * low nibble first, padded with 0xf.
     */
    private static byte[] encodeAddress(String number) {
        String digits = number == null ? "" : number.replaceAll("[^0-9*#+]", "");
        boolean international = digits.startsWith("+");
        if (international) digits = digits.substring(1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(international ? 0x91 : 0x81);
        for (int i = 0; i < digits.length(); i += 2) {
            int low = digitValue(digits.charAt(i));
            int high = i + 1 < digits.length() ? digitValue(digits.charAt(i + 1)) : 0x0f;
            out.write((high << 4) | low);
        }
        return out.toByteArray();
    }

    private static int digitValue(char c) {
        switch (c) {
            case '*': return 0x0a;
            case '#': return 0x0b;
            default: return c - '0';
        }
    }
}
