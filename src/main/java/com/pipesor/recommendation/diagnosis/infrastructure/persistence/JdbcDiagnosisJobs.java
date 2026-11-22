package com.pipesor.recommendation.diagnosis.infrastructure.persistence;

import com.pipesor.recommendation.diagnosis.application.ports.DiagnosisJobs;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code diagnosis.diagnosis_jobs}. Claims skip rows locked by other replicas and lease the rest;
 * settlement is fenced by the claimed attempt so a worker whose lease expired cannot overwrite a
 * newer claim.
 */
public final class JdbcDiagnosisJobs implements DiagnosisJobs {
    private final JdbcTemplate jdbc;

    public JdbcDiagnosisJobs(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public List<Job> claim(int limit, Duration lease) {
        // Oldest first, so versions of one incident follow receipt order within a batch.
        return jdbc.query("""
                WITH due AS (
                    SELECT context_id FROM diagnosis.diagnosis_jobs
                    WHERE state = 'PENDING' AND next_attempt_at <= clock_timestamp()
                      AND (lease_until IS NULL OR lease_until <= clock_timestamp())
                    ORDER BY next_attempt_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED),
                claimed AS (
                UPDATE diagnosis.diagnosis_jobs j
                SET attempts = j.attempts + 1, lease_until = clock_timestamp() + make_interval(secs => ?)
                FROM due WHERE j.context_id = due.context_id
                RETURNING j.context_id, j.attempts, j.next_attempt_at, j.created_at)
                SELECT context_id, attempts FROM claimed ORDER BY next_attempt_at, created_at
                """, (row, index) -> new Job(row.getObject("context_id", UUID.class), row.getInt("attempts")),
                limit, (double) lease.toMillis() / 1000d);
    }

    @Override
    public void complete(Job job) {
        jdbc.update("""
                UPDATE diagnosis.diagnosis_jobs SET state = 'DONE', lease_until = NULL, last_failure = NULL
                WHERE context_id = ? AND attempts = ? AND state = 'PENDING'
                """, job.contextId(), job.attempt());
    }

    @Override
    public void retry(Job job, Duration delay, String reason) {
        jdbc.update("""
                UPDATE diagnosis.diagnosis_jobs
                SET lease_until = NULL, last_failure = ?, next_attempt_at = clock_timestamp() + make_interval(secs => ?)
                WHERE context_id = ? AND attempts = ? AND state = 'PENDING'
                """, reason, (double) delay.toMillis() / 1000d, job.contextId(), job.attempt());
    }

    @Override
    public void fail(Job job, String reason) {
        jdbc.update("""
                UPDATE diagnosis.diagnosis_jobs SET state = 'FAILED', lease_until = NULL, last_failure = ?
                WHERE context_id = ? AND attempts = ? AND state = 'PENDING'
                """, reason, job.contextId(), job.attempt());
    }
}
