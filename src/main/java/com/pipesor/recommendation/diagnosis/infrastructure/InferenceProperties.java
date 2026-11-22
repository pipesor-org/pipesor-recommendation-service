package com.pipesor.recommendation.diagnosis.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * Azure OpenAI (Foundry) chat inference settings. The endpoint is the account origin
 * ({@code https://<resource>.services.ai.azure.com} or {@code https://<resource>.openai.azure.com});
 * authentication always uses {@code DefaultAzureCredential} with {@code scope}.
 */
@ConfigurationProperties("pipesor.inference")
public record InferenceProperties(URI endpoint, String deployment, String scope, Duration timeout,
        Integer maxCompletionTokens) {
    public InferenceProperties {
        scope = scope == null || scope.isBlank() ? "https://ai.azure.com/.default" : scope;
        timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
        maxCompletionTokens = maxCompletionTokens == null ? 1024 : maxCompletionTokens;
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("pipesor.inference.timeout must be positive and at most 2 minutes");
        }
        if (maxCompletionTokens < 64 || maxCompletionTokens > 16384) {
            throw new IllegalArgumentException("pipesor.inference.max-completion-tokens must be between 64 and 16384");
        }
        if (!scope.matches("https://[A-Za-z0-9.-]+/\\.default")) {
            throw new IllegalArgumentException("pipesor.inference.scope must be an HTTPS resource /.default scope");
        }
    }
}
