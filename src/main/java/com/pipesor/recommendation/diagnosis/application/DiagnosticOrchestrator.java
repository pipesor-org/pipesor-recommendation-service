package com.pipesor.recommendation.diagnosis.application;

import com.pipesor.recommendation.retrieval.application.ports.RagKnowledgeRetriever;
import com.pipesor.recommendation.retrieval.domain.KnowledgeMatch;
import com.pipesor.recommendation.retrieval.domain.RetrievalQuery;
import com.pipesor.recommendation.retrieval.domain.RetrievalResult;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Internal orchestration for a caller that has already authorized and sanitized the context. */
public final class DiagnosticOrchestrator {
    private final RagKnowledgeRetriever retrieval;
    private final Inference inference;

    public DiagnosticOrchestrator(RagKnowledgeRetriever retrieval, Inference inference) {
        this.retrieval = Objects.requireNonNull(retrieval);
        this.inference = Objects.requireNonNull(inference);
    }

    public Diagnosis diagnose(UUID contextId, UUID executionId, UUID incidentId, RetrievalQuery query,
            List<Evidence> evidence) {
        Objects.requireNonNull(contextId);
        Objects.requireNonNull(executionId);
        Objects.requireNonNull(incidentId);
        var signals = List.copyOf(evidence);
        if (signals.isEmpty()) throw new IllegalArgumentException("Diagnostic evidence is required");

        var result = retrieval.retrieve(query);
        if (!query.correlationId().equals(result.correlationId())) {
            throw new IllegalStateException("Retrieval correlation mismatch");
        }
        List<KnowledgeMatch> matches = result instanceof RetrievalResult.Matches found ? found.matches() : List.of();
        var hypothesis = inference.infer(signals, matches);
        if (hypothesis == null || empty(hypothesis.probableCause()) || empty(hypothesis.recommendation())
                || empty(hypothesis.limitations()) || hypothesis.evidenceIds().isEmpty()
                || !signals.stream().map(Evidence::evidenceId).toList().containsAll(hypothesis.evidenceIds())
                || !matches.stream().map(KnowledgeMatch::chunkId).toList().containsAll(hypothesis.chunkIds())) {
            throw new IllegalStateException("Invalid diagnostic hypothesis or citations");
        }
        return new Diagnosis(contextId, executionId, incidentId, query.correlationId(), hypothesis,
                signals.stream().filter(signal -> hypothesis.evidenceIds().contains(signal.evidenceId())).toList(),
                matches.stream().filter(match -> hypothesis.chunkIds().contains(match.chunkId()))
                        .map(match -> new Source(match.documentId(), match.chunkId(), match.provenance())).toList());
    }

    private static boolean empty(String value) {
        return value == null || value.isBlank();
    }

    @FunctionalInterface
    public interface Inference {
        Hypothesis infer(List<Evidence> evidence, List<KnowledgeMatch> knowledge);
    }

    public record Evidence(UUID evidenceId, UUID originContextId, String stage, String content) {}
    public record Hypothesis(String probableCause, String recommendation, String limitations,
                             List<UUID> evidenceIds, List<UUID> chunkIds) {
        public Hypothesis {
            evidenceIds = List.copyOf(evidenceIds);
            chunkIds = List.copyOf(chunkIds);
        }
    }
    public record Source(UUID documentId, UUID chunkId, String provenance) {}
    public record Diagnosis(UUID contextId, UUID executionId, UUID incidentId, String correlationId,
                            Hypothesis hypothesis, List<Evidence> evidence, List<Source> sources) {}
}
