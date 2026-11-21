package com.pipesor.recommendation.diagnosis.review;

import com.pipesor.recommendation.RecommendationServiceApplication;
import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator;
import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator.Diagnosis;
import com.pipesor.recommendation.retrieval.domain.CorpusKind;
import com.pipesor.recommendation.retrieval.domain.KnowledgeMatch;
import com.pipesor.recommendation.retrieval.domain.RetrievalQuery;
import com.pipesor.recommendation.retrieval.domain.RetrievalResult;
import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator.Evidence;
import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator.Hypothesis;
import com.pipesor.recommendation.retrieval.domain.AuthorizationScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewLifecycleTest {
    private static final java.util.concurrent.atomic.AtomicBoolean VIEWER_REVOKED = new java.util.concurrent.atomic.AtomicBoolean();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AuthorizationScope SCOPE = new AuthorizationScope(UUID.randomUUID(), "1", "2",
            UUID.randomUUID(), "fixture");
    private static final AuthorizationScope OTHER_SCOPE = new AuthorizationScope(UUID.randomUUID(), "1", "2",
            SCOPE.pipelineId(), "fixture");
    private static final String WRITE = "synthetic-operator";
    private static final String READ = "synthetic-viewer";
    private static final String OPERATOR = "reviewer-local-01";
    private static final String VIEWER = "viewer-local-01";
    private static final String OUTSIDER = "outsider-local-01";
    private static PGSimpleDataSource source;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TestConfiguration(proxyBeanMethods = false)
    static class LocalAuthority {
        @Bean
        RouterFunction<ServerResponse> reviewRoutes() {
            return ReviewEndpoint.routes(new ReviewStore(source), new ReviewEndpoint.Authority() {
                public String authenticate(String bearer) {
                    return switch (bearer) {
                        case WRITE -> OPERATOR;
                        case READ -> VIEWER;
                        case "synthetic-outsider" -> OUTSIDER;
                        case "synthetic-authority-outage" -> throw new IllegalStateException();
                        default -> null;
                    };
                }

                public boolean allows(String reviewer, AuthorizationScope scope, boolean write) {
                    return SCOPE.equals(scope) && (reviewer.equals(OPERATOR) || !write && reviewer.equals(VIEWER) && !VIEWER_REVOKED.get())
                            || OTHER_SCOPE.equals(scope) && reviewer.equals(OUTSIDER);
                }
            });
        }
    }

    @Test
    void persistsAllDecisionsAndReconstructsIncludingAfterRestart() throws Exception {
        try (var database = new PostgreSQLContainer("postgres:17.11")) {
            database.start();
            source = new PGSimpleDataSource();
            source.setURL(database.getJdbcUrl());
            source.setUser(database.getUsername());
            source.setPassword(database.getPassword());
            Flyway.configure().dataSource(source).defaultSchema("diagnosis").load().migrate();
            var store = new ReviewStore(source);
            UUID confirm = UUID.randomUUID(), reject = UUID.randomUUID();
            UUID other = UUID.randomUUID();
            store.save(other, 1, OTHER_SCOPE, synthetic("other tenant fixture"));
            Diagnosis e05;
            try (var input = getClass().getResourceAsStream("/review/e05-diagnosis.json")) {
                e05 = JSON.readValue(input, Diagnosis.class);
            }
            store.save(confirm, 1, SCOPE, synthetic("compiler reported missing file"));
            var retrieved = e05.sources().getFirst();
            var kind = CorpusKind.AUTHORIZED_TECHNICAL_DOCUMENTATION;
            var query = new RetrievalQuery(e05.correlationId(), e05.evidence().getFirst().content(),
                    SCOPE, List.of(kind), 1);
            var workflow = new PersistedDiagnosticWorkflow(new DiagnosticOrchestrator(
                    request -> new RetrievalResult.Matches(request.correlationId(), List.of(new KnowledgeMatch(
                            retrieved.chunkId(), retrieved.documentId(), kind, "Replay guide", "Replay knowledge",
                            retrieved.provenance(), 1))),
                    (signals, knowledge) -> e05.hypothesis()), store);
            var admitted = workflow.diagnose(e05.contextId(), e05.executionId(), e05.incidentId(), query, e05.evidence());
            UUID correct = admitted.diagnosticId();
            assertThat(admitted.version()).isEqualTo(1);
            assertThat(admitted.output()).isEqualTo(e05);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> workflow.diagnose(
                    e05.contextId(), UUID.randomUUID(), e05.incidentId(), query, e05.evidence()))
                    .isInstanceOf(ReviewStore.Conflict.class);
            var invalid = new PersistedDiagnosticWorkflow(new DiagnosticOrchestrator(
                    request -> new RetrievalResult.NoMatch(request.correlationId()),
                    (signals, knowledge) -> e05.hypothesis()), store);
            UUID invalidContext = UUID.randomUUID();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> invalid.diagnose(
                    invalidContext, e05.executionId(), e05.incidentId(), query, e05.evidence()))
                    .isInstanceOf(DiagnosticOrchestrator.InvalidHypothesis.class);
            store.save(reject, 1, SCOPE, synthetic("unsupported claim about the network"));
            try (var connection = source.getConnection();
                 var count = connection.prepareStatement("SELECT count(*) FROM diagnosis.diagnoses WHERE incident_id = ?")) {
                count.setObject(1, e05.incidentId());
                try (var row = count.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getInt(1)).isEqualTo(1);
                }
            }
            try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                UUID concurrentIncident = UUID.randomUUID();
                var firstAdmission = workers.submit(() -> workflow.diagnose(UUID.randomUUID(),
                        e05.executionId(), concurrentIncident, query, e05.evidence()));
                var secondAdmission = workers.submit(() -> workflow.diagnose(UUID.randomUUID(),
                        e05.executionId(), concurrentIncident, query, e05.evidence()));
                assertThat(List.of(firstAdmission.get(10, java.util.concurrent.TimeUnit.SECONDS).version(),
                        secondAdmission.get(10, java.util.concurrent.TimeUnit.SECONDS).version()))
                        .containsExactlyInAnyOrder(1, 2);
            }
            var browserIds = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
            store.save(browserIds.get(0), 1, SCOPE, synthetic("compiler reported missing file"));
            store.save(browserIds.get(1), 2, SCOPE, e05);
            store.save(browserIds.get(2), 1, SCOPE, synthetic("unsupported claim about the network"));

            // The existing browser fixture occupies version 2 for this incident.
            UUID nextContext = UUID.randomUUID();
            var next = workflow.diagnose(nextContext, e05.executionId(), e05.incidentId(), query, e05.evidence());
            assertThat(next.version()).isEqualTo(3);
            assertThat(next.diagnosticId()).isNotEqualTo(correct);
            assertThat(next.output().contextId()).isEqualTo(nextContext);
            assertThat(workflow.diagnose(nextContext, e05.executionId(), e05.incidentId(), query, e05.evidence()))
                    .isEqualTo(next);
            JsonNode original;
            try (var app = start()) {
                int port = app.getWebServer().getPort();
                original = get(port, correct, WRITE).get("original");
                assertThat(original).isEqualTo(JSON.valueToTree(e05));
                assertThat(get(port, correct, READ).get("status").asString()).isEqualTo("PENDING");
                VIEWER_REVOKED.set(true);
                try {
                    assertThat(HTTP.send(request(port, correct, READ),
                            HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(404);
                } finally {
                    VIEWER_REVOKED.set(false);
                }
                assertThat(get(port, correct, READ).get("original")).isEqualTo(original);
                assertThat(HTTP.send(request(port, correct, "invalid"), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
                assertThat(HTTP.send(request(port, correct, "synthetic-outsider"), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(404);
                assertThat(get(port, other, "synthetic-outsider").get("status").asString()).isEqualTo("PENDING");
                assertThat(post(port, correct, "synthetic-outsider", UUID.randomUUID(), decision("REJECTED", 0))
                        .statusCode()).isEqualTo(404);
                assertThat(HTTP.send(request(port, correct, "synthetic-authority-outage"), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(503);
                assertThat(post(port, confirm, READ, UUID.randomUUID(), decision("CONFIRMED", 0)).statusCode()).isEqualTo(404);
                assertThat(post(port, confirm, WRITE, UUID.randomUUID(), decision("CORRECTED", 0)).statusCode()).isEqualTo(400);
                assertThat(post(port, confirm, WRITE, UUID.randomUUID(),
                        decision("CONFIRMED", 0).replace("\"comment\"", "\"reviewer\":\"forged\",\"comment\"")).statusCode()).isEqualTo(400);

                var key = UUID.randomUUID();
                String accepted = decision("CONFIRMED", 0);
                var first = post(port, confirm, WRITE, key, accepted);
                assertThat(first.statusCode()).isEqualTo(200);
                assertThat(post(port, confirm, WRITE, key, accepted).body()).isEqualTo(first.body());
                assertThat(post(port, confirm, WRITE, key, decision("REJECTED", 0)).statusCode()).isEqualTo(409);
                assertThat(post(port, confirm, WRITE, key, accepted.replace("\"diagnosisVersion\":1",
                        "\"diagnosisVersion\":2")).statusCode()).isEqualTo(409);
                assertThat(post(port, confirm, WRITE, UUID.randomUUID(), accepted).statusCode()).isEqualTo(409);
                var competing = List.of(
                        HTTP.sendAsync(postRequest(port, confirm, WRITE, UUID.randomUUID(), decision("CONFIRMED", 1)),
                                HttpResponse.BodyHandlers.ofString()),
                        HTTP.sendAsync(postRequest(port, confirm, WRITE, UUID.randomUUID(), decision("CONFIRMED", 1)),
                                HttpResponse.BodyHandlers.ofString()));
                assertThat(competing.stream().map(future -> future.join().statusCode()).toList())
                        .containsExactlyInAnyOrder(200, 409);
                assertThat(post(port, correct, WRITE, UUID.randomUUID(), """
                        {"diagnosisVersion":1,"expectedRevision":0,"decision":"CORRECTED",
                         "comment":"The line does not establish the underlying configuration.",
                         "actualCause":"Compiler could not locate libproc.h; upstream cause unknown.",
                         "correctedRecommendation":"Check header availability and include configuration before installing anything."}
                        """).statusCode()).isEqualTo(200);
                assertThat(post(port, reject, WRITE, UUID.randomUUID(), decision("REJECTED", 0)).statusCode()).isEqualTo(200);
            }
            try (var app = start()) {
                int port = app.getWebServer().getPort();
                var replay = new PersistedDiagnosticWorkflow(new DiagnosticOrchestrator(
                        request -> { throw new AssertionError("Replay called retrieval"); },
                        (signals, knowledge) -> { throw new AssertionError("Replay called inference"); }),
                        new ReviewStore(source));
                assertThat(replay.diagnose(e05.contextId(), e05.executionId(), e05.incidentId(), query, e05.evidence()))
                        .isEqualTo(admitted);
                JsonNode restored = get(port, correct, READ);
                assertThat(restored.get("original")).isEqualTo(original);
                assertThat(restored.get("status").asString()).isEqualTo("CORRECTED");
                assertThat(restored.path("history").get(0).path("reviewer").asString()).isEqualTo(OPERATOR);
                assertThat(restored.path("history").get(0).path("timestamp").asString()).isNotBlank();
                assertThat(restored.path("history").get(0).path("correlationId").asString()).isEqualTo(e05.correlationId());
                assertThat(restored.path("history").get(0).path("correctedRecommendation").asString())
                        .contains("before installing anything");
                var restoredNext = get(port, next.diagnosticId(), READ);
                assertThat(restoredNext.path("diagnosisVersion").asInt()).isEqualTo(3);
                assertThat(restoredNext.path("original")).isEqualTo(JSON.valueToTree(next.output()));
                assertThat(restoredNext.path("status").asString()).isEqualTo("PENDING");
                assertThat(restoredNext.path("history").size()).isZero();
                assertThat(get(port, confirm, READ).path("history").size()).isEqualTo(2);
                assertThat(get(port, reject, READ).path("status").asString()).isEqualTo("REJECTED");
                System.out.println("PIPESOR_ADMISSION_RESTART immutable replay, conflicting fingerprint rejected, invalid citations rejected");
                System.out.println("P3_HTTP confirmed=1 corrected=1 rejected=1; duplicate replay, conflict race, denied read/write, service restart and immutable E05 verified");
                var browserViews = new java.util.ArrayList<JsonNode>();
                if (System.getenv("PIPESOR_REVIEW_BROWSER_CHECK") != null) {
                    browser(port, browserIds);
                    assertThat(get(port, browserIds.get(0), READ).get("status").asString()).isEqualTo("CONFIRMED");
                    var browserCorrection = get(port, browserIds.get(1), READ);
                    assertThat(browserCorrection.get("diagnosisVersion").asInt()).isEqualTo(2);
                    assertThat(browserCorrection.get("status").asString()).isEqualTo("CORRECTED");
                    assertThat(browserCorrection.get("original")).isEqualTo(original);
                    assertThat(get(port, browserIds.get(2), READ).get("status").asString()).isEqualTo("REJECTED");
                    for (UUID id : browserIds) browserViews.add(get(port, id, READ));
                }
                String receipt = System.getenv("PIPESOR_REVIEW_RECEIPT");
                if (receipt != null) {
                    java.nio.file.Files.writeString(Path.of(receipt), JSON.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(Map.of("schemaVersion", "1.0.0",
                                    "scope", "synthetic local PostgreSQL and HTTP",
                                    "httpAfterRestart", List.of(get(port, confirm, READ), restored, get(port, reject, READ)),
                                    "chromiumSubmissions", browserViews,
                                    "checks", List.of("immutable E05 original", "idempotent duplicate",
                                            "changed replay and stale revision rejected", "two competing writes: 200 and 409",
                                            "read-only write denied", "cross-scope read denied", "unauthenticated read denied",
                                            "authority outage fails closed", "server restart preserves history"))) + "\n",
                            java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
                }
            }
        }
    }

    private static void browser(int port, List<UUID> ids) throws Exception {
        var check = Path.of(System.getenv("PIPESOR_REVIEW_BROWSER_CHECK")).toAbsolutePath();
        var log = java.nio.file.Files.createTempFile("pipesor-review-browser-", ".log");
        var process = new ProcessBuilder("node", check.toString()).redirectErrorStream(true).redirectOutput(log.toFile());
        process.environment().put("PIPESOR_DEMO_PORT", Integer.toString(port));
        process.environment().put("PIPESOR_REVIEW_IDS", String.join(",", ids.stream().map(UUID::toString).toList()));
        process.environment().put("PIPESOR_REVIEW_TOKEN", WRITE);
        var running = process.start();
        try {
            assertThat(running.waitFor(150, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            System.out.print(java.nio.file.Files.readString(log));
            assertThat(running.exitValue()).isZero();
        } finally {
            if (running.isAlive()) running.destroyForcibly();
            java.nio.file.Files.deleteIfExists(log);
        }
    }

    private static ServletWebServerApplicationContext start() {
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(
                RecommendationServiceApplication.class, LocalAuthority.class)
                .run("--server.port=0", "--server.address=127.0.0.1",
                        "--pipesor.rag.endpoint=https://rag.example.invalid",
                        "--pipesor.rag.audience=api://00000000-0000-0000-0000-000000000001");
    }

    private static Diagnosis synthetic(String cause) {
        var context = UUID.randomUUID();
        var signal = new Evidence(UUID.randomUUID(), context, "BUILD", "Synthetic build step exited with 1.",
                "v2:job:701:member:0_verify.txt:lines:2-2:sha256:" + "a".repeat(64));
        return new Diagnosis(context, UUID.randomUUID(), UUID.randomUUID(), "p3-synthetic",
                new Hypothesis(cause, "Inspect the build log.", "Only fixture input.", List.of(signal.evidenceId()),
                        List.of()), List.of(signal), List.of());
    }

    private static HttpRequest request(int port, UUID id, String token) {
        return builder(port, id, token).GET().build();
    }

    private static HttpRequest.Builder builder(int port, UUID id, String token) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + ReviewEndpoint.PATH.replace("{id}", id.toString())))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token);
    }

    private static JsonNode get(int port, UUID id, String token) throws Exception {
        var reply = HTTP.send(request(port, id, token), HttpResponse.BodyHandlers.ofString());
        assertThat(reply.statusCode()).isEqualTo(200);
        assertThat(reply.headers().firstValue("Cache-Control")).contains("no-store");
        return JSON.readTree(reply.body());
    }

    private static String decision(String value, int revision) {
        return """
                {"diagnosisVersion":1,"expectedRevision":%d,"decision":"%s","comment":"Reviewed fixture evidence"}
                """.formatted(revision, value);
    }

    private static HttpRequest postRequest(int port, UUID id, String token, UUID key, String body) {
        return builder(port, id, token).header("Content-Type", "application/json")
                .header("Idempotency-Key", key.toString()).POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private static HttpResponse<String> post(int port, UUID id, String token, UUID key, String body) throws Exception {
        return HTTP.send(postRequest(port, id, token, key, body), HttpResponse.BodyHandlers.ofString());
    }
}
