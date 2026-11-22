package com.pipesor.recommendation.diagnosis.infrastructure.persistence;

import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator.Diagnosis;
import com.pipesor.recommendation.diagnosis.application.model.AdmittedDiagnosis;
import com.pipesor.recommendation.diagnosis.application.ports.DiagnosisReadModel;
import com.pipesor.recommendation.retrieval.domain.AuthorizationScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads the selected version, its receipt binding and its review history in one repeatable-read
 * transaction, so the projection is consistent with the history it reports.
 */
public final class JdbcDiagnosisReadModel implements DiagnosisReadModel {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SELECT = """
            SELECT d.id, d.version, d.created_at, d.authorization_scope::text AS scope, d.output::text AS output,
                   r.context_id AS receipt_context_id, r.incident_id AS receipt_incident_id,
                   r.execution_id AS receipt_execution_id, r.tenant_id, r.github_installation_id,
                   r.github_repository_id, r.pipeline_id, r.scope_revision
            FROM diagnosis.diagnoses d
            LEFT JOIN diagnosis.correlated_context_receipts r ON r.context_id = d.context_id
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcDiagnosisReadModel(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.transactions.setReadOnly(true);
    }

    @Override
    public Optional<AdmittedDiagnosis> highestAdmitted(UUID incidentId, UUID executionId) {
        return select(SELECT + """
                WHERE d.incident_id = ? AND d.execution_id = ? AND d.admission_fingerprint IS NOT NULL
                ORDER BY d.version DESC LIMIT 1
                """, incidentId, executionId);
    }

    @Override
    public Optional<AdmittedDiagnosis> highestAdmittedForExecution(UUID executionId) {
        return select(SELECT + """
                WHERE d.execution_id = ? AND d.admission_fingerprint IS NOT NULL
                ORDER BY d.version DESC, d.created_at DESC LIMIT 1
                """, executionId);
    }

    private Optional<AdmittedDiagnosis> select(String sql, Object... arguments) {
        return transactions.execute(status -> {
            var found = jdbc.query(sql, (row, index) -> row(row), arguments);
            if (found.isEmpty()) return Optional.empty();
            var selected = found.getFirst();
            var reviews = jdbc.query("""
                    SELECT revision, decision, timestamp FROM diagnosis.human_reviews
                    WHERE diagnostic_id = ? ORDER BY revision
                    """, (row, index) -> new AdmittedDiagnosis.ReviewEntry(row.getInt("revision"),
                    row.getString("decision"), row.getObject("timestamp", OffsetDateTime.class).toInstant()),
                    selected.diagnosisId());
            return Optional.of(new AdmittedDiagnosis(selected.diagnosisId(), selected.version(), selected.createdAt(),
                    selected.scope(), selected.output(), selected.receipt(), reviews));
        });
    }

    private static AdmittedDiagnosis row(ResultSet row) throws SQLException {
        var receiptContext = row.getObject("receipt_context_id", UUID.class);
        var receipt = receiptContext == null ? null : new AdmittedDiagnosis.Receipt(receiptContext,
                row.getObject("receipt_incident_id", UUID.class), row.getObject("receipt_execution_id", UUID.class),
                new AuthorizationScope(row.getObject("tenant_id", UUID.class), row.getString("github_installation_id"),
                        row.getString("github_repository_id"), row.getObject("pipeline_id", UUID.class),
                        row.getString("scope_revision")));
        return new AdmittedDiagnosis(row.getObject("id", UUID.class), row.getInt("version"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                JSON.readValue(row.getString("scope"), AuthorizationScope.class),
                JSON.readValue(row.getString("output"), Diagnosis.class), receipt, List.of());
    }
}
