package com.pipesor.recommendation.diagnosis.infrastructure.pipeline;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Bound for one entitlement exchange with Pipeline (whole exchange, body included). */
@ConfigurationProperties("pipesor.pipeline")
public record PipelineClientProperties(Duration timeout) {
    public PipelineClientProperties {
        timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
    }
}
