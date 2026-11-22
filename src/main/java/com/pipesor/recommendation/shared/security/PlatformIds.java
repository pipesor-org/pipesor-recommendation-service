package com.pipesor.recommendation.shared.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.regex.Pattern;

/** Deterministic identifiers of the platform identity contract v1 (UUIDv5, URL namespace). */
public final class PlatformIds {
    private static final UUID URL_NAMESPACE = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
    private static final Pattern WORKLOAD = Pattern.compile("[a-z][a-z-]{1,31}");

    public static final UUID DASHBOARD_CLIENT = uuid5("urn:pipesor:v1:client:dashboard");
    public static final String SESSION_ISSUER = "urn:pipesor:session";

    private PlatformIds() { }

    public static String requireWorkload(String id) {
        if (id == null || !WORKLOAD.matcher(id).matches()) throw new IllegalArgumentException("Invalid workload id");
        return id;
    }

    public static UUID workload(String id) { return uuid5("urn:pipesor:v1:workload:" + requireWorkload(id)); }

    public static String workloadIssuer(String id) { return "urn:pipesor:workload:" + requireWorkload(id); }

    public static String audience(String id) { return "urn:pipesor:api:" + requireWorkload(id); }

    public static UUID human(long githubUserId) {
        if (githubUserId < 1) throw new IllegalArgumentException("Invalid GitHub user id");
        return uuid5("urn:pipesor:v1:github-user:" + githubUserId);
    }

    static UUID uuid5(String name) {
        try {
            var sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(ByteBuffer.allocate(16).putLong(URL_NAMESPACE.getMostSignificantBits())
                    .putLong(URL_NAMESPACE.getLeastSignificantBits()).array());
            byte[] hash = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            var buffer = ByteBuffer.wrap(hash, 0, 16);
            return new UUID(buffer.getLong(), buffer.getLong());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}
