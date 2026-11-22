package com.pipesor.recommendation.shared.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

/** GitHub Actions OIDC verifier settings; the issuer and audience are fixed by the identity contract. */
@ConfigurationProperties("pipesor.security.github-actions")
public record GitHubActionsProperties(URI jwksUri) {
    public GitHubActionsProperties {
        jwksUri = jwksUri == null ? GitHubActionsJwtDecoder.DEFAULT_JWKS_URI : jwksUri;
    }
}
