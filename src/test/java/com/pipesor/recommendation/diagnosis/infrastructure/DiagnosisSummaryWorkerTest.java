package com.pipesor.recommendation.diagnosis.infrastructure;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.pipesor.recommendation.diagnosis.application.ContextReconstruction;
import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator;
import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator.Hypothesis;
import com.pipesor.recommendation.diagnosis.application.commandhandlers.PublishDiagnosisSummariesHandler;
import com.pipesor.recommendation.diagnosis.application.ports.DiagnosisSummaryOutbox;
import com.pipesor.recommendation.diagnosis.infrastructure.persistence.JdbcAdmittedContexts;
import com.pipesor.recommendation.diagnosis.infrastructure.persistence.JdbcDiagnosisReadModel;
import com.pipesor.recommendation.diagnosis.infrastructure.persistence.JdbcDiagnosisSummaryOutbox;
import com.pipesor.recommendation.diagnosis.infrastructure.pipeline.PipelineDiagnosisSummaryPublisher;
import com.pipesor.recommendation.diagnosis.review.PersistedDiagnosticWorkflow;
import com.pipesor.recommendation.diagnosis.review.ReviewStore;
import com.pipesor.recommendation.receipt.ReceiptStore;
import com.pipesor.recommendation.receipt.ReceiptSubmission;
import com.pipesor.recommendation.retrieval.domain.RetrievalResult;
import com.pipesor.recommendation.shared.security.PlatformIds;
import com.pipesor.recommendation.shared.security.SigningKey;
import com.pipesor.recommendation.shared.security.TransportPolicy;
import com.pipesor.recommendation.shared.security.WorkloadTokens;
import com.sun.net.httpserver.HttpServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-027 publisher on PostgreSQL against a loopback stand-in for Pipeline's
 * {@code PUT /internal/v1/incidents/{incidentId}/diagnosis-summary}.
 */
class DiagnosisSummaryWorkerTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");
    private static final UUID TENANT = UUID.randomUUID();
    private static final KeyPair KEYS = rsa();
    private static PGSimpleDataSource source;
    private static JdbcTemplate jdbc;
    private static HttpServer pipeline;
    private static final List<Exchange> exchanges = new CopyOnWriteArrayList<>();
    private static volatile Function<Exchange, Reply> reply = DiagnosisSummaryWorkerTest::echo;

    record Exchange(String method, String path, Map<String, List<String>> headers, JsonNode body) {
        String header(String name) {
            return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(entry -> entry.getValue().getFirst()).findFirst().orElse(null);
        }
    }

    record Reply(int status, String body) { }

    @BeforeAll
    static void start() throws Exception {
        POSTGRES.start();
        source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(source).defaultSchema("diagnosis").load().migrate();
        jdbc = new JdbcTemplate(source);
        pipeline = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pipeline.createContext("/pipeline/internal/v1/incidents/", http -> {
            var exchange = new Exchange(http.getRequestMethod(), http.getRequestURI().getPath(),
                    Map.copyOf(http.getRequestHeaders()), JSON.readTree(http.getRequestBody().readAllBytes()));
            exchanges.add(exchange);
            var answer = reply.apply(exchange);
            byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
            http.getResponseHeaders().set("Content-Type", "application/json");
            http.sendResponseHeaders(answer.status(), bytes.length);
            http.getResponseBody().write(bytes);
            http.close();
        });
        pipeline.start();
    }

    @AfterAll
    static void stop() {
        pipeline.stop(0);
        POSTGRES.stop();
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM diagnosis.incident_summary_outbox");
        exchanges.clear();
        reply = DiagnosisSummaryWorkerTest::echo;
    }

    @Test
    void pushesTheExactSummaryWithTheWorkloadRoleAndDeletesOnAcknowledgement() throws Exception {
        var receipt = admit("Dependency resolution failed (inferred).");
        assertThat(outbox(receipt)).containsEntry("generation", 1L);

        assertThat(handler().runOnce()).isEqualTo(1);
        var sent = exchanges.getFirst();
        assertThat(exchanges).hasSize(1);
        assertThat(sent.method()).isEqualTo("PUT");
        assertThat(sent.path()).isEqualTo("/pipeline/internal/v1/incidents/" + receipt.incidentId() + "/diagnosis-summary");
        assertThat(sent.header("Content-Type")).isEqualTo("application/json");
        assertThat(sent.header("Accept")).isEqualTo("application/json");
        assertThat(sent.header("X-Correlation-ID")).isEqualTo("worker-corr");
        assertThat(sent.header("X-Pipesor-Subject-Token")).isNull();
        assertThat(sent.header("Authorization")).startsWith("Bearer ");
        var token = SignedJWT.parse(sent.header("Authorization").substring("Bearer ".length()));
        assertThat(token.verify(new RSASSAVerifier((RSAPublicKey) KEYS.getPublic()))).isTrue();
        assertThat(token.getJWTClaimsSet().getIssuer()).isEqualTo("urn:pipesor:workload:recommendation");
        assertThat(token.getJWTClaimsSet().getSubject()).isEqualTo(PlatformIds.workload("recommendation").toString());
        assertThat(token.getJWTClaimsSet().getAudience()).containsExactly("urn:pipesor:api:pipeline");
        assertThat(token.getJWTClaimsSet().getStringListClaim("roles"))
                .containsExactly("Recommendation.DiagnosisSummaries.Write");

        UUID diagnosisId = jdbc.queryForObject("SELECT id FROM diagnosis.diagnoses WHERE context_id = ?", UUID.class,
                receipt.operationId());
        var createdAt = jdbc.queryForObject("SELECT created_at FROM diagnosis.diagnoses WHERE id = ?",
                OffsetDateTime.class, diagnosisId).toInstant();
        assertThat(sent.body()).isEqualTo(JSON.readTree("""
                {"diagnosisId":"%s","version":1,"executionId":"%s","pipelineId":"%s","githubRepositoryId":"456",
                 "status":"PENDING","reviewRevision":0,"reviewedAt":null,
                 "probableCause":"Dependency resolution failed (inferred).","createdAt":"%s"}
                """.formatted(diagnosisId, receipt.executionId(), receipt.pipelineId(), createdAt)));
        assertThat(outbox(receipt)).isNull();
        assertThat(handler().runOnce()).isZero();
    }

    @Test
    void aReviewProjectsTheDecisionRevisionAndTime() throws Exception {
        var receipt = admit("Cause");
        handler().runOnce();
        assertThat(exchanges.getFirst().body().get("status").asString()).isEqualTo("PENDING");

        review(receipt, 0, ReviewStore.Decision.CONFIRMED);
        assertThat(outbox(receipt)).containsEntry("generation", 1L);
        assertThat(handler().runOnce()).isEqualTo(1);
        var body = exchanges.getLast().body();
        assertThat(body.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(body.get("reviewRevision").asInt()).isEqualTo(1);
        assertThat(body.get("reviewedAt").asString()).isEqualTo(jdbc.queryForObject("""
                SELECT r.timestamp FROM diagnosis.human_reviews r JOIN diagnosis.diagnoses d ON d.id = r.diagnostic_id
                WHERE d.context_id = ?""", OffsetDateTime.class, receipt.operationId()).toInstant().toString());
        assertThat(outbox(receipt)).isNull();
    }

    @Test
    void transientFailuresBackOffAndKeepRetryingAtTheCap() throws Exception {
        var receipt = admit("Cause");
        reply = exchange -> new Reply(503, "{}");
        assertThat(handler().runOnce()).isEqualTo(1);
        assertThat(outbox(receipt)).containsEntry("attempts", 1).containsEntry("last_failure", "pipeline_status_503")
                .containsEntry("lease_until", null);
        assertThat(delaySeconds(receipt)).isBetween(1.0, 2.5);
        assertThat(handler().runOnce()).isZero();

        makeDue(receipt);
        reply = exchange -> new Reply(403, "{}");
        assertThat(handler().runOnce()).isEqualTo(1);
        assertThat(outbox(receipt)).containsEntry("attempts", 2).containsEntry("last_failure", "pipeline_status_403");
        assertThat(delaySeconds(receipt)).isBetween(3.0, 4.5);

        jdbc.update("UPDATE diagnosis.incident_summary_outbox SET attempts = 40 WHERE incident_id = ?", receipt.incidentId());
        makeDue(receipt);
        reply = exchange -> new Reply(200, "{\"incidentId\":\"" + receipt.incidentId() + "\"}");
        assertThat(handler().runOnce()).isEqualTo(1);
        assertThat(outbox(receipt)).containsEntry("attempts", 41).containsEntry("last_failure", "pipeline_invalid_response");
        assertThat(delaySeconds(receipt)).isBetween(590.0, 600.5);

        makeDue(receipt);
        reply = DiagnosisSummaryWorkerTest::echo;
        assertThat(handler().runOnce()).isEqualTo(1);
        assertThat(outbox(receipt)).isNull();
        assertThat(exchanges).hasSize(4);
    }

    @Test
    void permanentRefusalsAreDropped() throws Exception {
        var conflicting = admit("Cause");
        var invalid = admit("Cause");
        reply = exchange -> exchange.path().contains(conflicting.incidentId().toString())
                ? new Reply(409, "{\"code\":\"SCOPE_MISMATCH\"}") : new Reply(400, "{\"code\":\"INVALID_REQUEST\"}");
        assertThat(handler().runOnce()).isEqualTo(2);
        assertThat(outbox(conflicting)).isNull();
        assertThat(outbox(invalid)).isNull();
        assertThat(exchanges).hasSize(2);
    }

    @Test
    void aReviewDuringAnInFlightPushKeepsTheNewerGenerationDue() throws Exception {
        var receipt = admit("Cause");
        var reviewed = new AtomicInteger();
        reply = exchange -> {
            if (reviewed.getAndIncrement() == 0) {
                try {
                    review(receipt, 0, ReviewStore.Decision.REJECTED);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }
            return echo(exchange);
        };
        assertThat(handler().runOnce()).isEqualTo(1);
        assertThat(exchanges.getFirst().body().get("status").asString()).isEqualTo("PENDING");
        assertThat(outbox(receipt)).containsEntry("generation", 2L).containsEntry("attempts", 0)
                .containsEntry("lease_until", null).containsEntry("due", true);

        assertThat(handler().runOnce()).isEqualTo(1);
        assertThat(exchanges.getLast().body().get("status").asString()).isEqualTo("REJECTED");
        assertThat(exchanges.getLast().body().get("reviewRevision").asInt()).isEqualTo(1);
        assertThat(outbox(receipt)).isNull();
    }

    @Test
    void probableCauseIsBoundedToOneThousandCodePoints() throws Exception {
        var receipt = admit("😀".repeat(1001));
        handler().runOnce();
        String cause = exchanges.getFirst().body().get("probableCause").asString();
        assertThat(cause.codePointCount(0, cause.length())).isEqualTo(1000);
        assertThat(cause).isEqualTo("😀".repeat(1000));
        assertThat(outbox(receipt)).isNull();
    }

    @Test
    void rowsWithoutABindableAdmittedDiagnosisAreSkipped() throws Exception {
        // Nothing admitted for this incident and execution.
        jdbc.update("INSERT INTO diagnosis.incident_summary_outbox (incident_id, execution_id) VALUES (?, ?)",
                UUID.randomUUID(), UUID.randomUUID());
        // Admitted, but its scope no longer matches its receipt: the Workspace would answer diagnosis_stale.
        var receipt = admit("Cause");
        jdbc.update("UPDATE diagnosis.diagnoses SET authorization_scope = jsonb_set(authorization_scope, "
                + "'{scopeRevision}', '\"scope-2\"') WHERE context_id = ?", receipt.operationId());
        assertThat(handler().runOnce()).isEqualTo(2);
        assertThat(exchanges).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM diagnosis.incident_summary_outbox", Integer.class)).isZero();
    }

    @Test
    void claimsArePushedOnlyInTheFirstHalfOfTheLease() throws Exception {
        var receipts = List.of(admit("Cause"), admit("Cause"), admit("Cause"));
        var start = Instant.now();
        var calls = new AtomicInteger();
        // runOnce reads the clock once for the window and once per claim; the second claim starts after it closed.
        var clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return calls.getAndIncrement() < 2 ? start : start.plusSeconds(16); }
        };
        assertThat(handler(clock).runOnce()).isEqualTo(3);
        assertThat(exchanges).hasSize(1);
        assertThat(receipts.stream().filter(receipt -> outbox(receipt) != null).toList()).hasSize(2)
                .allSatisfy(receipt -> assertThat(outbox(receipt)).containsEntry("lease_until", null)
                        .containsEntry("attempts", 0).containsEntry("due", true));
    }

    @Test
    void replicasClaimDisjointRowsAndStaleClaimsCannotSettle() throws Exception {
        var ids = new HashSet<UUID>();
        for (int i = 0; i < 6; i++) {
            UUID incident = UUID.randomUUID();
            ids.add(incident);
            jdbc.update("INSERT INTO diagnosis.incident_summary_outbox (incident_id, execution_id) VALUES (?, ?)",
                    incident, UUID.randomUUID());
        }
        var outbox = new JdbcDiagnosisSummaryOutbox(jdbc);
        var go = new CountDownLatch(1);
        List<DiagnosisSummaryOutbox.Claim> claimed = new ArrayList<>();
        try (var replicas = Executors.newFixedThreadPool(3)) {
            var futures = new ArrayList<Future<List<DiagnosisSummaryOutbox.Claim>>>();
            for (int i = 0; i < 3; i++) futures.add(replicas.submit(() -> {
                go.await();
                return outbox.claim(4, Duration.ofSeconds(30));
            }));
            go.countDown();
            for (var future : futures) claimed.addAll(future.get(10, TimeUnit.SECONDS));
        }
        assertThat(claimed).extracting(DiagnosisSummaryOutbox.Claim::incidentId).doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(ids);
        assertThat(outbox.claim(10, Duration.ofSeconds(30))).isEmpty();

        var stale = claimed.getFirst();
        jdbc.update("UPDATE diagnosis.incident_summary_outbox SET lease_until = clock_timestamp() - interval '1 second' "
                + "WHERE incident_id = ?", stale.incidentId());
        var reclaimed = outbox.claim(10, Duration.ofSeconds(30));
        assertThat(reclaimed).singleElement().satisfies(claim -> {
            assertThat(claim.incidentId()).isEqualTo(stale.incidentId());
            assertThat(claim.leaseUntil()).isAfter(stale.leaseUntil());
        });
        outbox.retry(stale, Duration.ofMinutes(5), "pipeline_status_503");
        outbox.release(stale);
        assertThat(jdbc.queryForMap("SELECT attempts, lease_until FROM diagnosis.incident_summary_outbox "
                + "WHERE incident_id = ?", stale.incidentId())).containsEntry("attempts", 0)
                .satisfies(row -> assertThat(row.get("lease_until")).isNotNull());
        outbox.retry(reclaimed.getFirst(), Duration.ofSeconds(2), "pipeline_status_503");
        assertThat(jdbc.queryForMap("SELECT attempts, lease_until FROM diagnosis.incident_summary_outbox "
                + "WHERE incident_id = ?", stale.incidentId())).containsEntry("attempts", 1)
                .containsEntry("lease_until", null);
    }

    private static Reply echo(Exchange exchange) {
        var body = exchange.body();
        String incident = exchange.path().split("/")[5];
        return new Reply(200, JSON.writeValueAsString(Map.of("incidentId", incident,
                "diagnosisId", body.get("diagnosisId").asString(), "version", body.get("version").asInt(),
                "reviewRevision", body.get("reviewRevision").asInt(), "status", body.get("status").asString(),
                "applied", true)));
    }

    private static PublishDiagnosisSummariesHandler handler() {
        return handler(Clock.systemUTC());
    }

    private static PublishDiagnosisSummariesHandler handler(Clock clock) {
        var base = URI.create("http://127.0.0.1:" + pipeline.getAddress().getPort() + "/pipeline");
        var publisher = new PipelineDiagnosisSummaryPublisher(new TransportPolicy(true)
                .resolve(base, PipelineDiagnosisSummaryPublisher.INCIDENTS_PATH),
                new WorkloadTokens("recommendation", TENANT, key(), Clock.systemUTC()), Duration.ofSeconds(5));
        return new PublishDiagnosisSummariesHandler(new JdbcDiagnosisSummaryOutbox(jdbc),
                new JdbcDiagnosisReadModel(jdbc, new DataSourceTransactionManager(source)), publisher,
                new PublishDiagnosisSummariesHandler.Policy(20, Duration.ofSeconds(30), Duration.ofSeconds(2),
                        Duration.ofMinutes(10)), clock);
    }

    /** Accepts a receipt and admits its diagnosis with the given inferred cause (no RAG or provider). */
    private static ReceiptSubmission admit(String cause) throws Exception {
        var receipt = DiagnosisWorkerTest.receipt(1);
        new ReceiptStore(source).accept(receipt);
        jdbc.update("UPDATE diagnosis.diagnosis_jobs SET state = 'DONE' WHERE context_id = ?", receipt.operationId());
        var input = ContextReconstruction.reconstruct(new JdbcAdmittedContexts(jdbc).find(receipt.operationId())
                .orElseThrow());
        var workflow = new PersistedDiagnosticWorkflow(new DiagnosticOrchestrator(
                query -> new RetrievalResult.NoMatch(query.correlationId()),
                (signals, knowledge) -> new Hypothesis(cause, "Manual check", "Synthetic only",
                        List.of(signals.getLast().evidenceId()), List.of())), new ReviewStore(source));
        workflow.diagnose(input.contextId(), input.executionId(), input.incidentId(), input.query(), input.evidence());
        return receipt;
    }

    private static void review(ReceiptSubmission receipt, int expectedRevision, ReviewStore.Decision decision)
            throws Exception {
        var store = new ReviewStore(source);
        UUID id = jdbc.queryForObject("SELECT id FROM diagnosis.diagnoses WHERE context_id = ?", UUID.class,
                receipt.operationId());
        store.append(store.read(id), UUID.randomUUID(), "reviewer-1", new ReviewStore.Submission(1, expectedRevision,
                decision, decision == ReviewStore.Decision.REJECTED ? "Not supported by the evidence" : null, null, null));
    }

    private static Map<String, Object> outbox(ReceiptSubmission receipt) {
        var rows = jdbc.queryForList("""
                SELECT generation, attempts, last_failure, lease_until, next_attempt_at <= clock_timestamp() AS due
                FROM diagnosis.incident_summary_outbox WHERE incident_id = ? AND execution_id = ?""",
                receipt.incidentId(), receipt.executionId());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static double delaySeconds(ReceiptSubmission receipt) {
        return jdbc.queryForObject("""
                SELECT extract(epoch FROM next_attempt_at - clock_timestamp())::float8
                FROM diagnosis.incident_summary_outbox WHERE incident_id = ?""", Double.class, receipt.incidentId());
    }

    private static void makeDue(ReceiptSubmission receipt) {
        jdbc.update("UPDATE diagnosis.incident_summary_outbox SET next_attempt_at = clock_timestamp() WHERE incident_id = ?",
                receipt.incidentId());
    }

    private static SigningKey key() {
        return new SigningKey() {
            @Override public RSAPublicKey publicKey() { return (RSAPublicKey) KEYS.getPublic(); }
            @Override public byte[] sign(byte[] input) {
                try {
                    var signature = Signature.getInstance("SHA256withRSA");
                    signature.initSign(KEYS.getPrivate());
                    signature.update(input);
                    return signature.sign();
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            }
        };
    }

    private static KeyPair rsa() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
