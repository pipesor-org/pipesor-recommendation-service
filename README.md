# pipesor-recommendation-service

Diagnostic & Recommendation Service orchestrates assisted diagnosis from correlated evidence, authorized RAG knowledge and external LLM inference. It composes probable-cause output and owns human review. Its canonical architecture ID is `recommendation_service`; the runtime is Java 25 / Spring Boot 4.1.2 on Azure Container Apps (workload id `recommendation`).

The service runs as one image with profiles `local` and `azure`:

- receives correlated contexts from Correlation (`POST /internal/v1/correlated-contexts`);
- diagnoses every admitted context automatically through a durable, replica-safe worker
  (RAG retrieval + Azure OpenAI inference + citation validation), see
  [diagnosis worker](docs/diagnosis-worker.md);
- serves the dashboard's diagnosis and review APIs and the GitHub Actions report.

## Documentation

- [Documentation baseline](docs/documentation-baseline.md): audited source revisions, evidence limits and adoption after merge.

- [Diagnosis worker](docs/diagnosis-worker.md): automatic diagnosis, leases, backoff and input reconstruction.
- [Human review](docs/human-review.md): review rules and API.
- [Architecture](docs/architecture.md): service boundaries, integration context and current sources in `pipesor-docs`.
- [Deployment contract](docs/deployment-contract.md): development baseline, delivery requirements and unresolved contracts.
- [AGENTS.md](AGENTS.md): repository instructions for coding agents.

## Development and verification

Run the checks (unit tests plus PostgreSQL Testcontainers; Docker required):

```bash
./mvnw -B -ntp verify
```

On Windows: `.\mvnw.cmd verify`.

### Routes

| Route | Authentication | Notes |
|---|---|---|
| `POST /internal/v1/correlated-contexts` | workload `correlation`, role `Correlation.Contexts.Deliver` | strict v1 contract, `Idempotency-Key` = context id; `200 accepted`/`duplicate`, `409 operation_conflict`, `413`, `400 invalid_context`; `Cache-Control: no-store` |
| `GET /api/v1/incidents/{incidentId}/diagnosis` | session, scope `Diagnosis.Read` | entitlement resolved with Pipeline on every request; highest admitted version for the incident and its ORIGIN execution, no fallback |
| `GET /api/v1/diagnoses/{diagnosisId}/reviews` | session, `Diagnosis.Read` | review view (status, revision, history) |
| `POST /api/v1/diagnoses/{diagnosisId}/reviews` | session, `Diagnosis.Review` + OPERATE | `{expectedRevision, decision, comment, actualCause, correctedRecommendation}`; reviewer = session subject; optional `Idempotency-Key` |
| `GET /api/v1/github-actions/repositories/{repositoryId}/runs/{runId}/attempts/{attempt}/report` | GitHub Actions OIDC (`aud=urn:pipesor:api:recommendation`) | token `repository_id` must equal the path; `{report, markdown}`, `404 diagnosis_absent` while processing |
| `GET /.well-known/jwks.json`, `/actuator/health/{liveness,readiness}` | none | readiness includes the database |
| `GET /v3/api-docs` | none, `local` profile only | OpenAPI |

Errors are `application/problem+json` with `type`, `title`, `status`, `code` and
`correlationId`. Diagnosis read codes: `404 incident_not_found` (also when the subject lacks
access), `404 diagnosis_absent`, `409 diagnosis_stale`, `503 authority_unavailable`,
`503 diagnosis_unavailable`. Every request may send `X-Correlation-ID`; it is echoed or generated.

Authentication follows the
[platform identity contract v1](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/contracts/platform-identity-v1.md):
one issuer-routing decoder sends GitHub Actions tokens (`https://token.actions.githubusercontent.com`)
to their own decoder and every other token to the platform decoder; each route accepts only its
own token kind.

**Pipeline entitlement (coordination point).** Recommendation calls Pipeline
`POST /internal/v1/diagnosis-read-entitlements` with its workload token (role
`Recommendation.DiagnosisEntitlements.Resolve`) and the caller's session token in
`X-Pipesor-Subject-Token`. Request `{"incidentId"}`. Expected `200` body: `incidentId`,
`pipelineId`, `executionId` (single failed ORIGIN), `githubInstallationId`, `githubRepositoryId`,
optional `tenantId` and `capabilities` (`["VIEW","OPERATE"]`). `403`/`404` deny; anything else is
`503 authority_unavailable`. The version is served only when its receipt matches the incident,
execution and scope (pipeline, installation, repository, and tenant when reported); otherwise
`409 diagnosis_stale`. Review writes need `OPERATE` in `capabilities`.

### Configuration

Names follow the [runtime manifest](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/development/runtime-manifest.md).

| Variable | Meaning |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` or `azure` |
| `PIPESOR_TENANT_ID`, `PIPESOR_WORKLOAD_ID` (`recommendation`), `PIPESOR_SECURITY_AUDIENCE` | identity |
| `PIPESOR_SIGNING_KEY_FILE` (local) or `PIPESOR_SIGNING_KEY_ID` (Key Vault) | workload signing key |
| `PIPESOR_SECURITY_TRUSTEDWORKLOADS_0_{ID,JWKSURI,ROLES}` | `correlation` / its JWKS / `Correlation.Contexts.Deliver` |
| `PIPESOR_SECURITY_SESSION_JWKSURI` | session JWKS (Ingestion) |
| `PIPESOR_SECURITY_GITHUBACTIONS_JWKSURI` | optional, defaults to GitHub's JWKS |
| `PIPESOR_ENDPOINTS_RAG`, `PIPESOR_ENDPOINTS_PIPELINE` | outbound base URLs (HTTPS; plain HTTP only to loopback under `local`) |
| `PIPESOR_DATABASE_URL`, `_USERNAME`, `_PASSWORD` | PostgreSQL (`azure`: passwordless Entra plugin, no password) |
| `PIPESOR_DASHBOARD_ORIGIN` | CORS origin for `/api/v1/**` |
| `PIPESOR_INFERENCE_ENDPOINT` | Foundry/Azure OpenAI account origin, e.g. `https://ai-pipesor-dev-04f339b7.services.ai.azure.com` (required under `azure`; `local` defaults to that dev account) |
| `PIPESOR_INFERENCE_DEPLOYMENT` | chat deployment, default `pipesor-dev-diagnostics` |
| `PIPESOR_INFERENCE_TIMEOUT`, `PIPESOR_INFERENCE_MAX_COMPLETION_TOKENS` | `60s`, `1024` |
| `PIPESOR_RAG_TIMEOUT` | total RAG budget, ≤ 15 s (ADR-021: at most two retries for transport, `429`, `5xx`, honoring `Retry-After`) |
| `PIPESOR_DIAGNOSIS_WORKER_ENABLED` | `true` |

Inference uses `DefaultAzureCredential` (developer `az login` locally, managed identity in
Azure) with scope `https://ai.azure.com/.default`. The system prompt and response schema are
versioned in code (`AzureOpenAiInference.PROMPT_VERSION`); each admitted hypothesis records
deployment, provider model version and prompt version. There is no fake inference in the
application; tests replace it with test-only beans.

Flyway migrates schema `diagnosis` at startup (`V1`–`V7`). V5 relaxes the signal locator CHECK
to the protected evidence 2.2 shape, V6 adds indexed `context_id`/`execution_id` to
`diagnosis.diagnoses`, V7 creates the worker's job table.

### Local run

With the shared local stack running (PostgreSQL on `127.0.0.1:55432`):

```bash
set -a; . ~/.config/pipesor-local/env/recommendation.env; set +a
./mvnw -B -ntp spring-boot:run
curl -s http://127.0.0.1:8086/actuator/health/readiness
curl -s http://127.0.0.1:8086/.well-known/jwks.json
```

Diagnoses need RAG on `PIPESOR_ENDPOINTS_RAG` and an `az login` identity with access to the
Foundry deployment; otherwise jobs retry with backoff.

### Container image

```bash
./mvnw -B -ntp package -DskipTests
docker build -t pipesor-recommendation-service .
```

The image runs as uid 10001 on port 8080.

For the current documentation foundation, check affected links and consistency with the referenced sources, then run:

```bash
git diff --check
```

For TOML configuration changes, run this syntax check with Python 3.11+ (the selected Python baseline supports `tomllib`):

```bash
python3 -c 'from pathlib import Path; import tomllib; [tomllib.loads(p.read_text()) for p in Path(".codex").rglob("*.toml")]; print("TOML syntax OK")'
```

Follow the organization [contribution policy](https://github.com/pipesor-org/.github/blob/main/CONTRIBUTING.md), [code of conduct](https://github.com/pipesor-org/.github/blob/main/CODE_OF_CONDUCT.md) and [security policy](https://github.com/pipesor-org/.github/blob/main/SECURITY.md). Licensing remains pending.

## Optional Codex setup

Start Codex from this repository root so its [repository instructions](AGENTS.md) are discovered. Project configuration is in [.codex/config.toml](.codex/config.toml); [diagnostic_reviewer](.codex/agents/diagnostic-reviewer.toml) defines a focused reviewer. Review roles do not create separate sandbox permissions.

After changing instructions, start a fresh session and ask it to identify its active instruction sources. See the official [instruction-discovery guide](https://learn.chatgpt.com/docs/agent-configuration/agents-md) and [subagent guide](https://learn.chatgpt.com/docs/agent-configuration/subagents). A successful TOML parse alone does not establish live discovery or reviewer execution.
