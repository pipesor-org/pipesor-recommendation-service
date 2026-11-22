# Automatic diagnosis worker

Every admitted correlated context gets a diagnosis without a human or harness action.

## Durable intent

`POST /internal/v1/correlated-contexts` commits the receipt and a `diagnosis.diagnosis_jobs`
row (`PENDING`) in one transaction. V7 back-fills a job for every receipt that existed before it
and still has no admitted diagnosis.

## Claim, lease and settlement

Each replica runs one `diagnosis-worker` thread that polls every `poll-interval`:

1. Claim up to `batch-size` due jobs (`state = 'PENDING'`, `next_attempt_at` reached, no live
   lease) with `FOR UPDATE SKIP LOCKED`, increment `attempts` and set
   `lease_until = now + lease`, all in one statement. Replicas never claim the same job.
2. Process the batch oldest first, one job at a time.
3. Settle the job (`DONE`, retry or `FAILED`) only while the claimed attempt still holds it
   (`WHERE attempts = <claimed>`). A worker whose lease expired cannot overwrite a newer claim.

A crashed or stopped replica leaves its jobs leased; they become claimable again when the lease
expires. Processing a job twice is harmless: `PersistedDiagnosticWorkflow` derives the diagnosis
id from the context and returns an already admitted diagnosis with the same admission fingerprint
without calling RAG or inference (covered by `DiagnosisWorkerTest` and `ReviewLifecycleTest`).
Version numbers per incident are assigned under a PostgreSQL advisory lock.

The lease must outlast one batch: `batch-size × (RAG budget + inference timeout)`. Defaults:
4 × (15 s + 60 s) = 5 min < 10 min lease.

## Failures and backoff

| Failure | `last_failure` | Outcome |
|---|---|---|
| Receipt cannot form inputs | `invalid_context` | `FAILED` |
| Admitted diagnosis with a different fingerprint for the context | `admission_conflict` | `FAILED` |
| Model output rejected by citation/shape validation | `invalid_hypothesis` | retry |
| RAG `401/403/422` | `rag_rejected` | retry |
| RAG unavailable after its ADR-021 retry budget | `rag_unavailable` | retry |
| Inference transport/provider failure | `inference_failed` | retry |
| Database failure | `storage_unavailable` | retry |
| Anything else | `unexpected_failure` | retry |

A retry waits `initial-backoff × 2^(attempt-1)`, capped at `max-backoff`. After `max-attempts`
the job becomes `FAILED`; the incident read keeps answering `404 diagnosis_absent` and the
failure is logged at ERROR with context id, reason, exception class and correlation id (never
evidence or provider payloads). An operator can requeue a job with
`UPDATE diagnosis.diagnosis_jobs SET state = 'PENDING', next_attempt_at = now() WHERE context_id = ...`.

## Input reconstruction

`ContextReconstruction` uses only Recommendation's own receipt (`diagnosis` schema), replacing
the Correlation harness's raw-SQL shortcut:

- evidence: every admitted signal in delivery order (id, origin context, stage, sanitized
  content, source locator);
- retrieval query: the single signal's content; for several signals, their contents in delivery
  order joined by a blank line and cut at 4000 characters (the RAG v1 limit);
- corpus: `authorized_technical_documentation`; `topK` = number of signals, at most 3;
- authorization scope and correlation id: the receipt's.

The harness only exercised single-signal receipts. The multi-signal rule above is a
conservative choice of this service: every signal still reaches inference and citation
validation; only the RAG query text is bounded. Single-signal inputs equal the harness's, so
admissions it created keep replaying.

## Configuration

| Property | Default |
|---|---|
| `pipesor.diagnosis-worker.enabled` (`PIPESOR_DIAGNOSIS_WORKER_ENABLED`) | `true` |
| `pipesor.diagnosis-worker.poll-interval` | `5s` |
| `pipesor.diagnosis-worker.batch-size` | `4` |
| `pipesor.diagnosis-worker.lease` | `10m` |
| `pipesor.diagnosis-worker.initial-backoff` / `max-backoff` | `30s` / `30m` |
| `pipesor.diagnosis-worker.max-attempts` | `8` |
