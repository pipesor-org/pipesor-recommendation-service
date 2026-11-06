# Deployment contract: recommendation_service

**Status: readiness record; not approved for IaC consumption or deployment.** Prepared for PD-035, as explicitly requested in the repository initialization task. [Architecture consumption](architecture.md) records the full source SHA `c0871ac9a62bad62b8c679255fe0a4b9c6574d2e`, model IDs, applicable ADRs and separately hashed uncommitted PD-033–036 overlay. That overlay is not attributed to this SHA.

**Documented fact** means recorded in those sources/policies, not proven implementation or independent approval evidence. **Proposal** requires acceptance; **Unknown** remains pending. No images, endpoints, credentials, migrations or infrastructure exist in this increment.

## Identity and runtime

| Requirement | Value | Source and status |
|---|---|---|
| Identity and responsibility | `recommendation_service` — Orchestrate assisted diagnosis with correlated context, authorized RAG knowledge and external LLM inference; compose probable-cause output and own human review. | Documented fact: `elements[recommendation_service]`, ADR-018. |
| Independent deployment | Yes; separate backend repository/container on Microsoft Azure Container Apps. | Documented fact: `deployment.container_hosts.recommendation_service`, ADR-001/015/018 as applicable. No resource has been created. |
| Language/framework | Java / Spring Boot. | Documented fact: element `backend`, ADR-018. Runtime/framework/base-image versions unknown: PD-032/035. |

## Artifact and responsibility boundary

| Requirement | Value | Source and status |
|---|---|---|
| Delivery unit | Container image for this service. | Documented fact: `deployment.containerization`, ADR-001/018. |
| Immutable identity | Associate the image with its source commit and digest; promote the same digest through the staged delivery flow. | Documented organization target: CONTRIBUTING artifact promotion. No digest, version, image or automation exists. |
| Release/version | Policy targets Conventional-Commit-derived SemVer and protected `vX.Y.Z` tags after staging, without rebuilding. | Documented organization target, not configured release tooling. Registry, image path/tag convention and build mechanism remain unknown under PD-035. |
| Ownership handoff | Proposed: this service's maintainers supply the tested image/digest and runtime/configuration requirements; IaC maintainers consume the approved delivery inputs for the agreed environment. | Proposal under PD-035; no named build/publish owner or accepted handoff contract is documented. |
| Promotion responsibility | Independent QA approval before staging and independent product-owner approval before production are policy targets. | Documented organization target. Concrete automation, environment mapping and operational owners remain unknown under PD-035; do not infer them from the sibling IaC implementation. |

## Execution

Documented fact: Documented synchronous REST/HTTPS context from correlation and queries/review operations through APIM (rel_apim_recommendation_service). GitHub Actions queries through APIM, then publishes its own artifact; workflow authorization is unresolved PD-036. Sources: service relationships listed in [architecture consumption](architecture.md), ADR-015/016 where applicable.

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
| Nonsecret integration settings | PostgreSQL diagnosis connection target, rag_retrieval address, Azure OpenAI endpoint/deployment and diagnostic configuration. GPT-4 family is documented; exact LLM variant is intentionally unknown (PD-026). Confidence/risk/API values remain pending PD-004/009/010/011/034. Actual environment values and their suppliers are not assigned. | Targets/capabilities are documented in the model; exact settings and supplier allocation are unknown. |
| Secret/authentication requirements | Azure OpenAI, PostgreSQL and internal-call authentication material and suppliers are unspecified (PD-027/035). Human/API authorization and workflow access remain pending PD-007/034/036; the read-only GitHub App does not authorize workflow API access. | Unknown except explicitly documented mechanisms; PD-027/035. Secret storage is intentionally undecided. |
| Delivery of values | Proposed: backend maintainers define/validate the required configuration interface; the authorized environment/integration owners supply approved values through the agreed IaC handoff. | Proposal, PD-035; specific people/teams, rotation and distribution mechanism remain unknown. |

No environment-variable names, example credentials, secret store, identity type or default values are approved by this document.

## Access and integration

| Downstream | Intended operation and boundary | Canonical source |
|---|---|---|
| `postgresql_operational_store` | Data plane: read/write owned tables only; no database control-plane authority. | `rel_recommendation_service_postgresql`; PostgreSQL |
| `rag_retrieval` | Data plane: request/read authorized contextual knowledge. | `rel_recommendation_rag`; REST/HTTPS |
| `pretrained_llm_diagnostic_service` | Data plane: request inference using sanitized evidence and RAG context; no model hosting, training or provider administration. | `rel_recommendation_llm`; Azure OpenAI API/HTTPS |

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

Unknown under PD-035: telemetry configuration/supplier, log format/export, sampling, alerts, dashboards, operational ownership, SLOs, replicas, autoscaling triggers, concurrency, CPU/memory, storage sizing, network/SKU/region and availability budgets. Model evaluation targets are not operational SLOs. PD-029 also preserves unresolved operational degradation/version-update mechanisms for AI-related capabilities. No resources or deployment configuration are inferred.

## Approval and unresolved decisions

Readiness blockers for the affected increment: [PD-035](architecture.md#uncommitted-source-overlay), [PD-032](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-027](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-004](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-007](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-009](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-010](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-011](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-017](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-026](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-028](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-030](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-031](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-034](architecture.md#uncommitted-source-overlay), [PD-036](architecture.md#uncommitted-source-overlay). PD-033/034/036 apply only to the exchanges this service uses. These link to existing pending IDs; this record neither creates nor closes decisions.

Before IaC consumes this contract, provide:

1. A versioned contract revision with actual image source commit/digest, runtime versions, required execution/configuration/access inputs and explicit backend/IaC responsibilities for the deployment increment.
2. Traceable decisions/evidence for each required unknown or accepted proposal, including relevant event/REST/workflow contracts, credentials/secret handling and data/migration compatibility. Resolve or explicitly accept the source-provenance limitations affecting that increment; absent fields are not defaults.
3. Identifiable approval by the authority recorded in the uncommitted stage-5 source overlay: CODEOWNERS **Jhon Galvez and Ramiro Guzman** for backend–IaC coordination and applicable contracts. The rule for one versus both approvals and GitHub handles/enforcement is unknown; do not invent CODEOWNERS configuration. Commit/link that source overlay or an approved successor revision so remote consumers can retrieve the authority and PD-035 evidence.
4. The applicable independent environment promotion approvals and separate authorization for any infrastructure action. Contract approval alone does not authorize deployment.

**Approval evidence today: none for this service's delivery contract.** ADRs retain their recorded Accepted status; missing individual acceptance records remain PD-014. This document is sufficient to expose gaps while repository initialization proceeds; it is insufficient to claim deployability.
