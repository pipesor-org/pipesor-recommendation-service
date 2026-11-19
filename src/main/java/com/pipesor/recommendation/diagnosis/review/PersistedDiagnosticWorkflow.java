package com.pipesor.recommendation.diagnosis.review;

import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator;
import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator.Evidence;
import com.pipesor.recommendation.retrieval.domain.RetrievalQuery;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Invoked after the existing caller has authorized and reconstructed the correlated context. */
public final class PersistedDiagnosticWorkflow {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final DiagnosticOrchestrator orchestrator;
    private final ReviewStore store;

    public PersistedDiagnosticWorkflow(DiagnosticOrchestrator orchestrator, ReviewStore store) {
        this.orchestrator = Objects.requireNonNull(orchestrator);
        this.store = Objects.requireNonNull(store);
    }

    public ReviewStore.Admission diagnose(UUID context, UUID execution, UUID incident,
                                          RetrievalQuery query, List<Evidence> evidence) throws SQLException {
        Objects.requireNonNull(context);
        Objects.requireNonNull(execution);
        Objects.requireNonNull(incident);
        var signals = List.copyOf(evidence);
        UUID id = UUID.nameUUIDFromBytes(("pipesor/diagnosis/v1:" + context).getBytes(StandardCharsets.US_ASCII));
        String fingerprint = fingerprint(execution, incident, query, signals);
        var previous = store.admitted(id, fingerprint);
        if (previous != null) return previous;
        var output = orchestrator.diagnose(context, execution, incident, query, signals);
        return store.admit(id, fingerprint, query.authorizationScope(), output);
    }

    private static String fingerprint(UUID execution, UUID incident, RetrievalQuery query, List<Evidence> evidence) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsBytes(new Input(execution, incident, query, evidence))));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record Input(UUID execution, UUID incident, RetrievalQuery query, List<Evidence> evidence) {}
}
