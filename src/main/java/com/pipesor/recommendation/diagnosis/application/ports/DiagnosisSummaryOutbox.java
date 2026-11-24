package com.pipesor.recommendation.diagnosis.application.ports;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Transactional outbox of incident diagnosis summaries (ADR-027). Each pending (incident, execution)
 * carries a generation that every enqueue increments. A claim leases the row; settlement removes it
 * only while it still holds the claimed generation, and updates it only while the claim still holds
 * the lease, so neither a newer enqueue nor a newer claim is lost.
 */
public interface DiagnosisSummaryOutbox {
    List<Claim> claim(int limit, Duration lease);

    /**
     * Settles the claimed generation: removes the row when it still holds that generation and returns
     * {@code true}; otherwise (a newer generation was enqueued meanwhile) releases the claim so the row
     * is due now and returns {@code false}.
     */
    boolean complete(Claim claim);

    /** Counts a failed attempt of the claimed generation and defers it; a newer generation is only released. */
    void retry(Claim claim, Duration delay, String reason);

    /** Releases the claim without counting an attempt. */
    void release(Claim claim);

    /** {@code leaseUntil} identifies the claim; settlement is fenced by it. */
    record Claim(UUID incidentId, UUID executionId, long generation, int attempts, Instant leaseUntil) { }
}
