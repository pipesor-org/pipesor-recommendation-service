package com.pipesor.recommendation.receipt;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Caller validates the contract and current authorization before invoking persistence. */
public final class ReceiptStore {
    private final DataSource dataSource;

    public ReceiptStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    /** Returns only after commit, including duplicate recovery after a previously lost response. */
    public boolean accept(ReceiptSubmission input) {
        Objects.requireNonNull(input, "Validated receipt submission is required");
        try (var connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                int inserted;
                var evidence = connection.createArrayOf("uuid", input.evidenceIds().toArray());
                try (var statement = connection.prepareStatement("""
                        INSERT INTO diagnosis.correlated_context_receipts
                        (operation_id,context_id,request_fingerprint,tenant_id,github_installation_id,
                         github_repository_id,pipeline_id,scope_revision,incident_id,execution_id,
                         evidence_ids,summary,correlation_id,context_created_at,signals,created_at)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?)
                        ON CONFLICT DO NOTHING
                        """)) {
                    statement.setObject(1, input.operationId());
                    statement.setObject(2, input.operationId());
                    statement.setString(3, input.fingerprint());
                    statement.setObject(4, input.tenantId());
                    statement.setString(5, input.installationId());
                    statement.setString(6, input.repositoryId());
                    statement.setObject(7, input.pipelineId());
                    statement.setString(8, input.scopeRevision());
                    statement.setObject(9, input.incidentId());
                    statement.setObject(10, input.executionId());
                    statement.setArray(11, evidence);
                    statement.setString(12, input.summary());
                    statement.setString(13, input.correlationId());
                    statement.setObject(14, input.contextCreatedAt().atOffset(ZoneOffset.UTC));
                    statement.setString(15, input.signalsJson());
                    statement.setObject(16, Instant.now().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC));
                    inserted = statement.executeUpdate();
                } finally {
                    evidence.free();
                }
                if (inserted == 0) {
                    // A separate statement observes the concurrent winner after ON CONFLICT waits.
                    try (var statement = connection.prepareStatement("""
                            SELECT request_fingerprint FROM diagnosis.correlated_context_receipts
                            WHERE operation_id = ? AND context_id = ?
                            """)) {
                        statement.setObject(1, input.operationId());
                        statement.setObject(2, input.operationId());
                        try (var rows = statement.executeQuery()) {
                            if (!rows.next() || !input.fingerprint().equals(rows.getString(1))) throw new Conflict();
                        }
                    }
                }
                connection.commit();
                return inserted == 1;
            } catch (SQLException | RuntimeException failure) {
                rollback(connection);
                if (failure instanceof Conflict conflict) throw conflict;
                throw new Unavailable();
            }
        } catch (SQLException failure) {
            throw new Unavailable();
        }
    }

    private static void rollback(Connection connection) {
        try { connection.rollback(); } catch (SQLException unavailable) { /* Original outcome remains unavailable. */ }
    }

    public static final class Conflict extends RuntimeException {
        public Conflict() { super("Correlated context conflicts with its accepted representation"); }
    }

    public static final class Unavailable extends RuntimeException {
        public Unavailable() { super("Correlated context receipt persistence is unavailable"); }
    }
}
