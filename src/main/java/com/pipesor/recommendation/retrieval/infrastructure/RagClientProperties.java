package com.pipesor.recommendation.retrieval.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Total RAG request budget (ADR-021): every attempt and retry wait fits inside {@code timeout}. */
@ConfigurationProperties("pipesor.rag")
public record RagClientProperties(Duration timeout) {
    public RagClientProperties {
        timeout = timeout == null ? Duration.ofSeconds(15) : timeout;
        if (timeout.compareTo(Duration.ofMillis(3)) < 0 || timeout.compareTo(Duration.ofSeconds(15)) > 0) {
            throw new IllegalArgumentException("pipesor.rag.timeout must be between 3 ms and 15 seconds");
        }
    }
}
