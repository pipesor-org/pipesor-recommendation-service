package com.pipesor.recommendation.diagnosis.infrastructure;

import com.pipesor.recommendation.diagnosis.application.commandhandlers.PublishDiagnosisSummariesHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Polls the incident summary outbox on one thread per replica (ADR-027). Replicas coordinate only
 * through the database ({@code FOR UPDATE SKIP LOCKED} plus leases), so any number can run.
 */
public final class DiagnosisSummaryWorker implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(DiagnosisSummaryWorker.class);
    private final PublishDiagnosisSummariesHandler handler;
    private final Duration pollInterval;
    private final int batchSize;
    private ScheduledExecutorService scheduler;

    public DiagnosisSummaryWorker(PublishDiagnosisSummariesHandler handler, Duration pollInterval, int batchSize) {
        this.handler = Objects.requireNonNull(handler);
        this.pollInterval = Objects.requireNonNull(pollInterval);
        this.batchSize = batchSize;
    }

    @Override
    public synchronized void start() {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "diagnosis-summary-worker");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::poll, pollInterval.toMillis(), pollInterval.toMillis(), TimeUnit.MILLISECONDS);
        LOG.info("diagnosis_summary_worker_started pollIntervalMillis={} batchSize={}", pollInterval.toMillis(), batchSize);
    }

    void poll() {
        try {
            // Drain full batches without waiting, then rest until the next poll.
            for (int round = 0; round < 10 && handler.runOnce() == batchSize; round++) {
                if (Thread.currentThread().isInterrupted()) return;
            }
        } catch (RuntimeException failure) {
            LOG.warn("diagnosis_summary_worker_poll_failed exception={}", failure.getClass().getName());
        }
    }

    @Override
    public synchronized void stop() {
        if (scheduler == null) return;
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) LOG.warn("diagnosis_summary_worker_stop_timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        scheduler = null;
    }

    @Override
    public synchronized boolean isRunning() { return scheduler != null; }
}
