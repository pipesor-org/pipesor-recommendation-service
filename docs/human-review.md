# Human review

P3 (manuscript v1.2 §3.3): human review distinguishes a generated hypothesis from a confirmed,
corrected or rejected diagnosis. The review API replaced the former `/local-demo/v1/...` routes.

## Rules

| Source | Implementation |
| --- | --- |
| ADR-011, ADR-016, ADR-017 | Recommendation owns the immutable diagnosis and its append-only review history (`diagnosis.human_reviews`). The reviewer is the session subject (`oid`), never a body field; the correlation id comes from the original output; the database supplies the timestamp. |
| DD-005, A-012 | `PENDING` derives from an empty history. `CONFIRMED` records acceptance, `CORRECTED` requires `actualCause`, `REJECTED` records rejection. Each submission appends a revision; the latest one sets the displayed status. |
| ADR-020, MVP HTTP API v1 | Every request resolves the incident entitlement with Pipeline and requires it to cover the diagnosis scope (otherwise `404 not_found`); writes also need `OPERATE` (otherwise `403`). An indeterminate authority answers `503 authority_unavailable`. |

## API

- `GET /api/v1/diagnoses/{diagnosisId}/reviews` (scope `Diagnosis.Read`) returns
  `diagnosticId`, `diagnosisVersion`, `reviewRevision`, `status`, the `original` output and the
  ordered `history`. `Cache-Control: no-store`.
- `POST` on the same path (scope `Diagnosis.Review`) takes
  `{"expectedRevision": n, "decision": "CONFIRMED|CORRECTED|REJECTED", "comment": "...",
  "actualCause": "..."|null, "correctedRecommendation": "..."|null}`; `diagnosisVersion` is
  optional and, when sent, must equal the reviewed version. `actualCause` and
  `correctedRecommendation` are accepted only for `CORRECTED`. An optional UUID
  `Idempotency-Key` makes a retried identical submission return the current view; a changed
  submission under the same key, a stale `expectedRevision` or a concurrent winner yields
  `409 review_conflict`. Invalid bodies yield `400 invalid_review` (`413`/`415` for size/media type).

The incident diagnosis read (`GET /api/v1/incidents/{incidentId}/diagnosis`) exposes only
revision, decision and timestamp of each review, not reviewer identity or free text.

Store-level behavior (idempotency, optimistic concurrency, concurrent writes) is covered by
`ReviewLifecycleTest`; HTTP authorization by `RecommendationApiTest`.
