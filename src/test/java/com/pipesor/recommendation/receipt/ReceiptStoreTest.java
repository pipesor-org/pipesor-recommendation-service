package com.pipesor.recommendation.receipt;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReceiptStoreTest {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");
    private static PGSimpleDataSource dataSource;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).defaultSchema("diagnosis").load().migrate();
    }

    @AfterAll static void stop() { POSTGRES.stop(); }

    @Test
    void commitThenRestartRecoversDuplicateWithoutRewritingTheAcceptedRow() throws Exception {
        var input = fixture();
        assertThat(new ReceiptStore(dataSource).accept(input)).isTrue();
        String before = row(input.operationId());
        assertThat(new ReceiptStore(dataSource).accept(input)).isFalse();
        assertThat(row(input.operationId())).isEqualTo(before);
        assertThat(count(input.operationId())).isEqualTo(1);
        assertThat(before).contains(input.scopeRevision(), "protected summary", "protected content");
    }

    @Test
    void conflictingReplayPreservesEveryAcceptedValueAndSafeErrors() throws Exception {
        var input = fixture();
        var store = new ReceiptStore(dataSource);
        store.accept(input);
        String before = row(input.operationId());
        var changed = fingerprint(input, "v1:sha256:" + "b".repeat(64));
        var failure = assertThrows(ReceiptStore.Conflict.class, () -> store.accept(changed));
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getMessage()).isEqualTo("Correlated context conflicts with its accepted representation");
        assertThat(row(input.operationId())).isEqualTo(before);
    }

    @Test
    void concurrentIdenticalRequestsCommitOneReceiptAndReturnDuplicates() throws Exception {
        var input = fixture();
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(6);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 6; i++) futures.add(executor.submit(() -> {
                start.await();
                return new ReceiptStore(dataSource).accept(input);
            }));
            start.countDown();
            int accepted = 0;
            for (var future : futures) if (future.get(10, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(1);
            assertThat(count(input.operationId())).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }

    @Test
    void databaseRejectsPartialOrMutatedReceipt() throws Exception {
        var input = fixture();
        new ReceiptStore(dataSource).accept(input);
        String before = row(input.operationId());
        try (var connection = dataSource.getConnection()) {
            for (String sql : List.of("UPDATE diagnosis.correlated_context_receipts SET summary='changed' WHERE operation_id=?",
                    "DELETE FROM diagnosis.correlated_context_receipts WHERE operation_id=?")) {
                try (var statement = connection.prepareStatement(sql)) {
                    statement.setObject(1, input.operationId());
                    assertThrows(java.sql.SQLException.class, statement::executeUpdate);
                }
            }
        }
        assertThat(row(input.operationId())).isEqualTo(before);
        var invalid = fixture();
        var missingMember = new ReceiptSubmission(invalid.operationId(), invalid.tenantId(), invalid.installationId(),
                invalid.repositoryId(), invalid.pipelineId(), invalid.scopeRevision(), invalid.incidentId(), invalid.executionId(),
                invalid.evidenceIds(), invalid.summary(), invalid.correlationId(), invalid.contextCreatedAt(), "[]", invalid.fingerprint());
        var failure = assertThrows(ReceiptStore.Unavailable.class, () -> new ReceiptStore(dataSource).accept(missingMember));
        assertThat(failure.getCause()).isNull();
        assertThat(count(invalid.operationId())).isZero();
    }

    @Test
    void commitFailureRollsBackTheReceiptBeforeAcknowledgement() throws Exception {
        var input = fixture();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE FUNCTION diagnosis.synthetic_commit_failure() RETURNS trigger LANGUAGE plpgsql AS $$ "
                    + "BEGIN IF NEW.operation_id='" + input.operationId() + "'::uuid THEN RAISE EXCEPTION 'synthetic failure'; "
                    + "END IF; RETURN NEW; END; $$");
            statement.execute("CREATE CONSTRAINT TRIGGER synthetic_commit_failure AFTER INSERT ON diagnosis.correlated_context_receipts "
                    + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION diagnosis.synthetic_commit_failure()");
        }
        try {
            assertThrows(ReceiptStore.Unavailable.class, () -> new ReceiptStore(dataSource).accept(input));
            assertThat(count(input.operationId())).isZero();
        } finally {
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("DROP TRIGGER synthetic_commit_failure ON diagnosis.correlated_context_receipts");
                statement.execute("DROP FUNCTION diagnosis.synthetic_commit_failure()");
            }
        }
    }

    private static ReceiptSubmission fingerprint(ReceiptSubmission input, String hash) {
        return new ReceiptSubmission(input.operationId(), input.tenantId(), input.installationId(), input.repositoryId(),
                input.pipelineId(), input.scopeRevision(), input.incidentId(), input.executionId(), input.evidenceIds(),
                input.summary(), input.correlationId(), input.contextCreatedAt(), input.signalsJson(), hash);
    }

    private static ReceiptSubmission fixture() {
        var id = UUID.randomUUID();
        var evidence = UUID.randomUUID();
        var signals = "[{\"evidence_id\":\"" + evidence + "\",\"origin_context_id\":\"" + id
                + "\",\"correlation_id\":\"synthetic-correlation\",\"stage\":\"BUILD\","
                + "\"summary\":\"protected summary\",\"content\":\"protected content\"}]";
        return new ReceiptSubmission(id, UUID.randomUUID(), "123", "456", null, "scope-12", UUID.randomUUID(),
                UUID.randomUUID(), List.of(evidence), "protected operational summary", "synthetic-correlation",
                Instant.parse("2026-11-17T10:00:00.000001Z"), signals, "v1:sha256:" + "a".repeat(64));
    }

    private static int count(UUID id) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT count(*) FROM diagnosis.correlated_context_receipts WHERE operation_id=?")) {
            statement.setObject(1, id);
            try (var rows = statement.executeQuery()) { rows.next(); return rows.getInt(1); }
        }
    }

    private static String row(UUID id) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT row_to_json(r)::text FROM diagnosis.correlated_context_receipts r WHERE operation_id=?")) {
            statement.setObject(1, id);
            try (var rows = statement.executeQuery()) { rows.next(); return rows.getString(1); }
        }
    }
}
