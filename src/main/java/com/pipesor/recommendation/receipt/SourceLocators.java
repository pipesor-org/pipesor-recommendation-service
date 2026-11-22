package com.pipesor.recommendation.receipt;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Aggregate source locator of protected workflow evidence 2.2 (every 2.1 locator is also valid).
 * The locator is provenance, never authorization; it is compared byte-for-byte in encoded form.
 */
public final class SourceLocators {
    private static final Pattern ENCODED = Pattern.compile(
            "v2:job:[1-9][0-9]{0,19}:member:((?:[A-Za-z0-9._~-]|%[0-9A-F]{2}){1,765}):lines:([1-9][0-9]{0,8})-([1-9][0-9]{0,8})"
                    + ":sha256:[0-9a-f]{64}");
    private static final int MAX_RANGE = 199;

    private SourceLocators() { }

    public static boolean valid(String locator) {
        if (locator == null) return false;
        var match = ENCODED.matcher(locator);
        if (!match.matches()) return false;
        long first = Long.parseLong(match.group(2));
        long last = Long.parseLong(match.group(3));
        if (first > last || last - first > MAX_RANGE) return false;
        String member = decode(match.group(1));
        return member != null && validMember(member);
    }

    private static String decode(String encoded) {
        var bytes = new ByteArrayOutputStream(encoded.length());
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '%') {
                bytes.write(Integer.parseInt(encoded, i + 1, i + 3, 16));
                i += 2;
            } else {
                bytes.write(c);
            }
        }
        byte[] raw = bytes.toByteArray();
        if (raw.length < 1 || raw.length > 255) return null;
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException invalid) {
            return null;
        }
    }

    private static boolean validMember(String member) {
        if (member.startsWith("/") || member.indexOf('\\') >= 0) return false;
        if (member.codePoints().anyMatch(Character::isISOControl)) return false;
        String[] segments = member.split("/", -1);
        if (segments.length > 2) return false;
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals("..")) return false;
        }
        return true;
    }
}
