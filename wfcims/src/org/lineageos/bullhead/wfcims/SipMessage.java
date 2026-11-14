/*
 * SPDX-FileCopyrightText: 2026 Romain Théry
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.bullhead.wfcims;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A parsed SIP request or response. Header lines are unfolded; names are kept as received. */
final class SipMessage {
    final String startLine;
    final List<String[]> headers = new ArrayList<>();
    final String body;
    /** The body as it arrived; SMS bodies are binary. */
    byte[] bodyBytes = new byte[0];

    private SipMessage(String startLine, String body) {
        this.startLine = startLine;
        this.body = body;
    }

    static SipMessage parse(byte[] data, int length) {
        // Split on bytes: an SMS body is binary and must survive untouched.
        int split = -1;
        for (int i = 0; i + 3 < length; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r'
                    && data[i + 3] == '\n') {
                split = i;
                break;
            }
        }
        String head = new String(data, 0, split < 0 ? length : split, StandardCharsets.UTF_8);
        byte[] rawBody = new byte[split < 0 ? 0 : length - split - 4];
        if (rawBody.length > 0) System.arraycopy(data, split + 4, rawBody, 0, rawBody.length);
        String body = new String(rawBody, StandardCharsets.UTF_8);
        String[] lines = head.replaceAll("\r\n[ \t]+", " ").split("\r\n");
        SipMessage msg = new SipMessage(lines[0], body);
        msg.bodyBytes = rawBody;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                msg.headers.add(new String[] {lines[i].substring(0, colon).trim(),
                        lines[i].substring(colon + 1).trim()});
            }
        }
        return msg;
    }

    boolean isResponse() {
        return startLine.startsWith("SIP/2.0 ");
    }

    int code() {
        return isResponse() ? Integer.parseInt(startLine.substring(8, 11)) : 0;
    }

    String method() {
        if (!isResponse()) return startLine.substring(0, startLine.indexOf(' '));
        String cseq = header("CSeq");
        return cseq == null ? "" : cseq.substring(cseq.indexOf(' ') + 1).trim();
    }

    String header(String name) {
        for (String[] h : headers) {
            if (h[0].equalsIgnoreCase(name)) return h[1];
        }
        return null;
    }

    List<String> headerValues(String name) {
        List<String> out = new ArrayList<>();
        for (String[] h : headers) {
            if (h[0].equalsIgnoreCase(name)) out.add(h[1]);
        }
        return out;
    }

    String branch() {
        String via = header("Via");
        return via == null ? null : param(via, "branch");
    }

    static String param(String value, String name) {
        Matcher m = Pattern.compile("(?:^|[;,\\s])" + Pattern.quote(name)
                + "\\s*=\\s*\"?([^\";,]*)\"?", Pattern.CASE_INSENSITIVE).matcher(value);
        return m.find() ? m.group(1) : null;
    }

    /** First URI between angle brackets, or the value up to the first parameter. */
    static String uri(String value) {
        if (value == null) return null;
        Matcher m = Pattern.compile("<([^>]+)>").matcher(value);
        return m.find() ? m.group(1) : value.split(";")[0].trim();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(startLine).append("\r\n");
        for (String[] h : headers) sb.append(h[0]).append(": ").append(h[1]).append("\r\n");
        return sb.append("\r\n").append(body).toString();
    }

    String summary() {
        return startLine + (isResponse() ? " (" + method().toUpperCase(Locale.ROOT) + ")" : "");
    }
}
