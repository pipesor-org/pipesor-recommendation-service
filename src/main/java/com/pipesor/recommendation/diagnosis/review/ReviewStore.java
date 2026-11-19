package com.pipesor.recommendation.diagnosis.review;

import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator.Diagnosis;
import com.pipesor.recommendation.retrieval.domain.AuthorizationScope;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Recommendation owns the immutable output and its append-only review history. */
public final class ReviewStore {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final DataSource source;

    public ReviewStore(DataSource source) {
        this.source = Objects.requireNonNull(source);
    }

    public void save(UUID diagnosticId, int version, AuthorizationScope scope, Diagnosis output) throws SQLException {
        Objects.requireNonNull(scope);
        try (var connection = source.getConnection();
             var insert = connection.prepareStatement("""
                     INSERT INTO diagnosis.diagnoses
                       (id, incident_id, version, correlation_id, authorization_scope, output)
                     VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb)
                     """)) {
            insert.setObject(1, diagnosticId);
            insert.setObject(2, output.incidentId());
            insert.setInt(3, version);
            insert.setString(4, output.correlationId());
            insert.setString(5, JSON.writeValueAsString(scope));
            insert.setString(6, JSON.writeValueAsString(output));
            insert.executeUpdate();
        }
    }

    Snapshot read(UUID id) throws SQLException {
        try (var connection = source.getConnection()) {
            return read(connection, id, false);
        }
    }

    private Snapshot read(Connection connection, UUID id, boolean lock) throws SQLException {
        try (var select = connection.prepareStatement("""
                SELECT version, authorization_scope, output FROM diagnosis.diagnoses WHERE id = ?
                """ + (lock ? " FOR UPDATE" : ""))) {
            select.setObject(1, id);
            try (var row = select.executeQuery()) {
                if (!row.next()) return null;
                var reviews = new ArrayList<Review>();
                try (var history = connection.prepareStatement("""
                        SELECT id, revision, reviewer_ref, decision, timestamp, correlation_id,
                               actual_cause, comment, corrected_recommendation
                        FROM diagnosis.human_reviews WHERE diagnostic_id = ? ORDER BY revision
                        """)) {
                    history.setObject(1, id);
                    try (var entries = history.executeQuery()) {
                        while (entries.next()) {
                            reviews.add(new Review(entries.getObject("id", UUID.class), entries.getInt("revision"),
                                    entries.getString("reviewer_ref"), Decision.valueOf(entries.getString("decision")),
                                    entries.getObject("timestamp", OffsetDateTime.class), entries.getString("correlation_id"),
                                    entries.getString("actual_cause"), entries.getString("comment"),
                                    entries.getString("corrected_recommendation")));
                        }
                    }
                }
                return new Snapshot(id, row.getInt("version"),
                        JSON.readValue(row.getString("authorization_scope"), AuthorizationScope.class),
                        JSON.readValue(row.getString("output"), Diagnosis.class), List.copyOf(reviews));
            }
        }
    }

    Snapshot append(Snapshot target, UUID requestId, String reviewer, Submission submission) throws SQLException {
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var current = read(connection, target.diagnosticId(), true);
                if (current == null || current.version() != target.version()
                        || current.version() != submission.diagnosisVersion()
                        || !current.scope().equals(target.scope())) throw new Conflict();
                for (var previous : current.history()) {
                    if (!previous.id().equals(requestId)) continue;
                    if (!previous.reviewer().equals(reviewer) || previous.revision() - 1 != submission.expectedRevision()
                            || previous.decision() != submission.decision()
                            || !previous.comment().equals(submission.comment())
                            || !Objects.equals(previous.actualCause(), submission.actualCause())
                            || !Objects.equals(previous.correctedRecommendation(), submission.correctedRecommendation())) {
                        throw new Conflict();
                    }
                    connection.commit();
                    return current;
                }
                if (current.history().size() != submission.expectedRevision()) throw new Conflict();
                try (var insert = connection.prepareStatement("""
                        INSERT INTO diagnosis.human_reviews
                         (id, diagnostic_id, revision, reviewer_ref, decision, correlation_id,
                          actual_cause, comment, corrected_recommendation) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    insert.setObject(1, requestId);
                    insert.setObject(2, current.diagnosticId());
                    insert.setInt(3, submission.expectedRevision() + 1);
                    insert.setString(4, reviewer);
                    insert.setString(5, submission.decision().name());
                    insert.setString(6, current.original().correlationId());
                    insert.setString(7, submission.actualCause());
                    insert.setString(8, submission.comment());
                    insert.setString(9, submission.correctedRecommendation());
                    insert.executeUpdate();
                }
                var result = read(connection, current.diagnosticId(), false);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                if (failure instanceof SQLException sql && "23505".equals(sql.getSQLState())) throw new Conflict();
                throw failure;
            }
        }
    }

    public enum Decision { CONFIRMED, CORRECTED, REJECTED }
    public record Submission(int diagnosisVersion, int expectedRevision, Decision decision, String comment,
                             String actualCause, String correctedRecommendation) {}
    public record Review(UUID id, int revision, String reviewer, Decision decision, OffsetDateTime timestamp,
                         String correlationId, String actualCause, String comment, String correctedRecommendation) {}
    record Snapshot(UUID diagnosticId, int version, AuthorizationScope scope, Diagnosis original, List<Review> history) {}
    static final class Conflict extends RuntimeException {}
}
