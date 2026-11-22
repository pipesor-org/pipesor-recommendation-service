package com.pipesor.recommendation.shared.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps verified platform tokens to authorities: workload tokens yield {@code workload:<id>} and
 * {@code role:<role>}; session tokens yield {@code SCOPE_<scope>} and {@code session}; GitHub Actions
 * OIDC tokens yield only {@code github-actions}.
 */
public final class PlatformAuthorities implements Converter<Jwt, AbstractAuthenticationToken> {
    public static final String GITHUB_ACTIONS = "github-actions";

    private PlatformAuthorities() { }

    public static PlatformAuthorities converter() { return new PlatformAuthorities(); }

    @Override public AbstractAuthenticationToken convert(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        String issuer = jwt.getClaimAsString("iss");
        if (PlatformIds.SESSION_ISSUER.equals(issuer)) {
            authorities.add(new SimpleGrantedAuthority("session"));
            for (String scope : jwt.getClaimAsString("scp").split(" ")) {
                if (!scope.isBlank()) authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope));
            }
        } else if (issuer != null && issuer.startsWith("urn:pipesor:workload:")) {
            authorities.add(new SimpleGrantedAuthority("workload:" + issuer.substring("urn:pipesor:workload:".length())));
            for (String role : jwt.getClaimAsStringList("roles")) authorities.add(new SimpleGrantedAuthority("role:" + role));
        } else if (GitHubActionsJwtDecoder.ISSUER.equals(issuer)) {
            authorities.add(new SimpleGrantedAuthority(GITHUB_ACTIONS));
        }
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    /** Route rule: only the named caller workload holding the named role. */
    public static AuthorizationManager<RequestAuthorizationContext> workload(String id, String role) {
        return AuthorizationManagers.allOf(AuthorityAuthorizationManager.hasAuthority("workload:" + PlatformIds.requireWorkload(id)),
                AuthorityAuthorizationManager.hasAuthority("role:" + role));
    }

    /** Route rule: a verified GitHub Actions OIDC token. */
    public static AuthorizationManager<RequestAuthorizationContext> gitHubActions() {
        return AuthorityAuthorizationManager.hasAuthority(GITHUB_ACTIONS);
    }

    /** Route rule: a human session holding the delegated scope. */
    public static AuthorizationManager<RequestAuthorizationContext> session(String scope) {
        return AuthorizationManagers.allOf(AuthorityAuthorizationManager.hasAuthority("session"),
                AuthorityAuthorizationManager.hasAuthority("SCOPE_" + scope));
    }
}
