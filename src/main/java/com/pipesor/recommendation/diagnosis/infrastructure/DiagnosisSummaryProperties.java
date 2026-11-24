package com.pipesor.recommendation.diagnosis.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Diagnosis summary publisher (ADR-027). Pushes start only during the first half of the lease, so
 * the lease must be at least twice the Pipeline exchange timeout ({@code pipesor.pipeline.timeout}).
 * Failures back off from {@code initialBackoff}, doubling up to {@code maxBackoff}, and keep retrying.
 */
@ConfigurationProperties("pipesor.diagnosis-summary")
public record DiagnosisSummaryProperties(Boolean enabled, Duration pollInterval, Integer batchSize, Duration lease,
        Duration initialBackoff, Duration maxBackoff) {
    public DiagnosisSummaryProperties {
        enabled = enabled == null || enabled;
        pollInterval = pollInterval == null ? Duration.ofSeconds(2) : pollInterval;
        batchSize = batchSize == null ? 20 : batchSize;
        lease = lease == null ? Duration.ofSeconds(30) : lease;
        initialBackoff = initialBackoff == null ? Duration.ofSeconds(2) : initialBackoff;
        maxBackoff = maxBackoff == null ? Duration.ofMinutes(10) : maxBackoff;
        if (pollInterval.compareTo(Duration.ofMillis(100)) < 0) {
            throw new IllegalArgumentException("pipesor.diagnosis-summary.poll-interval must be at least 100 ms");
        }
    }
}
