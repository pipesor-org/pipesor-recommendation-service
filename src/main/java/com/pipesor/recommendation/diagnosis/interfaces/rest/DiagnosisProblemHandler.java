package com.pipesor.recommendation.diagnosis.interfaces.rest;

import com.pipesor.recommendation.diagnosis.application.queries.DiagnosisAccessException;
import com.pipesor.recommendation.diagnosis.review.ReviewStore;
import com.pipesor.recommendation.shared.web.Problems;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Public codes of the diagnosis, review and report routes (MVP HTTP API v1). */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DiagnosisProblemHandler {
    @ExceptionHandler(DiagnosisAccessException.class)
    ResponseEntity<ProblemDetail> access(DiagnosisAccessException failure, HttpServletRequest request) {
        return switch (failure.outcome()) {
            case INCIDENT_NOT_FOUND -> Problems.entity(404, "incident_not_found", request);
            case DIAGNOSIS_NOT_FOUND -> Problems.entity(404, "not_found", request);
            case FORBIDDEN -> Problems.entity(403, "forbidden", request);
            case REPOSITORY_MISMATCH -> Problems.entity(403, "repository_mismatch", request);
            case DIAGNOSIS_ABSENT -> Problems.entity(404, "diagnosis_absent", request);
            case DIAGNOSIS_STALE -> Problems.entity(409, "diagnosis_stale", request);
            case AUTHORITY_UNAVAILABLE -> Problems.entity(503, "authority_unavailable", request);
            case DIAGNOSIS_UNAVAILABLE -> Problems.entity(503, "diagnosis_unavailable", request);
        };
    }

    @ExceptionHandler(ReviewStore.Conflict.class)
    ResponseEntity<ProblemDetail> reviewConflict(HttpServletRequest request) {
        return Problems.entity(409, "review_conflict", request);
    }
}
