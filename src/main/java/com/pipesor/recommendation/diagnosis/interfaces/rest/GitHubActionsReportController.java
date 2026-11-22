package com.pipesor.recommendation.diagnosis.interfaces.rest;

import com.pipesor.recommendation.diagnosis.application.queryhandlers.GitHubActionsReportQueryHandler;
import com.pipesor.recommendation.shared.web.ApiProblem;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Diagnosis report for a failed GitHub Actions run attempt, authenticated with the workflow's
 * GitHub Actions OIDC token (identity contract v1, §6).
 */
@RestController
public class GitHubActionsReportController {
    public static final String PATH =
            "/api/v1/github-actions/repositories/{repositoryId}/runs/{runId}/attempts/{attempt}/report";
    private final GitHubActionsReportQueryHandler handler;

    public GitHubActionsReportController(GitHubActionsReportQueryHandler handler) {
        this.handler = Objects.requireNonNull(handler);
    }

    @GetMapping(PATH)
    public ResponseEntity<GitHubActionsReportQueryHandler.Report> report(@PathVariable String repositoryId,
            @PathVariable String runId, @PathVariable String attempt, @AuthenticationPrincipal Jwt token) {
        try {
            var report = handler.handle(token.getClaimAsString("repository_id"), repositoryId, runId, attempt);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(report);
        } catch (IllegalArgumentException invalid) {
            throw new ApiProblem(400, "invalid_request");
        }
    }
}
