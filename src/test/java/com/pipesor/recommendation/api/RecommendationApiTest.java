package com.pipesor.recommendation.api;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.pipesor.recommendation.RecommendationServiceApplication;
import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator;
import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator.Hypothesis;
import com.pipesor.recommendation.diagnosis.domain.ExecutionIds;
import com.pipesor.recommendation.receipt.ReceiptFixtures;
import com.pipesor.recommendation.shared.security.PlatformIds;
import com.pipesor.recommendation.shared.security.WorkloadTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the real application (profile {@code local}) against PostgreSQL with loopback test peers:
 * platform authentication, the receipt contract, automatic diagnosis, the incident read, reviews
 * and the GitHub Actions report. Only inference is replaced, through a test-only bean.
 */
@SpringBootTest(classes = {RecommendationServiceApplication.class, RecommendationApiTest.TestInference.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RecommendationApiTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final UUID SCOPE_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000901");
    private static final UUID PIPELINE = UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID FIXTURE_INCIDENT = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID FIXTURE_EXECUTION = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final long OPERATOR = 87343021L;
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    private static final PlatformFixture PEERS;
    private static volatile UUID admittedDiagnosis;

    static {
        POSTGRES.start();
        try {
            PEERS = new PlatformFixture();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    @LocalServerPort int port;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestInference {
        @Bean
        @Primary
        DiagnosticOrchestrator.Inference testInference() {
            return (evidence, knowledge) -> new Hypothesis("Dependency resolution failed (inferred).",
                    "Check the dependency repository configuration manually.", "Synthetic evidence only.",
                    evidence.stream().map(DiagnosticOrchestrator.Evidence::evidenceId).toList(), List.of(),
                    new DiagnosticOrchestrator.Model("test-deployment", "test-model-1", "test-prompt"));
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("pipesor.tenant-id", PlatformFixture.TENANT::toString);
        registry.add("pipesor.signing-key-file", PEERS.signingKeyFile::toString);
        registry.add("pipesor.security.trusted-workloads[0].id", () -> "correlation");
        registry.add("pipesor.security.trusted-workloads[0].jwks-uri", () -> PEERS.base() + "/jwks/correlation");
        registry.add("pipesor.security.trusted-workloads[0].roles", () -> "Correlation.Contexts.Deliver");
        registry.add("pipesor.security.session.jwks-uri", () -> PEERS.base() + "/jwks/session");
        registry.add("pipesor.security.github-actions.jwks-uri", () -> PEERS.base() + "/jwks/github");
        registry.add("pipesor.endpoints.rag", () -> PEERS.base() + "/rag");
        registry.add("pipesor.endpoints.pipeline", () -> PEERS.base() + "/pipeline");
        registry.add("pipesor.dashboard-origin", () -> "http://localhost:5173");
        registry.add("pipesor.diagnosis-worker.poll-interval", () -> "200ms");
        registry.add("pipesor.diagnosis-summary.poll-interval", () -> "200ms");
        PEERS.rag = request -> new PlatformFixture.Reply(200, """
                {"contract_version":"1.0","correlation_id":"%s","status":"no_match","matches":[]}
                """.formatted(JSON.readTree(request.body()).get("correlation_id").asString()));
    }

    @AfterAll
    static void stop() {
        PEERS.close();
        POSTGRES.stop();
    }

    @Test
    @Order(1)
    void publicRoutesExposeOnlyHealthJwksAndLocalOpenApi() throws Exception {
        assertThat(get("/actuator/health/readiness", null).statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health/liveness", null).statusCode()).isEqualTo(200);
        var jwks = get("/.well-known/jwks.json", null);
        assertThat(jwks.statusCode()).isEqualTo(200);
        assertThat(jwks.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value)
                .contains("max-age=300").contains("public"));
        assertThat(JSON.readTree(jwks.body()).get("keys")).hasSize(1);
        assertThat(get("/v3/api-docs", null).statusCode()).isEqualTo(200);
        assertThat(get("/actuator/env", null).statusCode()).isIn(401, 404);
    }

    @Test
    @Order(2)
    void receiptRouteAcceptsOnlyCorrelationAndKeepsTheReceiptContract() throws Exception {
        var fixture = ReceiptFixtures.fixture(0);
        String body = fixture.get("proposed_canonical_json").asString();
        var anonymous = deliver(fixture, body, null);
        problem(anonymous, 401, "unauthenticated");
        problem(deliver(fixture, body, PEERS.session(OPERATOR, "Diagnosis.Read Diagnosis.Review")), 403, "forbidden");
        var otherRole = new WorkloadTokens("correlation", PlatformFixture.TENANT, PEERS.correlationKey(), Clock.systemUTC())
                .issue("recommendation", "Rag.Query").value();
        problem(deliver(fixture, body, otherRole), 401, "unauthenticated");
        var doubled = HTTP.send(deliveryRequest(fixture, body, correlationToken())
                .header("Authorization", "Bearer " + correlationToken()).build(), HttpResponse.BodyHandlers.ofString());
        problem(doubled, 400, "invalid_request");

        var accepted = deliver(fixture, body, correlationToken());
        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(accepted.headers().firstValue("Content-Type")).hasValueSatisfying(
                value -> assertThat(value).startsWith("application/json"));
        assertThat(accepted.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(JSON.readTree(accepted.body())).isEqualTo(fixture.get("accepted_receipt"));
        assertThat(JSON.readTree(deliver(fixture, " " + body, correlationToken()).body()))
                .isEqualTo(fixture.get("duplicate_receipt"));
        problem(deliver(fixture, body.replace("scope-12", "scope-99"), correlationToken()), 409, "operation_conflict");
        problem(deliver(fixture, body + " {}", correlationToken()), 400, "invalid_context");
        problem(deliver(fixture, " ".repeat(262_145) + body, correlationToken()), 413, "payload_too_large");

        var second = ReceiptFixtures.fixture(1);
        assertThat(JSON.readTree(deliver(second, second.get("proposed_canonical_json").asString(), correlationToken())
                .body())).isEqualTo(second.get("accepted_receipt"));
    }

    @Test
    @Order(3)
    void diagnosesAutomaticallyAndServesTheHighestAdmittedVersionToEntitledSessions() throws Exception {
        PEERS.pipeline = request -> entitlement(FIXTURE_INCIDENT, FIXTURE_EXECUTION, "456", List.of("VIEW", "OPERATE"));
        String session = PEERS.session(OPERATOR, "Diagnosis.Read Diagnosis.Review");
        var diagnosis = awaitDiagnosis("/api/v1/incidents/" + FIXTURE_INCIDENT + "/diagnosis", session, 2);
        assertThat(diagnosis.get("incidentId").asString()).isEqualTo(FIXTURE_INCIDENT.toString());
        assertThat(diagnosis.get("executionId").asString()).isEqualTo(FIXTURE_EXECUTION.toString());
        assertThat(diagnosis.get("contextId").asString()).isEqualTo("00000000-0000-0000-0000-000000000102");
        assertThat(diagnosis.get("correlationId").asString()).isEqualTo("corr-r2");
        assertThat(diagnosis.get("status").asString()).isEqualTo("PENDING");
        assertThat(diagnosis.at("/hypothesis/probableCause").asString()).contains("inferred");
        assertThat(diagnosis.get("evidence")).hasSize(2);
        assertThat(diagnosis.at("/evidence/1/stage").asString()).isEqualTo("TESTING");
        assertThat(diagnosis.get("sources")).isEmpty();
        assertThat(diagnosis.get("model")).isEqualTo(JSON.readTree(
                "{\"deployment\":\"test-deployment\",\"modelVersion\":\"test-model-1\"}"));
        assertThat(diagnosis.get("reviews")).isEmpty();
        admittedDiagnosis = UUID.fromString(diagnosis.get("diagnosisId").asString());

        var resolution = PEERS.requests.get("pipeline");
        assertThat(resolution.header("X-Pipesor-Subject-Token")).isEqualTo(session);
        assertThat(JSON.readTree(resolution.body())).isEqualTo(JSON.readTree(
                "{\"incidentId\":\"" + FIXTURE_INCIDENT + "\"}"));
        workloadToken(resolution.header("Authorization"), "pipeline", "Recommendation.DiagnosisEntitlements.Resolve");
        workloadToken(PEERS.requests.get("rag").header("Authorization"), "rag", "Rag.Query");

        String path = "/api/v1/incidents/" + FIXTURE_INCIDENT + "/diagnosis";
        PEERS.pipeline = request -> new PlatformFixture.Reply(404, "{}");
        problem(get(path, session), 404, "incident_not_found");
        PEERS.pipeline = request -> new PlatformFixture.Reply(500, "{}");
        problem(get(path, session), 503, "authority_unavailable");
        PEERS.pipeline = request -> entitlement(FIXTURE_INCIDENT, FIXTURE_EXECUTION, "999", List.of("VIEW"));
        problem(get(path, session), 409, "diagnosis_stale");
        PEERS.pipeline = request -> entitlement(FIXTURE_INCIDENT, UUID.randomUUID(), "456", List.of("VIEW"));
        problem(get(path, session), 404, "diagnosis_absent");
        problem(get("/api/v1/incidents/not-a-uuid/diagnosis", session), 400, "invalid_request");
        problem(get(path, PEERS.session(OPERATOR, "GitHub.Self")), 403, "forbidden");
        problem(get(path, correlationToken()), 403, "forbidden");
        problem(get(path, PEERS.gitHubActions("456")), 403, "forbidden");
        problem(get(path, null), 401, "unauthenticated");
    }

    @Test
    @Order(4)
    void reviewsRequireTheEntitlementOperateAndTheExpectedRevision() throws Exception {
        assertThat(admittedDiagnosis).isNotNull();
        String path = "/api/v1/diagnoses/" + admittedDiagnosis + "/reviews";
        String session = PEERS.session(OPERATOR, "Diagnosis.Read Diagnosis.Review");
        String review = """
                {"expectedRevision":0,"decision":"CONFIRMED","comment":"Matches the observed evidence."}""";

        PEERS.pipeline = request -> entitlement(FIXTURE_INCIDENT, FIXTURE_EXECUTION, "456", List.of("VIEW"));
        var view = get(path, session);
        assertThat(view.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(view.body()).get("status").asString()).isEqualTo("PENDING");
        problem(post(path, session, review), 403, "forbidden");
        problem(post(path, PEERS.session(OPERATOR, "Diagnosis.Read"), review), 403, "forbidden");

        PEERS.pipeline = request -> entitlement(FIXTURE_INCIDENT, FIXTURE_EXECUTION, "456", List.of("VIEW", "OPERATE"));
        problem(post(path, session, review.replace("\"comment\"", "\"reviewer\":\"forged\",\"comment\"")), 400,
                "invalid_review");
        var confirmed = post(path, session, review);
        assertThat(confirmed.statusCode()).isEqualTo(200);
        var history = JSON.readTree(confirmed.body()).get("history");
        assertThat(history).hasSize(1);
        assertThat(history.at("/0/reviewer").asString()).isEqualTo(PlatformIds.human(OPERATOR).toString());
        problem(post(path, session, review), 409, "review_conflict");

        PEERS.pipeline = request -> entitlement(FIXTURE_INCIDENT, FIXTURE_EXECUTION, "777", List.of("VIEW", "OPERATE"));
        problem(get(path, session), 404, "not_found");
        PEERS.pipeline = request -> entitlement(FIXTURE_INCIDENT, FIXTURE_EXECUTION, "456", List.of("VIEW"));
        var incident = JSON.readTree(get("/api/v1/incidents/" + FIXTURE_INCIDENT + "/diagnosis", session).body());
        assertThat(incident.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(incident.get("reviews")).singleElement().satisfies(entry -> {
            assertThat(entry.get("revision").asInt()).isEqualTo(1);
            assertThat(entry.get("decision").asString()).isEqualTo("CONFIRMED");
            assertThat(entry.has("reviewer")).isFalse();
            assertThat(entry.has("comment")).isFalse();
        });

        // Notes are optional for confirmations and corrections, may span lines, and a rejection must justify itself.
        PEERS.pipeline = request -> entitlement(FIXTURE_INCIDENT, FIXTURE_EXECUTION, "456", List.of("VIEW", "OPERATE"));
        var corrected = post(path, session, """
                {"expectedRevision":1,"decision":"CORRECTED","actualCause":"The runner image lacked the tool."}""");
        assertThat(corrected.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(corrected.body()).at("/history/1/comment").isNull()).isTrue();
        problem(post(path, session, """
                {"expectedRevision":2,"decision":"REJECTED"}"""), 400, "invalid_review");
        problem(post(path, session, """
                {"expectedRevision":2,"decision":"REJECTED","comment":"Bell \\u0007 character"}"""), 400, "invalid_review");
        var rejected = post(path, session, """
                {"expectedRevision":2,"decision":"REJECTED","comment":"First line\\r\\nSecond line"}""");
        assertThat(rejected.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(rejected.body()).at("/history/2/comment").asString()).isEqualTo("First line\nSecond line");

        // ADR-027: the outbox pushes the latest review to Pipeline's incident queue projection.
        var push = awaitSummary(FIXTURE_INCIDENT, "REJECTED");
        assertThat(push.method()).isEqualTo("PUT");
        assertThat(push.request().header("X-Correlation-ID")).isEqualTo("corr-r2");
        assertThat(push.request().header("X-Pipesor-Subject-Token")).isNull();
        workloadToken(push.request().header("Authorization"), "pipeline", "Recommendation.DiagnosisSummaries.Write");
        var summary = JSON.readTree(push.request().body());
        assertThat(summary.get("diagnosisId").asString()).isEqualTo(admittedDiagnosis.toString());
        assertThat(summary.get("version").asInt()).isEqualTo(2);
        assertThat(summary.get("executionId").asString()).isEqualTo(FIXTURE_EXECUTION.toString());
        assertThat(summary.get("pipelineId").asString()).isEqualTo(PIPELINE.toString());
        assertThat(summary.get("githubRepositoryId").asString()).isEqualTo("456");
        assertThat(summary.get("reviewRevision").asInt()).isEqualTo(3);
        assertThat(summary.get("probableCause").asString()).isEqualTo("Dependency resolution failed (inferred).");
    }

    private static PlatformFixture.Push awaitSummary(UUID incident, String status) throws Exception {
        String path = "/pipeline/internal/v1/incidents/" + incident + "/diagnosis-summary";
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            for (var push : PEERS.summaries) {
                if (push.path().equals(path) && status.equals(JSON.readTree(push.request().body()).get("status").asString())) {
                    return push;
                }
            }
            Thread.sleep(200);
        }
        throw new AssertionError("No " + status + " diagnosis summary pushed for " + incident);
    }

    @Test
    @Order(5)
    void gitHubActionsReportMatchesTheRepositoryAndRun() throws Exception {
        UUID execution = ExecutionIds.githubExecution("456", "77", "1");
        UUID context = UUID.randomUUID();
        String body = receipt(context, UUID.randomUUID(), execution, "456");
        var delivered = HTTP.send(HttpRequest.newBuilder(uri(com.pipesor.recommendation.receipt.interfaces.rest
                        .CorrelatedContextController.PATH)).header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + correlationToken()).header("Idempotency-Key", context.toString())
                .header("X-Correlation-ID", "corr-gha").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(delivered.statusCode()).isEqualTo(200);

        String path = "/api/v1/github-actions/repositories/456/runs/77/attempts/1/report";
        var report = awaitDiagnosis(path, PEERS.gitHubActions("456"), 1);
        assertThat(report.at("/report/executionId").asString()).isEqualTo(execution.toString());
        assertThat(report.get("report").has("reviews")).isFalse();
        assertThat(report.get("markdown").asString()).contains("Probable cause (inferred, not verified)")
                .contains("Observed evidence (sanitized)").contains(execution.toString());

        problem(get(path, PEERS.gitHubActions("999")), 403, "repository_mismatch");
        problem(get("/api/v1/github-actions/repositories/456/runs/78/attempts/1/report", PEERS.gitHubActions("456")),
                404, "diagnosis_absent");
        problem(get("/api/v1/github-actions/repositories/456/runs/077/attempts/1/report", PEERS.gitHubActions("456")),
                400, "invalid_request");
        problem(get(path, PEERS.session(OPERATOR, "Diagnosis.Read")), 403, "forbidden");
        problem(get(path, correlationToken()), 403, "forbidden");
    }

    private JsonNode awaitDiagnosis(String path, String token, int version) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        HttpResponse<String> last = null;
        while (Instant.now().isBefore(deadline)) {
            last = get(path, token);
            if (last.statusCode() == 200) {
                var body = JSON.readTree(last.body());
                var node = body.has("report") ? body.get("report") : body;
                if (node.get("version").asInt() == version) return body;
            } else {
                assertThat(last.statusCode()).isEqualTo(404);
                assertThat(JSON.readTree(last.body()).get("code").asString()).isEqualTo("diagnosis_absent");
            }
            Thread.sleep(200);
        }
        throw new AssertionError("No admitted diagnosis in time; last status " + (last == null ? "none" : last.statusCode()));
    }

    private static void workloadToken(String authorization, String target, String role) throws Exception {
        assertThat(authorization).startsWith("Bearer ");
        var jwt = SignedJWT.parse(authorization.substring(7));
        assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) PEERS.recommendation.getPublic()))).isTrue();
        var claims = jwt.getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo("urn:pipesor:workload:recommendation");
        assertThat(claims.getAudience()).containsExactly("urn:pipesor:api:" + target);
        assertThat(claims.getStringListClaim("roles")).containsExactly(role);
        assertThat(claims.getSubject()).isEqualTo(PlatformIds.workload("recommendation").toString());
    }

    private static PlatformFixture.Reply entitlement(UUID incident, UUID execution, String repository, List<String> capabilities) {
        return new PlatformFixture.Reply(200, JSON.writeValueAsString(Map.of("incidentId", incident.toString(),
                "pipelineId", PIPELINE.toString(), "executionId", execution.toString(), "tenantId", SCOPE_TENANT.toString(),
                "githubInstallationId", "123", "githubRepositoryId", repository, "capabilities", capabilities)));
    }

    private static String receipt(UUID context, UUID incident, UUID execution, String repository) {
        UUID evidence = UUID.randomUUID();
        return JSON.writeValueAsString(Map.of("contract_version", "1.0", "operation_id", context.toString(),
                "authorization_scope", Map.of("tenant_id", SCOPE_TENANT.toString(), "github_installation_id", "123",
                        "github_repository_id", repository, "pipeline_id", PIPELINE.toString(), "scope_revision", "scope-1"),
                "context", Map.of("id", context.toString(), "incident_id", incident.toString(),
                        "execution_id", execution.toString(), "evidence_ids", List.of(evidence.toString()),
                        "summary", "Synthetic context", "correlation_id", "corr-gha",
                        "created_at", "2026-11-20T10:00:00.000001Z"),
                "signals", List.of(Map.of("evidence_id", evidence.toString(), "origin_context_id", context.toString(),
                        "correlation_id", "corr-gha", "stage", "BUILD", "summary", "Synthetic failure",
                        "content", "[ERROR] Failed to execute goal: ```dependency``` unavailable",
                        "source_locator", "v2:job:4242:member:build%2F3_Run%20mvn%20verify.txt:lines:10-14:sha256:"
                                + "e".repeat(64)))));
    }

    private static String correlationToken() {
        return new WorkloadTokens("correlation", PlatformFixture.TENANT, PEERS.correlationKey(), Clock.systemUTC())
                .issue("recommendation", "Correlation.Contexts.Deliver").value();
    }

    private static void problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                value -> assertThat(value).startsWith("application/problem+json"));
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        var body = JSON.readTree(response.body());
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("correlationId").asString()).isNotBlank();
    }

    @Test
    @Order(6)
    void dashboardPreflightAllowsTheReviewIdempotencyKey() throws Exception {
        var preflight = HTTP.send(HttpRequest.newBuilder(uri("/api/v1/diagnoses/" + UUID.randomUUID() + "/reviews"))
                .timeout(Duration.ofSeconds(10)).method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://localhost:5173").header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key").build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(preflight.statusCode()).isEqualTo(200);
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:5173");
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Headers").orElse("").toLowerCase())
                .contains("idempotency-key");
        var foreign = HTTP.send(HttpRequest.newBuilder(uri("/api/v1/diagnoses/" + UUID.randomUUID() + "/reviews"))
                .timeout(Duration.ofSeconds(10)).method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "https://evil.example").header("Access-Control-Request-Method", "POST").build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(foreign.statusCode()).isEqualTo(403);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).GET();
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder deliveryRequest(JsonNode fixture, String body, String token) {
        var builder = HttpRequest.newBuilder(uri("/internal/v1/correlated-contexts")).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Idempotency-Key", fixture.get("headers").get("Idempotency-Key").asString())
                .header("X-Correlation-ID", fixture.get("headers").get("X-Correlation-ID").asString())
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return builder;
    }

    private HttpResponse<String> deliver(JsonNode fixture, String body, String token) throws Exception {
        return HTTP.send(deliveryRequest(fixture, body, token).build(), HttpResponse.BodyHandlers.ofString());
    }
}
