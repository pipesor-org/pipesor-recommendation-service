package com.pipesor.recommendation.diagnosis.application.queries;

import com.pipesor.recommendation.diagnosis.application.model.AdmittedDiagnosis;

import java.util.Comparator;

/** Maps an admitted diagnosis to the contract projection, keeping inferred and observed parts apart. */
public final class Projections {
    private Projections() { }

    public static DiagnosisProjection of(AdmittedDiagnosis diagnosis) {
        var output = diagnosis.output();
        var hypothesis = output.hypothesis();
        var reviews = diagnosis.reviews().stream()
                .sorted(Comparator.comparingInt(AdmittedDiagnosis.ReviewEntry::revision))
                .map(review -> new DiagnosisProjection.Review(review.revision(), review.decision(),
                        review.timestamp().toString())).toList();
        var model = hypothesis.model() == null ? null
                : new DiagnosisProjection.Model(hypothesis.model().deployment(), hypothesis.model().modelVersion());
        return new DiagnosisProjection(diagnosis.diagnosisId(), diagnosis.version(), output.incidentId(),
                output.executionId(), output.contextId(), output.correlationId(), diagnosis.createdAt().toString(),
                reviews.isEmpty() ? "PENDING" : reviews.getLast().decision(),
                new DiagnosisProjection.Hypothesis(hypothesis.probableCause(), hypothesis.recommendation(),
                        hypothesis.limitations(), hypothesis.evidenceIds(), hypothesis.chunkIds()),
                output.evidence().stream().map(evidence -> new DiagnosisProjection.Evidence(evidence.evidenceId(),
                        evidence.stage(), evidence.sourceLocator(), evidence.content())).toList(),
                output.sources().stream().map(source -> new DiagnosisProjection.Source(source.documentId(),
                        source.chunkId(), source.provenance())).toList(),
                model, reviews);
    }
}
