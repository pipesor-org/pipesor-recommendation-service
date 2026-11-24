package com.pipesor.recommendation.diagnosis.infrastructure.persistence;

import com.pipesor.recommendation.diagnosis.application.ports.DiagnosisSummaryOutbox;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code diagnosis.incident_summary_outbox}. Rows are written by {@code ReviewStore} in the same
 * transaction as the admission or review. Claims skip rows locked by other replicas and lease the
 * rest; the lease end returned by the claim fences every later update, and only removal is keyed
 * by the generation that was read.
 */
public final class JdbcDiagnosisSummaryOutbox implements DiagnosisSummaryOutbox {
    private final JdbcTemplate jdbc;

    public JdbcDiagnosisSummaryOutbox(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public List<Claim> claim(int limit, Duration lease) {
        return jdbc.query("""
                WITH due AS (
                    SELECT incident_id, execution_id FROM diagnosis.incident_summary_outbox
                    WHERE next_attempt_at <= clock_timestamp()
                      AND (lease_until IS NULL OR lease_until <= clock_timestamp())
                    ORDER BY next_attempt_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED),
                claimed AS (
                UPDATE diagnosis.incident_summary_outbox o
                SET lease_until = clock_timestamp() + make_interval(secs => ?)
                FROM due WHERE o.incident_id = due.incident_id AND o.execution_id = due.execution_id
                RETURNING o.incident_id, o.execution_id, o.generation, o.attempts, o.lease_until, o.next_attempt_at,
                          o.created_at)
                SELECT incident_id, execution_id, generation, attempts, lease_until
                FROM claimed ORDER BY next_attempt_at, created_at
                """, (row, index) -> new Claim(row.getObject("incident_id", UUID.class),
                        row.getObject("execution_id", UUID.class), row.getLong("generation"), row.getInt("attempts"),
                        row.getObject("lease_until", OffsetDateTime.class).toInstant()),
                limit, seconds(lease));
    }

    @Override
    public boolean complete(Claim claim) {
        int deleted = jdbc.update("""
                DELETE FROM diagnosis.incident_summary_outbox
                WHERE incident_id = ? AND execution_id = ? AND generation = ?
                """, claim.incidentId(), claim.executionId(), claim.generation());
        if (deleted > 0) return true;
        // A newer generation was enqueued while this one was pushed: hand it over, due now.
        jdbc.update("""
                UPDATE diagnosis.incident_summary_outbox SET lease_until = NULL, next_attempt_at = clock_timestamp()
                WHERE incident_id = ? AND execution_id = ? AND lease_until = ?
                """, claim.incidentId(), claim.executionId(), lease(claim));
        return false;
    }

    @Override
    public void retry(Claim claim, Duration delay, String reason) {
        int updated = jdbc.update("""
                UPDATE diagnosis.incident_summary_outbox
                SET attempts = attempts + 1, lease_until = NULL, last_failure = ?,
                    next_attempt_at = clock_timestamp() + make_interval(secs => ?)
                WHERE incident_id = ? AND execution_id = ? AND generation = ? AND lease_until = ?
                """, reason, seconds(delay), claim.incidentId(), claim.executionId(), claim.generation(), lease(claim));
        // A newer generation (attempts reset, due now) is not deferred by this failure.
        if (updated == 0) release(claim);
    }

    @Override
    public void release(Claim claim) {
        jdbc.update("""
                UPDATE diagnosis.incident_summary_outbox SET lease_until = NULL
                WHERE incident_id = ? AND execution_id = ? AND lease_until = ?
                """, claim.incidentId(), claim.executionId(), lease(claim));
    }

    private static OffsetDateTime lease(Claim claim) {
        return OffsetDateTime.ofInstant(claim.leaseUntil(), ZoneOffset.UTC);
    }

    private static double seconds(Duration duration) {
        return (double) duration.toMillis() / 1000d;
    }
}
