package com.pipesor.recommendation.diagnosis.interfaces.rest;

import com.pipesor.recommendation.diagnosis.application.queries.DiagnosisProjection;
import com.pipesor.recommendation.diagnosis.application.queryhandlers.IncidentDiagnosisQueryHandler;
import com.pipesor.recommendation.shared.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/** {@code GET /api/v1/incidents/{incidentId}/diagnosis}; session scope {@code Diagnosis.Read}. */
@RestController
public class IncidentDiagnosisController {
    public static final String PATH = "/api/v1/incidents/{incidentId}/diagnosis";
    private final IncidentDiagnosisQueryHandler handler;

    public IncidentDiagnosisController(IncidentDiagnosisQueryHandler handler) {
        this.handler = Objects.requireNonNull(handler);
    }

    @GetMapping(PATH)
    public ResponseEntity<DiagnosisProjection> diagnosis(@PathVariable String incidentId,
            @AuthenticationPrincipal Jwt session, HttpServletRequest request) {
        var projection = handler.handle(Ids.uuid(incidentId), session.getTokenValue(), CorrelationIdFilter.current(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(projection);
    }
}
