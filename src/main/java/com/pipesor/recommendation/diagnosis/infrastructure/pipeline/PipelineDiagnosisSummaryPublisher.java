package com.pipesor.recommendation.diagnosis.infrastructure.pipeline;

import com.pipesor.recommendation.diagnosis.application.ports.DiagnosisSummaryPublisher;
import com.pipesor.recommendation.diagnosis.application.queries.DiagnosisSummary;
import com.pipesor.recommendation.shared.security.WorkloadTokens;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@code recommendation → pipeline PUT /internal/v1/incidents/{incidentId}/diagnosis-summary}
 * (ADR-027) with a workload token for role {@code Recommendation.DiagnosisSummaries.Write} and no
 * subject token. {@code 200} must echo the incident, diagnosis, version and status that were sent;
 * {@code 400}/{@code 409} are permanent refusals; anything else is retryable.
 */
public final class PipelineDiagnosisSummaryPublisher implements DiagnosisSummaryPublisher {
    public static final String PATH = "/internal/v1/incidents/{incidentId}/diagnosis-summary";
    /** Base resolved once under the transport policy; {@code {incidentId}/diagnosis-summary} is appended per call. */
    public static final String INCIDENTS_PATH = "/internal/v1/incidents/";
    public static final String TARGET = "pipeline";
    public static final String ROLE = "Recommendation.DiagnosisSummaries.Write";
    private static final int MAX_RESPONSE_BYTES = 16 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION).build();

    private final URI incidents;
    private final WorkloadTokens tokens;
    private final HttpClient http;
    private final Duration timeout;

    /** {@code incidents} must already satisfy the platform transport policy and end with {@code /incidents/}. */
    public PipelineDiagnosisSummaryPublisher(URI incidents, WorkloadTokens tokens, Duration timeout) {
        this.incidents = Objects.requireNonNull(incidents);
        if (incidents.getPath() == null || !incidents.getPath().endsWith(INCIDENTS_PATH)) {
            throw new IllegalArgumentException("The diagnosis summary base must end with " + INCIDENTS_PATH);
        }
        this.tokens = Objects.requireNonNull(tokens);
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException("The Pipeline timeout must be positive and at most 10 seconds");
        }
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public Result publish(DiagnosisSummary summary, String correlationId) {
        Objects.requireNonNull(summary);
        HttpResponse<byte[]> response;
        try {
            var builder = HttpRequest.newBuilder(incidents.resolve(summary.incidentId() + "/diagnosis-summary"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + tokens.issue(TARGET, ROLE).value())
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body(summary))));
            if (correlationId != null) builder.header("X-Correlation-ID", correlationId);
            var pending = http.sendAsync(builder.build(), info -> HttpResponse.BodySubscribers.limiting(
                    HttpResponse.BodySubscribers.ofByteArray(), MAX_RESPONSE_BYTES));
            try {
                // Bounds the whole exchange, body included.
                response = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            } finally {
                if (!pending.isDone()) pending.cancel(true);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Failed("pipeline_interrupted", interrupted.getClass().getName());
        } catch (TimeoutException timedOut) {
            return new Failed("pipeline_timeout", timedOut.getClass().getName());
        } catch (Exception failure) {
            var cause = failure.getCause() == null ? failure : failure.getCause();
            return new Failed(cause instanceof HttpTimeoutException ? "pipeline_timeout" : "pipeline_transport_failure",
                    cause.getClass().getName());
        }
        int status = response.statusCode();
        if (status == 400 || status == 409) return new Rejected(status);
        if (status != 200) return new Failed("pipeline_status_" + status, null);
        try {
            var echo = JSON.readTree(response.body());
            if (!echoes(echo, summary)) return new Failed("pipeline_invalid_response", null);
            var applied = echo.path("applied");
            return new Acknowledged(applied.isBoolean() ? applied.asBoolean() : null);
        } catch (RuntimeException invalid) {
            return new Failed("pipeline_invalid_response", invalid.getClass().getName());
        }
    }

    /** Request body of the contract, with explicit nulls for an unreviewed diagnosis or a blank cause. */
    static JsonNode body(DiagnosisSummary summary) {
        var body = JSON.createObjectNode();
        body.put("diagnosisId", summary.diagnosisId().toString());
        body.put("version", summary.version());
        body.put("executionId", summary.executionId().toString());
        body.put("pipelineId", summary.pipelineId().toString());
        body.put("githubRepositoryId", summary.githubRepositoryId());
        body.put("status", summary.status());
        body.put("reviewRevision", summary.reviewRevision());
        if (summary.reviewedAt() == null) body.putNull("reviewedAt");
        else body.put("reviewedAt", summary.reviewedAt());
        if (summary.probableCause() == null) body.putNull("probableCause");
        else body.put("probableCause", summary.probableCause());
        body.put("createdAt", summary.createdAt());
        return body;
    }

    private static boolean echoes(JsonNode echo, DiagnosisSummary summary) {
        return echo.isObject()
                && summary.incidentId().equals(uuid(echo.path("incidentId")))
                && summary.diagnosisId().equals(uuid(echo.path("diagnosisId")))
                && echo.path("version").isIntegralNumber() && echo.path("version").asLong() == summary.version()
                && echo.path("status").isString() && summary.status().equals(echo.path("status").asString());
    }

    private static UUID uuid(JsonNode value) {
        return value.isString() ? UUID.fromString(value.asString()) : null;
    }
}
