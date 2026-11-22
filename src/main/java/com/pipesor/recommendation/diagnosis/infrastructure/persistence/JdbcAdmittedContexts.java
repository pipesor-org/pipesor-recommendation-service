package com.pipesor.recommendation.diagnosis.infrastructure.persistence;

import com.pipesor.recommendation.diagnosis.application.model.AdmittedContext;
import com.pipesor.recommendation.diagnosis.application.ports.AdmittedContexts;
import com.pipesor.recommendation.retrieval.domain.AuthorizationScope;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Reads the admitted receipt ({@code diagnosis.correlated_context_receipts}) for one context. */
public final class JdbcAdmittedContexts implements AdmittedContexts {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;

    public JdbcAdmittedContexts(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public Optional<AdmittedContext> find(UUID contextId) {
        return jdbc.query("""
                SELECT context_id, incident_id, execution_id, tenant_id, github_installation_id, github_repository_id,
                       pipeline_id, scope_revision, correlation_id, signals::text AS signals
                FROM diagnosis.correlated_context_receipts WHERE context_id = ?
                """, (row, index) -> new AdmittedContext(row.getObject("context_id", UUID.class),
                row.getObject("incident_id", UUID.class), row.getObject("execution_id", UUID.class),
                new AuthorizationScope(row.getObject("tenant_id", UUID.class), row.getString("github_installation_id"),
                        row.getString("github_repository_id"), row.getObject("pipeline_id", UUID.class),
                        row.getString("scope_revision")),
                row.getString("correlation_id"), signals(row.getString("signals"))), contextId).stream().findFirst();
    }

    private static java.util.List<AdmittedContext.Signal> signals(String json) {
        var signals = new ArrayList<AdmittedContext.Signal>();
        for (JsonNode signal : JSON.readTree(json)) {
            signals.add(new AdmittedContext.Signal(UUID.fromString(signal.get("evidence_id").asString()),
                    UUID.fromString(signal.get("origin_context_id").asString()), signal.get("correlation_id").asString(),
                    signal.get("stage").asString(), signal.get("content").asString(),
                    signal.has("source_locator") ? signal.get("source_locator").asString() : null));
        }
        return signals;
    }
}
