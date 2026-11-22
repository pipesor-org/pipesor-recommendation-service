package com.pipesor.recommendation.diagnosis.application;

import com.pipesor.recommendation.diagnosis.application.model.AdmittedContext;
import com.pipesor.recommendation.retrieval.domain.AuthorizationScope;
import com.pipesor.recommendation.retrieval.domain.CorpusKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextReconstructionTest {
    private static final AuthorizationScope SCOPE = new AuthorizationScope(UUID.randomUUID(), "1", "2", UUID.randomUUID(), "s1");

    @Test
    void singleSignalMatchesTheHarnessReconstruction() {
        var context = UUID.randomUUID();
        var signal = new AdmittedContext.Signal(UUID.randomUUID(), context, "corr", "BUILD", "Could not resolve host",
                "v2:job:1:member:a.txt:lines:1-1:sha256:" + "a".repeat(64));
        var input = ContextReconstruction.reconstruct(new AdmittedContext(context, UUID.randomUUID(), UUID.randomUUID(),
                SCOPE, "corr", List.of(signal)));
        assertThat(input.query().query()).isEqualTo("Could not resolve host");
        assertThat(input.query().topK()).isEqualTo(1);
        assertThat(input.query().allowedCorpusKinds()).containsExactly(CorpusKind.AUTHORIZED_TECHNICAL_DOCUMENTATION);
        assertThat(input.query().authorizationScope()).isEqualTo(SCOPE);
        assertThat(input.evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.originContextId()).isEqualTo(context);
            assertThat(evidence.sourceLocator()).isEqualTo(signal.sourceLocator());
        });
    }

    @Test
    void multiSignalKeepsEveryEvidenceInOrderAndBoundsTheQuery() {
        var context = UUID.randomUUID();
        var earlier = new AdmittedContext.Signal(UUID.randomUUID(), UUID.randomUUID(), "corr", "TESTING", "x".repeat(3000), null);
        var later = new AdmittedContext.Signal(UUID.randomUUID(), UUID.randomUUID(), "corr", "BUILD", "y".repeat(3000), null);
        var own = new AdmittedContext.Signal(UUID.randomUUID(), context, "corr", "BUILD", "z", null);
        var input = ContextReconstruction.reconstruct(new AdmittedContext(context, UUID.randomUUID(), UUID.randomUUID(),
                SCOPE, "corr", List.of(earlier, later, own)));
        assertThat(input.evidence()).extracting(DiagnosticOrchestrator.Evidence::evidenceId)
                .containsExactly(earlier.evidenceId(), later.evidenceId(), own.evidenceId());
        assertThat(input.query().query()).hasSize(4000).startsWith("x".repeat(3000) + "\n\n");
        assertThat(input.query().topK()).isEqualTo(3);
    }

    @Test
    void rejectsContextsThatDoNotEndWithTheirOwnSignal() {
        var signal = new AdmittedContext.Signal(UUID.randomUUID(), UUID.randomUUID(), "corr", "BUILD", "text", null);
        assertThatThrownBy(() -> ContextReconstruction.reconstruct(new AdmittedContext(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), SCOPE, "corr", List.of(signal)))).isInstanceOf(ContextReconstruction.InvalidContext.class);
    }
}
