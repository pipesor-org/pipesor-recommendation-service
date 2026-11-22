package com.pipesor.recommendation.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Narrow mapping of failures to problem responses; unexpected failures are logged by class only. */
@RestControllerAdvice
public class ProblemExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ProblemExceptionHandler.class);

    @ExceptionHandler(ApiProblem.class)
    ResponseEntity<ProblemDetail> problem(ApiProblem problem, HttpServletRequest request) {
        return Problems.entity(problem.status(), problem.code(), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> invalidPath(HttpServletRequest request) {
        return Problems.entity(400, "invalid_request", request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ProblemDetail> missingHeader(HttpServletRequest request) {
        return Problems.entity(400, "invalid_request", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> method(HttpServletRequest request) {
        return Problems.entity(405, "method_not_allowed", request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> mediaType(HttpServletRequest request) {
        return Problems.entity(415, "unsupported_media_type", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> notFound(HttpServletRequest request) {
        return Problems.entity(404, "not_found", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception failure, HttpServletRequest request) {
        LOG.error("request_failed exception={} correlationId={}", failure.getClass().getName(),
                CorrelationIdFilter.current(request));
        return Problems.entity(503, "temporarily_unavailable", request);
    }
}
