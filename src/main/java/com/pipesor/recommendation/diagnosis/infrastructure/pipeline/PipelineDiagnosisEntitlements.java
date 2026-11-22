package com.pipesor.recommendation.diagnosis.infrastructure.pipeline;

import com.pipesor.recommendation.diagnosis.application.model.Entitlement;
import com.pipesor.recommendation.diagnosis.application.ports.DiagnosisEntitlements;
import com.pipesor.recommendation.shared.security.WorkloadTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * {@code recommendation → pipeline POST /internal/v1/diagnosis-read-entitlements} with the workload
 * token (role {@code Recommendation.DiagnosisEntitlements.Resolve}) and the caller's unmodified
 * session token in {@code X-Pipesor-Subject-Token} (identity contract v1, §4). No positive result
 * is cached; every request asks again.
 *
 * <p>Request {@code {"incidentId"}}. A {@code 200} confirms access and carries {@code incidentId,
 * pipelineId, executionId} (the single failed ORIGIN execution), {@code githubInstallationId,
 * githubRepositoryId}, optionally {@code tenantId} and {@code capabilities} (e.g. {@code ["VIEW",
 * "OPERATE"]}). {@code 403}/{@code 404} deny; anything else is an indeterminate answer.
 */
public final class PipelineDiagnosisEntitlements implements DiagnosisEntitlements {
    public static final String PATH = "/internal/v1/diagnosis-read-entitlements";
    public static final String TARGET = "pipeline";
    public static final String ROLE = "Recommendation.DiagnosisEntitlements.Resolve";
    static final String SUBJECT_HEADER = "X-Pipesor-Subject-Token";
    private static final int MAX_RESPONSE_BYTES = 16 * 1024;
    private static final Logger LOG = LoggerFactory.getLogger(PipelineDiagnosisEntitlements.class);
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION).build();

    private final URI destination;
    private final WorkloadTokens tokens;
    private final HttpClient http;
    private final Duration timeout;

    /** {@code destination} must already satisfy the platform transport policy. */
    public PipelineDiagnosisEntitlements(URI destination, WorkloadTokens tokens, Duration timeout) {
        this.destination = Objects.requireNonNull(destination);
        this.tokens = Objects.requireNonNull(tokens);
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException("The entitlement timeout must be positive and at most 10 seconds");
        }
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public Entitlement resolve(UUID incidentId, String subjectToken, String correlationId) {
        Objects.requireNonNull(incidentId);
        if (subjectToken == null || subjectToken.isEmpty() || subjectToken.chars().anyMatch(c -> c <= 0x20 || c >= 0x7f)) {
            throw new Unavailable();
        }
        HttpResponse<byte[]> response;
        try {
            var builder = HttpRequest.newBuilder(destination).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + tokens.issue(TARGET, ROLE).value())
                    .header(SUBJECT_HEADER, subjectToken)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(
                            JSON.writeValueAsBytes(Map.of("incidentId", incidentId.toString()))));
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
            throw unavailable("interrupted", interrupted, correlationId);
        } catch (Exception failure) {
            throw unavailable("transport_failure", failure, correlationId);
        }
        int status = response.statusCode();
        if (status == 403 || status == 404) throw new Denied();
        if (status != 200) {
            LOG.warn("entitlement_unavailable status={} correlationId={}", status, correlationId);
            throw new Unavailable();
        }
        try {
            return decode(JSON.readTree(response.body()));
        } catch (RuntimeException invalid) {
            throw unavailable("invalid_response", invalid, correlationId);
        }
    }

    static Entitlement decode(JsonNode body) {
        if (!body.isObject()) throw new IllegalArgumentException("Entitlement must be an object");
        Set<String> capabilities = new HashSet<>();
        var listed = body.path("capabilities");
        if (listed.isArray()) for (JsonNode value : listed) capabilities.add(value.asString());
        else if (!listed.isMissingNode() && !listed.isNull()) throw new IllegalArgumentException("Invalid capabilities");
        return new Entitlement(uuid(body, "incidentId", true), uuid(body, "pipelineId", true), uuid(body, "executionId", true),
                uuid(body, "tenantId", false), external(body, "githubInstallationId"), external(body, "githubRepositoryId"),
                capabilities);
    }

    private static UUID uuid(JsonNode body, String field, boolean required) {
        var value = body.path(field);
        if (!required && (value.isMissingNode() || value.isNull())) return null;
        if (!value.isString()) throw new IllegalArgumentException("Invalid " + field);
        var id = UUID.fromString(value.asString());
        if (!id.toString().equals(value.asString())) throw new IllegalArgumentException("Invalid " + field);
        return id;
    }

    private static String external(JsonNode body, String field) {
        var value = body.path(field);
        String text = value.isString() ? value.asString() : value.isIntegralNumber() ? value.asString() : null;
        if (text == null || !text.matches("[1-9][0-9]{0,31}")) throw new IllegalArgumentException("Invalid " + field);
        return text;
    }

    private static Unavailable unavailable(String reason, Exception failure, String correlationId) {
        LOG.warn("entitlement_unavailable reason={} exception={} correlationId={}", reason, failure.getClass().getName(),
                correlationId);
        return new Unavailable();
    }
}
