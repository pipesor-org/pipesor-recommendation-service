package com.pipesor.recommendation.diagnosis.application;

import com.pipesor.recommendation.retrieval.application.ports.RagKnowledgeRetriever;
import com.pipesor.recommendation.retrieval.domain.KnowledgeMatch;
import com.pipesor.recommendation.retrieval.domain.RetrievalQuery;
import com.pipesor.recommendation.retrieval.domain.RetrievalResult;

import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.HashSet;
import java.util.Objects;
import java.util.UUID;

/** Internal orchestration for a caller that has already authorized and sanitized the context. */
public final class DiagnosticOrchestrator {
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(DiagnosticOrchestrator.class);
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
        if (hypothesis == null) throw reject("missing_hypothesis");
        if (empty(hypothesis.probableCause())) throw reject("blank_probable_cause");
        if (empty(hypothesis.recommendation())) throw reject("blank_recommendation");
        if (empty(hypothesis.limitations())) throw reject("blank_limitations");
        if (hypothesis.evidenceIds().isEmpty()) throw reject("missing_evidence_citation");
        validateCitations(hypothesis.evidenceIds(), signals.stream().map(Evidence::evidenceId).toList(), "evidence");
        validateCitations(hypothesis.chunkIds(), matches.stream().map(KnowledgeMatch::chunkId).toList(), "chunk");
        return new Diagnosis(contextId, executionId, incidentId, query.correlationId(), hypothesis,
                signals.stream().filter(signal -> hypothesis.evidenceIds().contains(signal.evidenceId())).toList(),
                matches.stream().filter(match -> hypothesis.chunkIds().contains(match.chunkId()))
                        .map(match -> new Source(match.documentId(), match.chunkId(), match.provenance())).toList());
    }

    private static void validateCitations(List<UUID> cited, List<UUID> available, String kind) {
        var remaining = new HashSet<>(cited);
        if (remaining.size() != cited.size()) throw reject("duplicate_" + kind + "_citation");
        for (UUID id : available) {
            if (cited.contains(id) && !remaining.remove(id)) throw reject("ambiguous_" + kind + "_reference");
        }
        if (!remaining.isEmpty()) throw reject("unknown_" + kind + "_citation");
    }

    private static InvalidHypothesis reject(String reason) {
        LOG.warn("diagnostic_validation_rejected reason={}", reason);
        return new InvalidHypothesis(reason);
    }

    public static final class InvalidHypothesis extends IllegalStateException {
        private final String reason;

        private InvalidHypothesis(String reason) {
            super("Invalid diagnostic hypothesis or citations");
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
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
