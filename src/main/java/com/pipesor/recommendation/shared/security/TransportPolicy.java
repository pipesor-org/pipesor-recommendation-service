package com.pipesor.recommendation.shared.security;

import java.net.URI;
import java.util.Set;

/**
 * Transport rule of the platform identity contract: HTTPS everywhere; plain HTTP only to
 * loopback hosts and only when the {@code local} profile is active.
 */
public record TransportPolicy(boolean loopbackHttpAllowed) {
    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "localhost", "[::1]", "::1");

    public static TransportPolicy forProfiles(String... activeProfiles) {
        return new TransportPolicy(Set.of(activeProfiles).contains("local"));
    }

    /** Returns the URI when it is an allowed absolute base or endpoint URI; fails otherwise. */
    public URI require(URI uri) {
        if (uri == null || !uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("An absolute endpoint URI without credentials, query or fragment is required");
        }
        if ("https".equals(uri.getScheme())) return uri;
        if ("http".equals(uri.getScheme()) && loopbackHttpAllowed && LOOPBACK.contains(uri.getHost())) return uri;
        throw new IllegalArgumentException("HTTPS is required for " + uri.getHost());
    }

    public URI resolve(URI base, String path) {
        var root = require(base);
        String prefix = root.getPath() == null ? "" : root.getPath().replaceAll("/+$", "");
        return require(root.resolve(prefix + path));
    }
}
