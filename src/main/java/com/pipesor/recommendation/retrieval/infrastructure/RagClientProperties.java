package com.pipesor.recommendation.retrieval.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties("pipesor.rag")
public record RagClientProperties(
        URI endpoint,
        @NotBlank @Pattern(regexp = "api://[0-9a-fA-F-]{36}") String audience,
        Duration timeout) {

    public RagClientProperties {
        timeout = timeout == null ? Duration.ofSeconds(15) : timeout;
        if (endpoint == null || !"https".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalArgumentException("pipesor.rag.endpoint must be an HTTPS URI");
        }
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(15)) > 0) {
            throw new IllegalArgumentException("pipesor.rag.timeout must be between 1 ms and 15 seconds");
        }
    }
}
