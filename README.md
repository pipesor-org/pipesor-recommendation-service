# pipesor-recommendation-service

Diagnostic & Recommendation Service orchestrates assisted diagnosis from correlated evidence, authorized RAG knowledge and external LLM inference. It composes probable-cause output and owns human review. Its canonical architecture ID is `recommendation_service`; the planned runtime is Java / Spring Boot on Azure Container Apps.

The first executable increment provides the Java / Spring Boot foundation and the
internal Recommendation → RAG v1 client. It preserves verified authorization scope,
correlation context and source provenance. The Correlation receipt increment adds
an explicitly composed internal HTTP route and durable context storage.
Diagnostic inference, public API endpoints, container images and release
automation remain outside this increment.

## Documentation

- [Documentation baseline](docs/documentation-baseline.md): audited source revisions, evidence limits and adoption after merge.

- [Architecture](docs/architecture.md): service boundaries, integration context and current sources in `pipesor-docs`.
- [Deployment contract](docs/deployment-contract.md): development baseline, delivery requirements and unresolved contracts.
- [AGENTS.md](AGENTS.md): repository instructions for coding agents.

## Development and verification

The [development baseline](docs/deployment-contract.md#development-baseline) records the selected toolchain and dependency policy. The current project targets Java 21 and pins Spring Boot 4.1.2. Run the verified checks with:

```bash
./mvnw verify
```

On Windows:

```powershell
.\mvnw.cmd verify
```

The application requires `PIPESOR_RAG_ENDPOINT` and `PIPESOR_RAG_AUDIENCE`.
The endpoint must use HTTPS and the audience has the form `api://<application-id>`.
`PIPESOR_RAG_TIMEOUT` is optional and cannot exceed the approved 15-second budget.
Authentication uses `DefaultAzureCredential`; no credentials belong in configuration
files. The client performs at most two retries for transport errors, `429` and `5xx`
responses, respects `Retry-After`, and never retries authentication, authorization or
validation rejection. Starting the application does not yet expose a diagnostic API.

### Durable Correlation receipts

`ReceiptEndpoint.routes` composes the approved internal v1 route with an explicit
data source, request byte limit and current-authority verifier. The verifier must
check the caller, resource scope and logical references before the route reads
or writes a receipt. The normal application composition does not register
this route. Its live authority, resource limits and timeout policy are still pending.

V1 creates only `diagnosis.correlated_context_receipts` and its guards, following
[DD-024 and its scope/signals supplement](https://github.com/pipesor-org/pipesor-docs/blob/main/model/domain_decisions.md#DD-024).
Run it through an authorized Flyway runner with `diagnosis` as the default schema.
An existing receipt table needs a separately verified migration path. The
application does not automatically run this migration. Upstream identifiers remain
logical references.

The receiver computes the approved canonical fingerprint and commits the scope,
context and ordered signals before returning `accepted`. A matching replay
returns `duplicate` without changing the committed receipt. A different accepted
representation under the same operation ID returns `409`. Neither result
represents a diagnosis.

`./mvnw -B verify` ran 14 tests with Java 25 compiling to release 21, an
embedded loopback HTTP server and disposable PostgreSQL 17.11. Coverage includes
receipt recovery, authorization before duplicate disclosure, exact stored signals,
concurrent insertion, commit failure, malformed or oversized requests and
immutable receipts. Tests inject synthetic authority and request bounds.

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
