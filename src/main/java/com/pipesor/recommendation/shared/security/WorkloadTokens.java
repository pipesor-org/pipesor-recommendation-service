package com.pipesor.recommendation.shared.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Issues short-lived workload tokens (platform identity contract v1, §2) for one target and one role. */
public final class WorkloadTokens {
    static final Duration LIFETIME = Duration.ofMinutes(5);
    private static final Duration RENEW_BEFORE = Duration.ofSeconds(60);

    public record Token(String value, Instant expiresAt) {
        @Override public String toString() { return "WorkloadToken[redacted]"; }
    }

    private record Key(String target, String role) { }

    private final String workload;
    private final UUID tenant;
    private final SigningKey key;
    private final Clock clock;
    private final Map<Key, Token> cache = new ConcurrentHashMap<>();

    public WorkloadTokens(String workload, UUID tenant, SigningKey key, Clock clock) {
        this.workload = PlatformIds.requireWorkload(workload);
        this.tenant = Objects.requireNonNull(tenant);
        this.key = Objects.requireNonNull(key);
        this.clock = Objects.requireNonNull(clock);
    }

    public Token issue(String target, String role) {
        PlatformIds.requireWorkload(target);
        if (role == null || !role.matches("[A-Z][A-Za-z]*(\\.[A-Z][A-Za-z]*)+")) throw new IllegalArgumentException("Invalid role");
        Instant now = clock.instant();
        return cache.compute(new Key(target, role), (ignored, current) ->
                current != null && current.expiresAt().minus(RENEW_BEFORE).isAfter(now) ? current : sign(target, role, now));
    }

    private Token sign(String target, String role, Instant now) {
        Instant expires = now.plus(LIFETIME);
        String subject = PlatformIds.workload(workload).toString();
        var claims = new JWTClaimsSet.Builder()
                .issuer(PlatformIds.workloadIssuer(workload)).audience(PlatformIds.audience(target))
                .subject(subject).claim("oid", subject).claim("azp", subject).claim("tid", tenant.toString())
                .claim("roles", List.of(role)).claim("idtyp", "app").claim("ver", "2.0")
                .issueTime(Date.from(now)).notBeforeTime(Date.from(now)).expirationTime(Date.from(expires))
                .jwtID(UUID.randomUUID().toString()).build();
        var header = new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(key.keyId()).build();
        String signingInput = header.toBase64URL() + "." + Base64URL.encode(claims.toString().getBytes(StandardCharsets.UTF_8));
        String signature = Base64URL.encode(key.sign(signingInput.getBytes(StandardCharsets.US_ASCII))).toString();
        return new Token(signingInput + "." + signature, expires);
    }
}
