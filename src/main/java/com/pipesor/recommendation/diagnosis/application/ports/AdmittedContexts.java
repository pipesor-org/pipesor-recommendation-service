package com.pipesor.recommendation.diagnosis.application.ports;

import com.pipesor.recommendation.diagnosis.application.model.AdmittedContext;

import java.util.Optional;
import java.util.UUID;

/** Reads admitted correlated contexts from Recommendation's own receipts. */
public interface AdmittedContexts {
    Optional<AdmittedContext> find(UUID contextId);
}
