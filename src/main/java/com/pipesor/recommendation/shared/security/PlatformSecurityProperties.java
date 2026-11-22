package com.pipesor.recommendation.shared.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Platform identity settings (runtime manifest, "Common settings"). */
@ConfigurationProperties("pipesor")
public record PlatformSecurityProperties(String workloadId, UUID tenantId, Path signingKeyFile, String signingKeyId,
        Security security) {

    public record Security(String audience, Duration clockSkew, List<TrustedWorkload> trustedWorkloads, Session session) {
        public Security {
            clockSkew = clockSkew == null ? Duration.ofSeconds(60) : clockSkew;
            trustedWorkloads = trustedWorkloads == null ? List.of() : List.copyOf(trustedWorkloads);
            if (clockSkew.isNegative() || clockSkew.compareTo(Duration.ofSeconds(60)) > 0) {
                throw new IllegalArgumentException("Clock skew must be between 0 and 60 seconds");
            }
        }
    }

    public record TrustedWorkload(String id, URI jwksUri, Set<String> roles) {
        public TrustedWorkload {
            PlatformIds.requireWorkload(id);
            if (jwksUri == null || roles == null || roles.isEmpty()) {
                throw new IllegalArgumentException("Trusted workload " + id + " needs a JWKS URI and roles");
            }
            roles = Set.copyOf(roles);
        }
    }

    public record Session(URI jwksUri) { }

    public PlatformSecurityProperties {
        PlatformIds.requireWorkload(workloadId);
        if (tenantId == null || tenantId.equals(new UUID(0, 0))) throw new IllegalArgumentException("pipesor.tenant-id is required");
        if ((signingKeyFile == null) == (signingKeyId == null || signingKeyId.isBlank())) {
            throw new IllegalArgumentException("Exactly one of pipesor.signing-key-file or pipesor.signing-key-id is required");
        }
        if (security == null || !PlatformIds.audience(workloadId).equals(security.audience())) {
            throw new IllegalArgumentException("pipesor.security.audience must be " + PlatformIds.audience(workloadId));
        }
    }

    @Override public String toString() { return "PlatformSecurityProperties[" + workloadId + "]"; }
}
