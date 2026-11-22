package com.pipesor.recommendation.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

/** Outbound base URLs per target workload ({@code PIPESOR_ENDPOINTS_<TARGET>}, runtime manifest). */
@ConfigurationProperties("pipesor.endpoints")
public record ServiceEndpoints(URI rag, URI pipeline) {
    public ServiceEndpoints {
        if (rag == null) throw new IllegalArgumentException("pipesor.endpoints.rag (PIPESOR_ENDPOINTS_RAG) is required");
        if (pipeline == null) {
            throw new IllegalArgumentException("pipesor.endpoints.pipeline (PIPESOR_ENDPOINTS_PIPELINE) is required");
        }
    }
}
