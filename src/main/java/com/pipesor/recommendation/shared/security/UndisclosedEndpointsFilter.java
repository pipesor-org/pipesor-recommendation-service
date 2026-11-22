package com.pipesor.recommendation.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Spring Security 7 publishes RFC 9728 protected-resource metadata by default. The platform
 * contract allows only health and JWKS without authentication, so the endpoint is not exposed.
 */
public final class UndisclosedEndpointsFilter extends OncePerRequestFilter {
    private static final String METADATA = "/.well-known/oauth-protected-resource";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith(METADATA)) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setHeader("Cache-Control", "no-store");
            return;
        }
        chain.doFilter(request, response);
    }
}
