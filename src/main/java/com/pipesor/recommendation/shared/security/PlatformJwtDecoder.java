package com.pipesor.recommendation.shared.security;

import com.nimbusds.jwt.JWTParser;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.net.URI;
import java.text.ParseException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Verifies workload and session tokens of the platform identity contract v1. The issuer selects
 * the trusted key set; every other claim is validated after the signature.
 */
public final class PlatformJwtDecoder implements JwtDecoder {
    static final Duration MAX_WORKLOAD_LIFETIME = Duration.ofMinutes(5);
    static final Duration MAX_SESSION_LIFETIME = Duration.ofHours(1);

    private final Map<String, JwtDecoder> byIssuer;

    public PlatformJwtDecoder(PlatformSecurityProperties settings, TransportPolicy transport) {
        this(settings, transport, uri -> NimbusJwtDecoder.withJwkSetUri(uri.toString())
                .jwsAlgorithm(SignatureAlgorithm.RS256).restOperations(jwksClient()).build());
    }

    PlatformJwtDecoder(PlatformSecurityProperties settings, TransportPolicy transport,
            Function<URI, NimbusJwtDecoder> keySets) {
        Objects.requireNonNull(settings);
        var security = settings.security();
        String tenant = settings.tenantId().toString();
        var decoders = new HashMap<String, JwtDecoder>();
        for (var trusted : security.trustedWorkloads()) {
            var decoder = keySets.apply(transport.require(trusted.jwksUri()));
            decoder.setJwtValidator(workload(trusted, security.audience(), tenant, security.clockSkew()));
            if (decoders.put(PlatformIds.workloadIssuer(trusted.id()), decoder) != null) {
                throw new IllegalArgumentException("Duplicate trusted workload " + trusted.id());
            }
        }
        if (security.session() != null && security.session().jwksUri() != null) {
            var decoder = keySets.apply(transport.require(security.session().jwksUri()));
            decoder.setJwtValidator(session(security.audience(), tenant, security.clockSkew()));
            decoders.put(PlatformIds.SESSION_ISSUER, decoder);
        }
        byIssuer = Map.copyOf(decoders);
    }

    /** Key sets are fetched with short bounds so an unreachable issuer fails a request fast instead of stalling it. */
    static org.springframework.web.client.RestTemplate jwksClient() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofSeconds(2));
        factory.setReadTimeout(java.time.Duration.ofSeconds(3));
        return new org.springframework.web.client.RestTemplate(factory);
    }

    @Override public Jwt decode(String token) throws JwtException {
        final String issuer;
        try {
            issuer = JWTParser.parse(token).getJWTClaimsSet().getIssuer();
        } catch (ParseException | RuntimeException malformed) {
            throw new BadJwtException("Malformed token");
        }
        var decoder = issuer == null ? null : byIssuer.get(issuer);
        if (decoder == null) throw new BadJwtException("Untrusted issuer");
        return decoder.decode(token);
    }

    public boolean acceptsSessions() { return byIssuer.containsKey(PlatformIds.SESSION_ISSUER); }

    private static OAuth2TokenValidator<Jwt> workload(PlatformSecurityProperties.TrustedWorkload trusted,
            String audience, String tenant, Duration skew) {
        String principal = PlatformIds.workload(trusted.id()).toString();
        return new DelegatingOAuth2TokenValidator<>(List.of(
                new JwtTimestampValidator(skew),
                rule("issuer", jwt -> PlatformIds.workloadIssuer(trusted.id()).equals(jwt.getClaimAsString("iss"))),
                rule("audience", jwt -> jwt.getAudience() != null && jwt.getAudience().equals(List.of(audience))),
                rule("tenant", jwt -> tenant.equals(jwt.getClaimAsString("tid"))),
                rule("token type", jwt -> "app".equals(jwt.getClaimAsString("idtyp")) && "2.0".equals(jwt.getClaimAsString("ver"))
                        && !jwt.hasClaim("scp")),
                rule("caller", jwt -> principal.equals(jwt.getClaimAsString("oid")) && principal.equals(jwt.getClaimAsString("azp"))
                        && principal.equals(jwt.getSubject())),
                rule("roles", jwt -> {
                    List<String> roles = jwt.getClaimAsStringList("roles");
                    return roles != null && roles.size() == 1 && trusted.roles().contains(roles.getFirst());
                }),
                rule("lifetime", jwt -> lifetime(jwt, MAX_WORKLOAD_LIFETIME))));
    }

    private static OAuth2TokenValidator<Jwt> session(String audience, String tenant, Duration skew) {
        String dashboard = PlatformIds.DASHBOARD_CLIENT.toString();
        return new DelegatingOAuth2TokenValidator<>(List.of(
                new JwtTimestampValidator(skew),
                rule("issuer", jwt -> PlatformIds.SESSION_ISSUER.equals(jwt.getClaimAsString("iss"))),
                rule("audience", jwt -> jwt.getAudience() != null && jwt.getAudience().contains(audience)),
                rule("tenant", jwt -> tenant.equals(jwt.getClaimAsString("tid"))),
                rule("token type", jwt -> "2.0".equals(jwt.getClaimAsString("ver")) && !jwt.hasClaim("idtyp")
                        && !jwt.hasClaim("roles") && jwt.getClaimAsString("scp") != null),
                rule("client", jwt -> dashboard.equals(jwt.getClaimAsString("azp"))),
                rule("subject", jwt -> uuid(jwt.getClaimAsString("oid")) && Objects.equals(jwt.getSubject(), jwt.getClaimAsString("oid"))
                        && jwt.getClaimAsString("github_user_id") != null
                        && jwt.getClaimAsString("github_user_id").matches("[1-9][0-9]{0,19}")
                        && PlatformIds.human(Long.parseLong(jwt.getClaimAsString("github_user_id"))).toString()
                                .equals(jwt.getClaimAsString("oid"))),
                rule("lifetime", jwt -> lifetime(jwt, MAX_SESSION_LIFETIME))));
    }

    private static boolean lifetime(Jwt jwt, Duration maximum) {
        return jwt.getIssuedAt() != null && jwt.getExpiresAt() != null && jwt.getNotBefore() != null
                && !jwt.getExpiresAt().isBefore(jwt.getIssuedAt())
                && Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).compareTo(maximum) <= 0;
    }

    private static boolean uuid(String value) {
        try {
            return value != null && UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static OAuth2TokenValidator<Jwt> rule(String name, Predicate<Jwt> check) {
        var failure = OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid " + name, null));
        return jwt -> {
            try {
                return check.test(jwt) ? OAuth2TokenValidatorResult.success() : failure;
            } catch (RuntimeException invalid) {
                return failure;
            }
        };
    }
}
