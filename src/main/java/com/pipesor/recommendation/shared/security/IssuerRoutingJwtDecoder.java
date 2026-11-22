package com.pipesor.recommendation.shared.security;

import com.nimbusds.jwt.JWTParser;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.text.ParseException;
import java.util.Objects;

/**
 * Sends GitHub Actions OIDC tokens to their own decoder and every other token to the platform
 * decoder unchanged. Each decoder still verifies signature and claims; routes still require the
 * authority of their own token kind.
 */
public final class IssuerRoutingJwtDecoder implements JwtDecoder {
    private final JwtDecoder platform;
    private final JwtDecoder gitHubActions;

    public IssuerRoutingJwtDecoder(JwtDecoder platform, JwtDecoder gitHubActions) {
        this.platform = Objects.requireNonNull(platform);
        this.gitHubActions = Objects.requireNonNull(gitHubActions);
    }

    @Override public Jwt decode(String token) throws JwtException {
        final String issuer;
        try {
            issuer = JWTParser.parse(token).getJWTClaimsSet().getIssuer();
        } catch (ParseException | RuntimeException malformed) {
            throw new BadJwtException("Malformed token");
        }
        return GitHubActionsJwtDecoder.ISSUER.equals(issuer) ? gitHubActions.decode(token) : platform.decode(token);
    }
}
