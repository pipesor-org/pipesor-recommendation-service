package com.pipesor.recommendation.shared.security;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Verifies GitHub Actions OIDC tokens (platform identity contract v1, §6). Accepted only by the
 * report route; repository matching against the path is the route's responsibility.
 */
public final class GitHubActionsJwtDecoder implements JwtDecoder {
    public static final String ISSUER = "https://token.actions.githubusercontent.com";
    public static final URI DEFAULT_JWKS_URI = URI.create("https://token.actions.githubusercontent.com/.well-known/jwks");
    static final Duration MAX_LIFETIME = Duration.ofHours(1);
    private static final String EXTERNAL_ID = "[1-9][0-9]{0,19}";

    private final NimbusJwtDecoder delegate;

    public GitHubActionsJwtDecoder(URI jwksUri, String audience, Duration skew, TransportPolicy transport) {
        this(jwksUri, audience, skew, transport, uri -> NimbusJwtDecoder.withJwkSetUri(uri.toString())
                .jwsAlgorithm(SignatureAlgorithm.RS256).build());
    }

    GitHubActionsJwtDecoder(URI jwksUri, String audience, Duration skew, TransportPolicy transport,
            Function<URI, NimbusJwtDecoder> keySets) {
        if (audience == null || audience.isBlank()) throw new IllegalArgumentException("An audience is required");
        delegate = keySets.apply(transport.require(jwksUri));
        delegate.setJwtValidator(new DelegatingOAuth2TokenValidator<>(List.of(
                new JwtTimestampValidator(skew),
                rule("issuer", jwt -> ISSUER.equals(jwt.getClaimAsString("iss"))),
                rule("audience", jwt -> jwt.getAudience() != null && jwt.getAudience().equals(List.of(audience))),
                rule("repository", jwt -> matches(jwt, "repository_id") && matches(jwt, "repository_owner_id")),
                rule("lifetime", jwt -> jwt.getIssuedAt() != null && jwt.getExpiresAt() != null
                        && !jwt.getExpiresAt().isBefore(jwt.getIssuedAt())
                        && Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).compareTo(MAX_LIFETIME) <= 0))));
    }

    @Override public Jwt decode(String token) throws JwtException { return delegate.decode(token); }

    private static boolean matches(Jwt jwt, String claim) {
        String value = jwt.getClaimAsString(claim);
        return value != null && value.matches(EXTERNAL_ID);
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
