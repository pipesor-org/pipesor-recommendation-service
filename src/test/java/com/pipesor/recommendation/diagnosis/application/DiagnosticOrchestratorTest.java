package com.pipesor.recommendation.diagnosis.application;

import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator.Evidence;
import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator.Hypothesis;
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
    }
}
