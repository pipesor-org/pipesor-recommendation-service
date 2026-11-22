package com.pipesor.recommendation.diagnosis.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Automatic diagnosis worker. The lease must outlast one batch: {@code batchSize} jobs each
 * bounded by the RAG budget plus the inference timeout.
 */
@ConfigurationProperties("pipesor.diagnosis-worker")
public record DiagnosisWorkerProperties(Boolean enabled, Duration pollInterval, Integer batchSize, Duration lease,
        Duration initialBackoff, Duration maxBackoff, Integer maxAttempts) {
    public DiagnosisWorkerProperties {
        enabled = enabled == null || enabled;
        pollInterval = pollInterval == null ? Duration.ofSeconds(5) : pollInterval;
        batchSize = batchSize == null ? 4 : batchSize;
        lease = lease == null ? Duration.ofMinutes(10) : lease;
        initialBackoff = initialBackoff == null ? Duration.ofSeconds(30) : initialBackoff;
        maxBackoff = maxBackoff == null ? Duration.ofMinutes(30) : maxBackoff;
        maxAttempts = maxAttempts == null ? 8 : maxAttempts;
        if (pollInterval.compareTo(Duration.ofMillis(100)) < 0) {
            throw new IllegalArgumentException("pipesor.diagnosis-worker.poll-interval must be at least 100 ms");
        }
    }
}
