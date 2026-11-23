package com.pipesor.recommendation.diagnosis.application;

import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator.Evidence;
import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator.Hypothesis;
import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator.InvalidHypothesis;
import com.pipesor.recommendation.retrieval.domain.AuthorizationScope;
import com.pipesor.recommendation.retrieval.domain.CorpusKind;
import com.pipesor.recommendation.retrieval.domain.KnowledgeMatch;
import com.pipesor.recommendation.retrieval.domain.RetrievalQuery;
import com.pipesor.recommendation.retrieval.domain.RetrievalResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiagnosticOrchestratorTest {
    @Test
    void refusesToInferWithoutEvidence() {
        var service = new DiagnosticOrchestrator(
                query -> { throw new AssertionError("Retrieved knowledge without diagnostic evidence"); },
                (signals, knowledge) -> { throw new AssertionError("Inferred without diagnostic evidence"); });
        var query = new RetrievalQuery("fixture", "Synthetic query",
                new AuthorizationScope(UUID.randomUUID(), "1", "2", null, "fixture"),
                List.of(CorpusKind.AUTHORIZED_TECHNICAL_DOCUMENTATION), 1);
        assertThatThrownBy(() -> service.diagnose(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), query, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Diagnostic evidence is required");
    }

    @Test
    void requiresEachCitationToResolveToExactlyOneLink() {
        var context = UUID.randomUUID();
        var evidence = new Evidence(UUID.randomUUID(), context, "checkout", "Synthetic failure");
        var kind = CorpusKind.AUTHORIZED_TECHNICAL_DOCUMENTATION;
        var match = new KnowledgeMatch(UUID.randomUUID(), UUID.randomUUID(), kind,
                "Synthetic runbook", "Check DNS", "fixture:checkout", 1.0);
        var query = new RetrievalQuery("fixture", evidence.content(),
                new AuthorizationScope(UUID.randomUUID(), "1", "2", null, "fixture"), List.of(kind), 2);
        var execution = UUID.randomUUID();
        var incident = UUID.randomUUID();
        var hypothesis = new Hypothesis("DNS failure", "Check DNS", "Cause unknown",
                List.of(evidence.evidenceId()), List.of(match.chunkId()));
        var service = new DiagnosticOrchestrator(
                request -> new RetrievalResult.Matches(request.correlationId(), List.of(match)),
                (signals, knowledge) -> hypothesis);
        assertThat(service.diagnose(context, execution, incident, query, List.of(evidence)).sources())
                .hasSize(1);

        assertThatThrownBy(() -> service.diagnose(context, execution, incident, query, List.of(evidence, evidence)))
                .hasMessage("Invalid diagnostic hypothesis or citations");

        var duplicateCitation = new DiagnosticOrchestrator(
                request -> new RetrievalResult.Matches(request.correlationId(), List.of(match)),
                (signals, knowledge) -> new Hypothesis("DNS failure", "Check DNS", "Cause unknown",
                        hypothesis.evidenceIds(), List.of(match.chunkId(), match.chunkId())));
        assertThatThrownBy(() -> duplicateCitation.diagnose(context, execution, incident, query, List.of(evidence)))
                .hasMessage("Invalid diagnostic hypothesis or citations");

        assertRejected(query, evidence, List.of(), List.of(), "missing_evidence_citation");
        assertRejected(query, evidence, List.of(UUID.randomUUID()), List.of(), "unknown_evidence_citation");
        assertRejected(query, evidence, List.of(evidence.evidenceId(), evidence.evidenceId()),
                List.of(), "duplicate_evidence_citation");
        assertRejected(query, evidence, List.of(evidence.evidenceId()), List.of(match.chunkId()),
                "unknown_chunk_citation");
    }

    private static void assertRejected(RetrievalQuery query, Evidence evidence, List<UUID> evidenceIds,
            List<UUID> chunkIds, String reason) {
        var service = new DiagnosticOrchestrator(request -> new RetrievalResult.NoMatch(request.correlationId()),
                (signals, knowledge) -> new Hypothesis("DNS failure", "Check DNS", "Cause unknown", evidenceIds, chunkIds));
        assertThatThrownBy(() -> service.diagnose(evidence.originContextId(), UUID.randomUUID(),
                        UUID.randomUUID(), query, List.of(evidence)))
                .isInstanceOfSatisfying(InvalidHypothesis.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(reason);
                    assertThat(failure).hasMessage("Invalid diagnostic hypothesis or citations");
                });
    }

    @Test
    void retrievalScoresAreLoggedAsIdentifiersAndScoresOnly() {
        var first = new KnowledgeMatch(UUID.fromString("00000000-0000-0000-0000-000000000001"), UUID.randomUUID(),
                CorpusKind.AUTHORIZED_TECHNICAL_DOCUMENTATION, "secret title", "secret content", "provenance", 0.5361234);
        var second = new KnowledgeMatch(UUID.fromString("00000000-0000-0000-0000-000000000002"), UUID.randomUUID(),
                CorpusKind.AUTHORIZED_TECHNICAL_DOCUMENTATION, "t", "c", "p", 0.4);
        String line = DiagnosticOrchestrator.scores(List.of(first, second));
        assertThat(line).isEqualTo("[00000000-0000-0000-0000-000000000001:0.5361,00000000-0000-0000-0000-000000000002:0.4000]");
        assertThat(line).doesNotContain("secret");
        assertThat(DiagnosticOrchestrator.scores(List.of())).isEqualTo("[]");
    }
}
