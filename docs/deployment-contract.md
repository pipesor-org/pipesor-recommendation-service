# Deployment contract: recommendation_service

**Status: readiness record; not approved for IaC consumption or deployment.** Prepared for PD-035. [Architecture consumption](architecture.md) records the consulted source revision `c84ef3e4629fcb0c38315f32b641a5fb949e450d`, model IDs, applicable ADRs and committed PD-033–036. ADR-019 governs the current MVP alignment.

**Documented fact** means recorded in those sources/policies, not proven implementation or independent approval evidence. **Proposal** requires acceptance; **Unknown** remains pending. No application images, endpoints, credentials or migrations are implemented in this service increment. IaC contains infrastructure preparation; provisioning is not evidenced here.

## Identity and runtime

| Requirement | Value | Source and status |
|---|---|---|
| Identity and responsibility | `recommendation_service` — Orchestrate assisted diagnosis with correlated context, authorized RAG knowledge and external LLM inference; compose probable-cause output and own human review. | Documented fact: `elements[recommendation_service]`, ADR-018. |
| Independent deployment | Yes; separate backend repository/container on Microsoft Azure Container Apps. | Documented fact: `deployment.container_hosts.recommendation_service`, ADR-001/015/018 as applicable. No deployed service resource is evidenced by this repository. |
| Language/framework | Java / Spring Boot. | Documented fact: element `backend`, ADR-018. See the session-authorized development baseline below. Dependency pins and base-image selection remain implementation/delivery requirements under PD-032/035. |

## Development baseline

User direction recorded on 2026-11-06 retains the installed Java 25.0.4.1, Python 3.14.8, Docker 29.9.3 and Git 2.55.0. Java services use Maven (installed: 3.10.0); Azure CLI 2.92.0 is available for separately authorized operations. Select the latest stable dependency versions compatible with that baseline, and pin them when application build/dependency files are introduced. Framework and transitive dependency compatibility must be checked together; availability on this workstation is not container/CI verification. Python dependencies belong in isolated project environments.

This session direction supplements the consulted architecture revision; it does not close PD-032's identity/hash choices or PD-035's base-image and delivery requirements. No application build files are introduced by this documentation update.

## Integrated infrastructure baseline

The [IaC resource baseline](https://github.com/pipesor-org/pipesor-iac/blob/main/docs/mvp-resource-baseline.md) at `pipesor-iac@4b42c0c8e66578dfb890ae1cca8c9c31c774026e` selects GHCR and system-assigned identity per future Container App. Identity is created with its app; it is not an existing identity or a scoped access grant. The [native-ingress decision](https://github.com/pipesor-org/pipesor-iac/blob/main/docs/adr-003-mvp-native-ingress.md) preserves service-side authorization, and [ADR 004](https://github.com/pipesor-org/pipesor-iac/blob/main/docs/adr-004-mvp-state-and-deployment.md) restricts current preparation to dev with separately authorized activation and Azure actions.

Shared opt-in Terraform foundations include the external-capable ACA environment/VNet, Entra-only public Service Bus Standard namespace, private Entra-only PostgreSQL 17 Flexible Server (B2s, 32 GiB, seven-day local backups, no HA), shared Log Analytics/Application Insights and public Entra-only Foundry account/project. These are approved IaC preparation choices with checked-in infrastructure code, not evidence of deployed or reachable dependencies. They do not add a database, broker or AI relationship to a service that lacks one in the architecture model.

PostgreSQL's `vector` allowlist setting does not execute SQL extension creation, schemas, migrations or grants. Broker entities/delivery semantics, SQL/token access, operator/migration connectivity, instrumentation, model/API compatibility, image pulls and required per-service contracts remain pending. Platform/database backup and telemetry retention do not implement the 365-day evidence policy. Read the [documentation baseline](documentation-baseline.md) for source and evidence limits.

## Artifact and responsibility boundary

| Requirement | Value | Source and status |
|---|---|---|
| Delivery unit | Container image for this service. | Documented fact: `deployment.containerization`, ADR-001/018. |
| Immutable identity | Associate the image with its source commit and digest; promote the same digest through the staged delivery flow. | Documented organization target: CONTRIBUTING artifact promotion. No digest, version, image or automation exists. |
| Release/version | Policy targets Conventional-Commit-derived SemVer and protected `vX.Y.Z` tags after staging, without rebuilding. | Documented organization target, not configured release tooling. GHCR is selected in the integrated IaC baseline; image path/tag convention, build/publish responsibility and private-pull credentials remain pending under PD-035. |
| Ownership handoff | Proposed: this service's maintainers supply the tested image/digest and runtime/configuration requirements; IaC maintainers consume the approved delivery inputs for the agreed environment. | Proposal under PD-035; no named build/publish owner or accepted handoff contract is documented. |
| Promotion responsibility | Independent QA approval before staging and independent product-owner approval before production are policy targets. | Documented organization target. Concrete automation, environment mapping and operational owners remain unknown under PD-035; do not infer them from the sibling IaC implementation. |

Current target: only `dev` is selected for temporary academic MVP preparation (ADR-019 and IaC ADR 004). Local service integration/validation, complete readiness/access/cost review and explicit target/action authorization precede Azure writes. The dev procedural approval exception does not extend to future environments; retain independent QA/production promotion rules when commissioned.

## Execution

Documented fact: Documented synchronous REST/HTTPS context from correlation and queries/review operations from the dashboard through ACA native ingress (rel_dashboard_recommendation_service). GitHub Actions queries Diagnostic directly (rel_github_actions_diagnostic), then publishes its own artifact; workflow authorization is unresolved PD-036. Sources: service relationships listed in [architecture consumption](architecture.md), ADR-015/016/019 where applicable.

| Pending runtime value | Status and approval required |
|---|---|
| Listening port and bind address | Unknown, PD-035; framework defaults are not approved contract values. |
| Ingress scope, routing, TLS termination and caller authorization | Unknown beyond the documented caller/protocol above; PD-034/035, and PD-036 for workflow access where used. Do not add public exposure. |
| Liveness/readiness/startup probes | Paths, protocol, port, criteria and timings unknown, PD-035. No health endpoint is selected or implemented. |
| Startup | Initialization order, dependency retries, startup budget and migration coordination unknown, PD-035. |
| Shutdown | Graceful termination/drain behavior, time budget and in-flight work handling unknown, PD-035; broker consumers also depend on PD-033 re-delivery agreement. |

## Configuration and secrets

| Category | Known requirement and supplier | Status/source |
|---|---|---|
| Nonsecret integration settings | PostgreSQL diagnosis connection target, rag_retrieval address, Foundry-backed inference endpoint/deployment (application API/SDK compatibility unresolved) and diagnostic configuration. GPT-4 family is documented; exact LLM variant is intentionally unknown (PD-026). Confidence/risk/API values remain pending PD-004/009/010/011/034. Actual environment values and their suppliers are not assigned. | Targets/capabilities are documented in the model; exact settings and supplier allocation are unknown. |
| Secret/authentication requirements | Foundry Entra-only access is selected for the managed foundation; exact inference grants, PostgreSQL/internal-call authentication material and suppliers are unspecified (PD-027/035). Human/API authorization and workflow access remain pending PD-007/034/036; the read-only GitHub App does not authorize workflow API access. | Unknown except explicitly documented mechanisms; PD-027/035. Secret storage is intentionally undecided. |
| Delivery of values | Proposed: backend maintainers define/validate the required configuration interface; the authorized environment/integration owners supply approved values through the agreed IaC handoff. | Proposal, PD-035; specific people/teams, rotation and distribution mechanism remain unknown. |

No application environment-variable names, credentials, secret store or runtime defaults are approved by this document. The system-assigned ACA identity lifecycle is selected in IaC; its creation, access grants and user/workflow authentication remain separate requirements.

## Access and integration

| Downstream | Intended operation and boundary | Canonical source |
|---|---|---|
| `postgresql_operational_store` | Data plane: read/write owned tables only; no database control-plane authority. | `rel_recommendation_service_postgresql`; PostgreSQL |
| `rag_retrieval` | Data plane: request/read authorized contextual knowledge. | `rel_recommendation_rag`; REST/HTTPS |
| `pretrained_llm_diagnostic_service` | Data plane: request inference using sanitized evidence and RAG context; no model hosting, training or provider administration; Foundry foundation does not establish application API/SDK compatibility or model grants (ADR-019). | `rel_recommendation_llm`; Azure OpenAI API/HTTPS |

Documented fact: incoming consumers/callers are listed in [service relationships](architecture.md#service-relationships). Protocols and directional operations above describe data access, not granted permissions. Control-plane access for Azure/GitHub provisioning or administration is not part of the service runtime contract; no grants are authorized. Exact credentials, audiences, scopes, network rules and permission sets remain unknown (PD-027/034/035 and PD-036 if used). Embedding-provider descriptions do not authorize undeclared direct calls.

## Data lifecycle

Documented ownership from ADR-018 and `model/domain.yml.entities`:

`diagnosis.diagnoses` (confirmed), `diagnosis.recommendations` (confirmed), `diagnosis.diagnosis_evidence_links` (confirmed), `diagnosis.diagnosis_source_links` (confirmed), `diagnosis.human_reviews` (confirmed)

One physical PostgreSQL instance is shared; queries and commands use logical CQRS. Writes are confined to owned tables; shared schemas do not grant cross-owner writes. Physical FKs may exist within a schema; cross-schema references are logical IDs, with no ORM navigation across contexts (ADR-018, DD-014, PD-031). `audit.audit_events` has shared cross-cutting ownership and is not assigned exclusively to this repository; exact shared audit-write mechanics remain pending PD-035. Inferred `pipeline_access_grants`, where listed, preserve `ia_only`/inferred status and pending PD-007/008. No persistent schema is added for technical processing services without documented ownership.

**Proposal (PD-035):** the owner of each table authors and validates its migrations; IaC consumes approved compatibility and execution requirements without owning functional schema changes. Migration author/runner approval, sequencing, execution mechanism, rollback/recovery and deployment ordering are **unknown**; no migration tool or job is selected. Services without owned tables have no owned-schema migration payload; do not infer new tables to fill the gap.

**Documented facts:** logical references must be validated through existing contracts (PD-031). Logs, diagnoses and evidence have the documented 365-day retention policy (ADR-013/016/018, INV-009, domain RET-001); do not extend that duration to corpus, configuration or audit. Policy evidence and purge mechanisms remain PD-017/030.

**Unknown (PD-033/034/035):** accepted message/API/schema compatibility rules, supported producer/consumer version skew, migration rollout sequencing and validation evidence. No proposed DTO, class design or embedding dimension is treated as an approved contract. For knowledge storage, vector dimension and corpus updates remain PD-005/006; no migration can assume either.

## Operations

Documented facts: Azure Monitor and Application Insights for application telemetry (ADR-002); correlationId, traceability and cross-cutting functional audit are model requirements (`audit_trail`, CTRL-003/004). Functional audit in PostgreSQL is distinct from technical telemetry. Evidence protection before NLP/RAG/LLM, read-only GitHub access and human control remain applicable invariants.

Unknown under PD-035: application instrumentation/token handling, log format/export, sampling, alerts, dashboards, operational ownership, SLOs, replicas, autoscaling triggers, concurrency, application CPU/memory/storage, actual region/names and availability budgets. The integrated foundation choices above do not settle those per-service contracts. Model evaluation targets are not operational SLOs. PD-029 also preserves unresolved operational degradation/version-update mechanisms for AI-related capabilities. No per-service resources or executable deployment configuration are inferred.

## Approval and unresolved decisions

Readiness blockers for the affected increment: [PD-035](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-032](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-027](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-004](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-007](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-009](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-010](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-011](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-017](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-026](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-028](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-030](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-031](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-034](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-036](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md). PD-033/034/036 apply only to the exchanges this service uses. These link to existing pending IDs; this record neither creates nor closes decisions.

Before IaC consumes this contract, provide:

1. A versioned contract revision with actual image source commit/digest, runtime versions, required execution/configuration/access inputs and explicit backend/IaC responsibilities for the deployment increment.
2. Traceable decisions/evidence for each required unknown or accepted proposal, including relevant event/REST/workflow contracts, credentials/secret handling and data/migration compatibility. Resolve or explicitly accept the source-provenance limitations affecting that increment; absent fields are not defaults.
3. Identifiable approval by the authority recorded in the committed pending-decision register: CODEOWNERS **Jhon Galvez and Ramiro Guzman** for backend–IaC coordination and applicable contracts. The rule for one versus both approvals and GitHub handles/enforcement is unknown; do not invent CODEOWNERS configuration. Reference the committed source revision and provide identifiable approval evidence for this delivery contract; source availability alone does not approve it.
4. The applicable independent environment promotion approvals and separate authorization for any infrastructure action. Contract approval alone does not authorize deployment.

**Approval evidence today: none for this service's delivery contract.** ADRs retain their recorded Accepted status; missing individual acceptance records remain PD-014. This document is sufficient to expose gaps while repository initialization proceeds; it is insufficient to claim deployability.
