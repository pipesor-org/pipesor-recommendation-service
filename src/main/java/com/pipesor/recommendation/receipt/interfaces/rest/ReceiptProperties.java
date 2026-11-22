package com.pipesor.recommendation.receipt.interfaces.rest;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Request bound for the correlated-context route (matches Correlation's delivery limit by default). */
@ConfigurationProperties("pipesor.receipt")
public record ReceiptProperties(Integer maxRequestBytes) {
    public ReceiptProperties {
        maxRequestBytes = maxRequestBytes == null ? 262_144 : maxRequestBytes;
        if (maxRequestBytes < 1024 || maxRequestBytes > 1_048_576) {
            throw new IllegalArgumentException("pipesor.receipt.max-request-bytes must be between 1 KiB and 1 MiB");
        }
    }
}
