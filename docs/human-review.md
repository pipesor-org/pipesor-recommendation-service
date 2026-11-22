# Increment 6 · Local diagnostic review

P3 from the manuscript v1.2 §3.3 states “la revisión humana distingue hipótesis generada de diagnóstico confirmado, corregido o rechazado”. This increment exercises that distinction on a persisted snapshot through Recommendation's local review API, with synthetic reviewer permissions and an actual PostgreSQL 17 database.

## Traceability and boundaries

| Source | Implementation |
| --- | --- |
| ADR-011, ADR-016 and ADR-017 | Recommendation owns review and its audit history in PostgreSQL. The API derives the reviewer from the authority and the correlation identifier from the original output. The database supplies the review timestamp. |
| DD-005 and A-012 | PENDING derives from an empty history. CONFIRMED records acceptance, CORRECTED requires an actual cause, and REJECTED records rejection without a validated alternative. Each authorized submission appends a new revision. The latest review supplies the displayed state; prior decisions remain readable. |
| ADR-020 | Authorization is an injected, fail-closed boundary. The local test authority models a bound scope with VIEW for reads and OPERATE for writes. It denies users without the matching scope and simulates an unavailable authority. |
| PD-011 and PD-034 | The local route reconstructs the Recommendation-owned admitted snapshot. It introduces no cross-service lookup or production API contract. The production projection, persistence handoff from live inference and user authorization bindings still need integration and review. |

Sources live in the [architecture ADR index](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/index.md), [domain decisions](https://github.com/pipesor-org/pipesor-docs/blob/main/model/domain_decisions.md) and [pending decisions](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md).

The fixture deliberately exercises the approved domain decision via a local storage projection. The owned diagnosis snapshot preserves the hypothesis, limitations, evidence references, excerpts and RAG links together. The migration stores a review revision as an ordering and optimistic-concurrency guard. The optional corrected recommendation is a local review observation, separate from the original output and from the required corrected cause. Reviewers supply sanitized text and a justification. Existing evidence and diagnostics from the experimental campaigns remain unchanged.

## Local endpoint

The route is composed explicitly by the integration fixture on loopback, with no default registration in the running application. Its prefix and test identity mechanism are the existing local-demo boundary.

- `GET /local-demo/v1/diagnoses/{id}/review` returns the diagnosis ID and version, original output, derived review status and ordered history. The response uses `Cache-Control: no-store`.
- `POST` on the same path takes a UUID `Idempotency-Key` and JSON fields `diagnosisVersion`, `expectedRevision`, `decision`, required `comment` and optional `actualCause` and `correctedRecommendation`. The last two fields are accepted only for CORRECTED. The current latest revision is zero when there is no history. The response supplies the full updated view.
- The authenticated subject comes from the authority. Missing or invalid credentials yield 401. A resource whose scope cannot be used yields 404. Authority or database failure yields 503. Invalid commands yield 400 (or 413/415 for size/media type). Stale revision, wrong diagnosis version, and conflicting reuse of an idempotency key yield 409.
- Under a PostgreSQL row lock, one command for a given observed revision wins. A matching repeat by the same reviewer does not append a row and returns the current view, even if later reviews already exist. A changed command with a reused key conflicts. The original output is stored with an insert-only service API and the review path writes only to the review table.

The React route `/diagnostic-demo?review=<diagnosis-uuid>` preserves the original evidence display, labels the original hypothesis, and exposes the decision form and chronological history. It retains the idempotency key for the same failed submission, clears stale content on access failure, and requires a reload on a conflict.

## Reproduce using synthetic identities

Run from the Recommendation checkout with Java, Maven, Docker, and the sibling frontend dependencies installed. Use a fresh evidence path.

```bash
umask 077
out="$(mktemp -d)"
npm install --prefix "$out/browser" --no-save playwright
"$out/browser/node_modules/.bin/playwright" install chromium
export PIPESOR_PLAYWRIGHT_MODULE="$out/browser/node_modules/playwright"
export PIPESOR_REVIEW_BROWSER_CHECK="$PWD/../../pipesor-frontend/tools/verify_local_review.cjs"
export PIPESOR_REVIEW_RECEIPT="$out/receipt.json"
./mvnw -B -ntp verify
(cd "$out" && sha256sum receipt.json > SHA256SUMS && sha256sum -c SHA256SUMS)
```

To use a compatible preinstalled Chromium, set `PIPESOR_CHROMIUM_EXECUTABLE` to its binary. Without `PIPESOR_REVIEW_BROWSER_CHECK`, the suite exercises HTTP and persistence only.

The integration test seeds snapshots through Recommendation's store. The E05 test copy is the `diagnosis` node from `E05/receipt.json` inside the preserved Increment 5 bundle. The bundle SHA-256 is `93fed2f5cb976b78810ac3ae5537b8241c0666aa6be3cd2537098bcb08aac1ab`. The repository copy SHA-256 is `e14a00f51c305adc5314e676421c5c6c8a87be5ac2e5aba362376b6f515e97bf`. Replays need no inference or writes to the source evidence.

The E05 excerpt is derived from Travis build 566824752 in Carolin Brandt, Annibale Panichella and Moritz Beller, [LogChunks v1.0.0](https://doi.org/10.5281/zenodo.3632351), [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). The reviewed copy removes the source path, compiler location and surrounding output, and includes synthetic fixture framing.

## Executed evidence

On 2026-11-19, PostgreSQL 17.11 and the Recommendation HTTP test recorded all three outcomes and read them after a service-context restart. A confirmed case accepted one of two concurrent updates and denied the stale competing revision. The test exercised an exact idempotent replay, conflicting reuse, version mismatch, malformed corrections, forged reviewer fields, read-only scope, cross-tenant access, invalid credentials, and an unavailable authority. The original E05 hypothesis and both citation sets were unchanged.

Chromium then submitted and reloaded each outcome on separate persisted test snapshots. E05's second fixture version used the Spanish view at 390 px. The confirmation and rejection used the English view at 1280 px. All three cleared the rendered hypothesis after HTTP 401. Recommendation's full suite passed 23 tests. Frontend type checking, lint, and production build passed. The build reported existing Rollup annotation and bundle-size warnings.

P3 is supported by these local synthetic-identity and PostgreSQL executions. The fixture transcript demonstrates the lifecycle with programmatically exercised reviewer identities. Live reviewer acceptance, production identity bindings, the production read projection and live snapshot-admission wiring remain pending.
