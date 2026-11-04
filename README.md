# pipesor-recommendation-service

Orchestrate assisted diagnosis with correlated context, authorized RAG knowledge and external LLM inference; compose probable-cause output and own human review.

Canonical architecture ID: `recommendation_service`. Independent backend container on Azure Container Apps; approved runtime: **Java / Spring Boot**, versions pending PD-032.

This repository contains the initial development guidance only. Application code, endpoints, build tooling, images and infrastructure have not been implemented.

## Start here

- [Agent instructions](AGENTS.md): responsibilities, workflow and checks.
- [Architecture consumption](docs/architecture.md): pinned revision, service boundaries, relationships and source limitations.
- [Deployment contract](docs/deployment-contract.md): PD-035 readiness and unresolved delivery requirements.
- [Optional Codex configuration](.codex/config.toml) and service-specific `diagnostic_reviewer` in [.codex/agents/diagnostic-reviewer.toml](.codex/agents/diagnostic-reviewer.toml).

In the Pipesor workspace, this repository lives under `services/`; the architecture checkout is at `../../pipesor-docs/` relative to this repository root. Use that accessible checkout or the pinned GitHub links in the architecture guide. No submodule, copied model or architecture-generation dependency is required. Before implementation, read the source instructions and applicable models/ADRs at the recorded revision and account for the separately identified local overlay.

## Contributing and verification

Follow the [organization contribution policy](https://github.com/pipesor-org/.github/blob/main/CONTRIBUTING.md), [code of conduct](https://github.com/pipesor-org/.github/blob/main/CODE_OF_CONDUCT.md) and [security policy](https://github.com/pipesor-org/.github/blob/main/SECURITY.md). Use short-lived `feature/<topic>` or `fix/<topic>` branches from `main`, English Angular-style Conventional Commits and reviewed squash PRs. Do not push directly to `main`. GitHub's initial README establishes the empty repository baseline; the development structure is submitted by PR. No release or deployment automation is configured. Licensing remains pending.

For this documentation/configuration foundation, review source coherence and links, parse the TOML files and run `git diff --check`. No application build/test command exists yet. Add only the tooling and meaningful behavior checks required by the first approved implementation.

Codex discovers the repository-local specialist in trusted projects; it inherits the caller's model, permissions and tools. The main session handles implementation and may request the focused reviewer when relevant. No global setup or extra MCP server is needed. Configuration follows [official OpenAI subagent documentation](https://learn.chatgpt.com/docs/agent-configuration/subagents). Restart sessions after configuration changes; parse/startup checks do not prove live agent execution.
