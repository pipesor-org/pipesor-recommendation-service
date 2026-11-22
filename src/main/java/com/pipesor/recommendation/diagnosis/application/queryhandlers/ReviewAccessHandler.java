package com.pipesor.recommendation.diagnosis.application.queryhandlers;

import com.pipesor.recommendation.diagnosis.application.model.Entitlement;
import com.pipesor.recommendation.diagnosis.application.ports.DiagnosisEntitlements;
import com.pipesor.recommendation.diagnosis.application.queries.DiagnosisAccessException;
import com.pipesor.recommendation.diagnosis.application.queries.DiagnosisAccessException.Outcome;
import com.pipesor.recommendation.diagnosis.review.ReviewStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/**
 * Review read and write: the diagnosis's incident entitlement is resolved with Pipeline on every
 * request and must cover the diagnosis scope; writes additionally need OPERATE. The reviewer is
 * always the session subject.
 */
public final class ReviewAccessHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ReviewAccessHandler.class);
    private final ReviewStore store;
    private final DiagnosisEntitlements entitlements;

    public ReviewAccessHandler(ReviewStore store, DiagnosisEntitlements entitlements) {
        this.store = Objects.requireNonNull(store);
        this.entitlements = Objects.requireNonNull(entitlements);
    }

    public ReviewStore.Snapshot read(UUID diagnosisId, String subjectToken, String correlationId) {
        return authorize(diagnosisId, subjectToken, correlationId, false);
    }

    /** {@code diagnosisVersion} may be omitted; it then means the version being reviewed. */
    public ReviewStore.Snapshot append(UUID diagnosisId, String subjectToken, String correlationId, UUID requestId,
            String reviewer, Integer diagnosisVersion, int expectedRevision, ReviewStore.Decision decision,
            String comment, String actualCause, String correctedRecommendation) {
        var snapshot = authorize(diagnosisId, subjectToken, correlationId, true);
        var submission = new ReviewStore.Submission(diagnosisVersion == null ? snapshot.version() : diagnosisVersion,
                expectedRevision, decision, comment, actualCause, correctedRecommendation);
        try {
            return store.append(snapshot, requestId, reviewer, submission);
        } catch (SQLException failure) {
            LOG.warn("review_write_failed exception={} correlationId={}", failure.getClass().getName(), correlationId);
            throw new DiagnosisAccessException(Outcome.DIAGNOSIS_UNAVAILABLE);
        }
    }

    private ReviewStore.Snapshot authorize(UUID diagnosisId, String subjectToken, String correlationId, boolean write) {
        ReviewStore.Snapshot snapshot;
        try {
            snapshot = store.read(diagnosisId);
        } catch (SQLException | RuntimeException failure) {
            LOG.warn("review_read_failed exception={} correlationId={}", failure.getClass().getName(), correlationId);
            throw new DiagnosisAccessException(Outcome.DIAGNOSIS_UNAVAILABLE);
        }
        if (snapshot == null) throw new DiagnosisAccessException(Outcome.DIAGNOSIS_NOT_FOUND);
        Entitlement entitlement;
        try {
            entitlement = entitlements.resolve(snapshot.original().incidentId(), subjectToken, correlationId);
        } catch (DiagnosisEntitlements.Denied denied) {
            throw new DiagnosisAccessException(Outcome.DIAGNOSIS_NOT_FOUND);
        } catch (DiagnosisEntitlements.Unavailable unavailable) {
            throw new DiagnosisAccessException(Outcome.AUTHORITY_UNAVAILABLE);
        }
        if (!snapshot.original().incidentId().equals(entitlement.incidentId())
                || !Bindings.covers(entitlement, snapshot.scope())) {
            throw new DiagnosisAccessException(Outcome.DIAGNOSIS_NOT_FOUND);
        }
        if (write && !entitlement.canOperate()) throw new DiagnosisAccessException(Outcome.FORBIDDEN);
        return snapshot;
    }
}
