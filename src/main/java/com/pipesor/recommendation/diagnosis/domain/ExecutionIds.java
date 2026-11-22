package com.pipesor.recommendation.diagnosis.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Pipeline's public deterministic execution identity (PD-031): UUIDv5, URL namespace, name
 * {@code urn:pipesor:v1:execution:github:<repositoryId>:<runId>:<runAttempt>}.
 */
public final class ExecutionIds {
    private static final UUID URL_NAMESPACE = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
    private static final String DECIMAL = "[1-9][0-9]{0,19}";

    private ExecutionIds() { }

    public static UUID githubExecution(String repositoryId, String runId, String runAttempt) {
        if (repositoryId == null || runId == null || runAttempt == null || !repositoryId.matches(DECIMAL)
                || !runId.matches(DECIMAL) || !runAttempt.matches("[1-9][0-9]{0,8}")) {
            throw new IllegalArgumentException("Canonical positive decimal GitHub identifiers are required");
        }
        return uuid5("urn:pipesor:v1:execution:github:" + repositoryId + ":" + runId + ":" + runAttempt);
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
