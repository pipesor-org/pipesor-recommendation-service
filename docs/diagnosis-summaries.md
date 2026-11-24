# Diagnosis summaries for Pipeline's incident queue

Pipeline serves the dashboard's incident queue and filters, counts and computes Insights by
diagnosis status before paging. Recommendation owns diagnoses and reviews, so it pushes a minimal
summary of each incident's diagnosis to Pipeline through a transactional outbox
([ADR-027](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/ADR-027-incident-queue-read-model.md)).
Pipeline's projection is a read model; the Incident Workspace keeps reading the full diagnosis,
evidence and reviews from this service.

## Outbox

`diagnosis.incident_summary_outbox` holds one row per `(incident_id, execution_id)` with pending
changes (logical references, no foreign keys):

- `ReviewStore.admit` (a new admitted version) and `ReviewStore.append` (a new review) upsert the
  row in the same transaction as the change: a new row starts at `generation = 1`; an existing one
  gets `generation + 1`, `attempts = 0`, `next_attempt_at = now`, `last_failure = NULL` and keeps
  its lease. Idempotent replays (an already admitted context, a replayed `Idempotency-Key`) and
  rolled-back transactions write nothing.
- V9 backfills one row per distinct incident and execution of every admitted diagnosis.

## Publishing

Each replica runs one `diagnosis-summary-worker` thread that polls every `poll-interval`:

1. Claim up to `batch-size` due rows (`next_attempt_at` reached, no live lease) with
   `FOR UPDATE SKIP LOCKED` and set `lease_until = now + lease`. Replicas never claim the same row.
   The returned lease end fences every later update of that claim.
2. Read the current state with the Workspace selection
   (`DiagnosisReadModel.highestAdmitted(incidentId, executionId)`, reviews included) and require
   the Workspace binding rule (`Bindings.admittedFor`: the version was admitted from a receipt of
   this incident and execution under the same scope).
3. `PUT {PIPESOR_ENDPOINTS_PIPELINE}/internal/v1/incidents/{incidentId}/diagnosis-summary` with a
   workload token (target `pipeline`, role `Recommendation.DiagnosisSummaries.Write`, no subject
   token), `Content-Type`/`Accept: application/json` and `X-Correlation-ID` set to the diagnosis
   correlation id. The body follows the
   [MVP HTTP API v1](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/contracts/mvp-http-api-v1.md)
   section "Internal: diagnosis summaries":

   | Field | Source |
   |---|---|
   | `diagnosisId`, `version`, `executionId`, `createdAt` | selected admitted version |
   | `pipelineId`, `githubRepositoryId` | its authorization scope |
   | `status`, `reviewRevision`, `reviewedAt` | last review (decision, revision, time) or `PENDING`, `0`, `null`, exactly as the Workspace projection |
   | `probableCause` | inferred hypothesis cause, cut at 1000 Unicode code points; `null` when blank |

Pushes start only during the first half of the lease; the rest of a batch is released untouched
and claimed again, so a push never outlives its claim (the lease must be at least twice
`pipesor.pipeline.timeout`). Pipeline applies summaries monotonically on
`(version, reviewRevision)`, so duplicate or late pushes after an expired lease are harmless.

## Outcomes

| Outcome | Settlement | `last_failure` / log |
|---|---|---|
| `200` echoing the sent `incidentId`, `diagnosisId`, `version` and `status` | delete where `generation` = the claimed one | INFO `diagnosis_summary_published` |
| No admitted version, binding rule fails or scope without pipeline id | delete where `generation` = the claimed one | WARN `diagnosis_summary_skipped reason=diagnosis_absent\|binding_mismatch\|pipeline_scope_missing` |
| `400`, `409` (permanent refusal) | delete where `generation` = the claimed one | WARN `diagnosis_summary_rejected status=…` |
| `200` with another echo or an invalid body | retry | `pipeline_invalid_response` |
| `401`, `403`, `404`, `429`, `5xx`, other statuses | retry | `pipeline_status_<code>` |
| Timeout, transport failure, interruption | retry | `pipeline_timeout`, `pipeline_transport_failure`, `pipeline_interrupted` |
| Read model failure, unexpected exception | retry | `storage_unavailable`, `unexpected_failure` |

When the delete finds a newer generation (a review or version committed during the push), the
claim is released and the row is due immediately, so the newer state is pushed next. A retry
increments `attempts`, clears the lease and waits `initial-backoff × 2^(attempts-1)`, capped at
`max-backoff`. Rows are never abandoned: at the cap every attempt logs `diagnosis_summary_retry`
at ERROR (WARN before). Logs carry identifiers, status codes, reason codes and exception classes
only, never the summary body or tokens.

**Deployment prerequisite.** Pipeline's trust configuration must grant role
`Recommendation.DiagnosisSummaries.Write` to the trusted `recommendation` caller (IaC
`modules/workloads` and the local stack). Until then pushes fail with `403`, keep retrying at
`max-backoff`, and every incident reads as `ABSENT` in the queue; they drain once the role is granted.

## Configuration

| Property | Default |
|---|---|
| `pipesor.diagnosis-summary.enabled` (`PIPESOR_DIAGNOSIS_SUMMARY_ENABLED`) | `true` |
| `pipesor.diagnosis-summary.poll-interval` | `2s` |
| `pipesor.diagnosis-summary.batch-size` | `20` |
| `pipesor.diagnosis-summary.lease` | `30s` |
| `pipesor.diagnosis-summary.initial-backoff` / `max-backoff` | `2s` / `10m` |
| `pipesor.pipeline.timeout` (whole exchange, shared with the entitlement call) | `5s` |

With the worker disabled, admissions and reviews still enqueue; the rows drain once it is enabled.

## Verification

`IncidentSummaryOutboxTest` covers the enqueue transaction, replays, a commit that fails after the
outbox write and the V9 backfill; `DiagnosisSummaryWorkerTest` covers the exact request against a
loopback Pipeline stand-in, review projection, backoff, permanent refusals, the generation race,
the push window, replica claims and the code-point bound; `RecommendationApiTest` checks the wired
application end to end.
