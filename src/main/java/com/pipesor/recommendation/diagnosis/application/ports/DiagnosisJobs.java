package com.pipesor.recommendation.diagnosis.application.ports;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Durable diagnosis work queue. A claim leases each job for a bounded time and increments its
 * attempt; completion, retry and failure apply only while that attempt still holds the job.
 */
public interface DiagnosisJobs {
    List<Job> claim(int limit, Duration lease);

    void complete(Job job);

    void retry(Job job, Duration delay, String reason);

    void fail(Job job, String reason);

    record Job(UUID contextId, int attempt) { }
}
