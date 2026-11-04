# Diagnostic & Recommendation Service agents

Read [README.md](README.md), [architecture consumption](docs/architecture.md) and [deployment contract](docs/deployment-contract.md) before changes. Follow the linked organization contribution/security policies; keep work on short-lived branches with English Conventional Commits and reviewed squash PRs.

## Scope and authority

- Own `recommendation_service`: Orchestrate assisted diagnosis with correlated context, authorized RAG knowledge and external LLM inference; compose probable-cause output and own human review.
- Use the pinned `pipesor-docs` sources and its AGENTS.md, relevant model IDs/invariants and applicable Accepted ADRs. Record revision updates explicitly; inspect dirty source changes separately. Architecture remains draft; confirmed does not mean implemented, proposed classes/API examples do not approve contracts, and assumptions remain assumptions.
- Preserve ADR-018 boundaries: Java / Spring Boot, lightweight DDD where appropriate, logical CQRS, one physical PostgreSQL instance, exclusive owned-table writes, no cross-schema FK/ORM navigation and no Event Sourcing. Do not create artificial domain layers before code needs them.
- Never resolve pending decisions, choose unspecified versions/models or add relationships to fill a deployment gap. Report conflicts and seek an authorized decision in the architecture owner repository.
- This increment authorizes documentation and agent setup only. Future endpoints, business behavior, contracts, build tooling and infrastructure need their own agreed scope. Never change neighboring repositories, global configuration, credentials or infrastructure as a side effect.

## Work and review

The main session scopes and implements the smallest complete authorized change. Use `diagnostic_reviewer` for changes affecting its specialized risks; handle small documentation edits directly. Give it the objective, changed files and required checks; it reviews without editing or delegating. Shared instructions live here rather than in each agent. Agent review does not replace human approval.

Service-specific checks: Probable-cause wording, evidence/limitations, human approval and non-autonomous remediation; keep API, orchestration, output composition and review internal; preserve diagnosis ownership.

For documentation, verify source coherence, relative links and TOML syntax, then run `git diff --check`. For later executable changes, inspect callers/configuration/tests first and add a small meaningful test for nontrivial behavior; run the checks supplied by the approved implementation. Do not invent a build command now. Report checks actually run, omissions and unresolved decisions.

Keep secrets, tokens, personal data and machine state out of commits and tool output. Do not create credentials, grants or deploy infrastructure. Protect evidence before NLP/RAG/LLM; preserve correlationId/provenance, human review, non-autonomous remediation and evaluation/corpus separation as applicable. The deployment contract records readiness and grants no approvals.
