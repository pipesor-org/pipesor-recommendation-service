# Architecture consumption

Consulted on 2026-11-04: `pipesor-org/pipesor-docs` full Git SHA **`c0871ac9a62bad62b8c679255fe0a4b9c6574d2e`**, also the remote `main` observed during preparation. Canonical model: `ey_aiops_ci_cd_diagnosis`, version `0.4.0-c4-views`, status `draft`. `recommendation_service` is a confirmed container with an independent repository under ADR-018; confirmed is a model status only.

The links below point to current sources on `main`; the summary in this guide reflects the revision recorded above and must be reviewed when those sources change.

## Sources by subject

- [Source instructions](https://github.com/pipesor-org/pipesor-docs/blob/main/AGENTS.md), [architecture model](https://github.com/pipesor-org/pipesor-docs/blob/main/model/architecture.yml): `elements[recommendation_service]`, `relationships`, `deployment.container_hosts.recommendation_service`, `architectural_invariants` (INV-001/002/003/005/006/007/008/009/010), CTRL-001–006, `audit_trail`, `retention`.
- [Persistent model](https://github.com/pipesor-org/pipesor-docs/blob/main/model/domain.yml), [domain decisions](https://github.com/pipesor-org/pipesor-docs/blob/main/model/domain_decisions.md): table ownership, assumptions, retention and open questions. Domain owners are listed in the delivery contract; no derived SQL/DBML is consumed as independent authority.
- Applicable ADRs: [ADR-001](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/ADR-001-azure-container-apps.md), [ADR-002](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/ADR-002-azure-observability.md), [ADR-013](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/ADR-013-retention-365-days.md), [ADR-018](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/ADR-018-application-architecture-ddd-cqrs.md), [ADR-003](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/ADR-003-identity-api-perimeter.md), [ADR-004](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/ADR-004-azure-openai.md), [ADR-009](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/ADR-009-remove-reporting-service.md), [ADR-011](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/ADR-011-audit-trail-without-audit-service.md), [ADR-015](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/ADR-015-diagnostic-runtime-boundaries-and-orchestration.md), [ADR-016](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/ADR-016-identity-api-human-review-and-artifact-flow.md), [ADR-017](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/ADR-017-diagnostic-service-component-boundaries.md). For current decisions, read the [decision index](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/index.md) for subsequent supersession.
- [Proposed design](https://github.com/pipesor-org/pipesor-docs/blob/main/model/design.yml), [design rationale](https://github.com/pipesor-org/pipesor-docs/blob/main/model/design_decisions.md): retain proposed states and DCQ questions; not approved classes or API contracts.
- [Source provenance](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/sources/README.md), [reconciliation](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/reconciliation.md), [committed pending register](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md). Charter controls scope; architecture/ADRs control boundaries; drift/applicable technology ADRs control technology. Functional research is derived and its API v0.1 is proposed.

## Uncommitted source overlay

The local source checkout has relevant changes **outside the SHA above**:

| File in `../../pipesor-docs/` (from repository root) | Git state | SHA-256 of consulted working file |
|---|---|---|
| `docs/architecture/pending-decisions.md` | Modified tracked file; stage-5 extension adds PD-033–036 and contract approval authority | `3e153d9a467cef72caa937fc2b2e2e7642ebcbee1f98465edc8951df8c799c42` |
| `docs/architecture/stage-5-result.md` | Untracked descriptive stage-5 report with local reconciliation | `1a1228c6869cbe602ff78c0ba0c87466db4b6c7cc9d3500f6b6c3e9e7d86d6a0` |

These hashes identify local evidence, not Git revisions or approvals. PD-033–036 do **not** exist at the consulted commit. Read those IDs in the separately identified working file; remote consumers must obtain the matching source overlay or a subsequently reviewed committed revision before relying on its details. No source contents are copied here. The report cites historical `7c0bd1f9eaa47d6e48e5bffb8c1a5d5b49fb405e`; that is not this task's consulted checkout. The overlay names Jhon Galvez and Ramiro Guzman as contract approval authority; it does not prove configured CODEOWNERS or specify whether one or both approvals suffice. The current user task explicitly requests PD-035 readiness documentation; it does not approve a delivery contract.

## Repository boundary inventory

ADR-018 specifies multirepo per deployable microservice. Repository names below apply the existing `pipesor-` prefix and kebab-case to canonical IDs; IDs are unchanged.

| Canonical container | Independent backend repository |
|---|---|
| `ingestion_normalization_service` | `pipesor-ingestion-normalization-service` |
| `pipeline_service` | `pipesor-pipeline-service` |
| `log_event_service` | `pipesor-log-event-service` |
| `evidence_correlation_engine` | `pipesor-evidence-correlation-engine` |
| `recommendation_service` | `pipesor-recommendation-service` |
| `nlp_log_processor` | `pipesor-nlp-log-processor` |
| `rag_retrieval` | `pipesor-rag-retrieval` |

`web_dashboard` already has `pipesor-frontend`. Managed APIM, Service Bus, PostgreSQL/pgvector, Blob, Entra ID and Azure OpenAI are not custom backend service repositories. IaC already has `pipesor-iac`. `pretrained_llm_diagnostic_service` is an external managed service despite its ID (ADR-015). The diagnostic API, orchestrator, output composer and human review are internal to `recommendation_service` (ADR-016/017). Audit is cross-cutting (ADR-011); reporting was removed (ADR-009), detector/LogBERT are outside MVP (ADR-010), notifications are excluded (ADR-012), and direct scanner integrations are outside MVP (ADR-014). No repository is created for those capabilities.

No ambiguity blocks the seven service boundaries. Operational gaps remain: embedding access paths are not separately declared for NLP/RAG despite the documented provider, and the owners/mechanisms for configuration, migrations and promotion are incomplete. Those gaps do not authorize new relationships or services.

## Service relationships

Read these canonical IDs in `model/architecture.yml`; internal C4 projections do not add deployment boundaries:

- `rel_recommendation_service_postgresql`: `recommendation_service` → `postgresql_operational_store` (PostgreSQL).
- `rel_correlation_recommendation`: `evidence_correlation_engine` → `recommendation_service` (REST/HTTPS).
- `rel_recommendation_rag`: `recommendation_service` → `rag_retrieval` (REST/HTTPS).
- `rel_recommendation_llm`: `recommendation_service` → `pretrained_llm_diagnostic_service` (Azure OpenAI API/HTTPS).
- `rel_apim_recommendation_service`: `api_management` → `recommendation_service` (REST/HTTPS).
- `rel_recommendation_human_review`: `recommendation_service` → `human_review_component` (in-process).

## Organization conventions consulted

Shared policy checkout/remote `main`: `pipesor-org/.github@36691787bb9d0c9fd6b78c76bfe82adffa0c1c87`; [contribution policy](https://github.com/pipesor-org/.github/blob/main/CONTRIBUTING.md), [security policy](https://github.com/pipesor-org/.github/blob/main/SECURITY.md), [PR template](https://github.com/pipesor-org/.github/blob/main/.github/PULL_REQUEST_TEMPLATE.md). Reuse organization defaults by reference, without duplicated community files or an unapproved license. Existing repository naming, `main`, Trunk-Based Development and squash settings were inspected. Native agent layout follows `pipesor-iac@abd0551644744be3a0d416119d5f163708136ea5` and [official OpenAI documentation](https://learn.chatgpt.com/docs/agent-configuration/subagents), using a single focused reviewer and inherited model/tool settings. Existing Bun/Lefthook tooling belongs to those repositories; no application/toolchain dependency is introduced here.

Missing source approvals/provenance are tracked in PD-012–024, including individual ADR approval records (PD-014); preserve Accepted status while reporting those limits. Unresolved decisions relevant to this service: [PD-035](architecture.md#uncommitted-source-overlay), [PD-032](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-027](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-004](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-007](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-009](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-010](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-011](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-017](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-026](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-028](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-030](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-031](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-034](architecture.md#uncommitted-source-overlay), [PD-036](architecture.md#uncommitted-source-overlay). Scope them to the affected integration or deployment rather than blocking repository initialization.

## GitHub enforcement limitation

Repository settings use squash-only merges, PR-title/body squash messages and automatic deletion of merged branches. No branch ruleset or protection is claimed: the GitHub rulesets API returned HTTP 403 with a plan-upgrade requirement when inspected on the existing docs repository and the new ingestion repository. Mandatory reviewed PRs and no direct pushes to main remain contribution rules; server-side enforcement has not been established. Keep these repositories private; do not change visibility to work around that limitation. No empty CI checks or deployment workflows are added.
