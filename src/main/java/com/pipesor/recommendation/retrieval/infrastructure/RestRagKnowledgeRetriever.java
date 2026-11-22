package com.pipesor.recommendation.retrieval.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pipesor.recommendation.retrieval.application.ports.RagAccessTokenProvider;
import com.pipesor.recommendation.retrieval.application.ports.RagContractException;
import com.pipesor.recommendation.retrieval.application.ports.RagKnowledgeRetriever;
import com.pipesor.recommendation.retrieval.application.ports.RagUnavailableException;
import com.pipesor.recommendation.retrieval.domain.AuthorizationScope;
import com.pipesor.recommendation.retrieval.domain.CorpusKind;
import com.pipesor.recommendation.retrieval.domain.KnowledgeMatch;
import com.pipesor.recommendation.retrieval.domain.RetrievalQuery;
import com.pipesor.recommendation.retrieval.domain.RetrievalResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.time.Duration;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

public final class RestRagKnowledgeRetriever implements RagKnowledgeRetriever {
    static final String QUERY_PATH = "/internal/v1/retrieval/query";
    public static final int MAX_RETRIES = 2;
    private final RestClient client;
    private final RagAccessTokenProvider tokenProvider;
    private final Duration totalBudget;
    private final LongSupplier nanoTime;
    private final RetryDelay retryDelay;
    private final Clock wallClock;

    /** {@code endpoint} must already satisfy the platform transport policy. */
    public RestRagKnowledgeRetriever(RestClient.Builder builder, URI endpoint, RagClientProperties properties,
                                     RagAccessTokenProvider tokenProvider) {
        this(builder, endpoint, properties, tokenProvider, System::nanoTime, Thread::sleep, Clock.systemUTC());
    }

    RestRagKnowledgeRetriever(RestClient.Builder builder, URI endpoint, RagClientProperties properties,
                              RagAccessTokenProvider tokenProvider, LongSupplier nanoTime,
                              RetryDelay retryDelay) {
        this(builder, endpoint, properties, tokenProvider, nanoTime, retryDelay, Clock.systemUTC());
    }

    RestRagKnowledgeRetriever(RestClient.Builder builder, URI endpoint, RagClientProperties properties,
                              RagAccessTokenProvider tokenProvider, LongSupplier nanoTime,
                              RetryDelay retryDelay, Clock wallClock) {
        this.client = builder.baseUrl(java.util.Objects.requireNonNull(endpoint).toString()).build();
        this.tokenProvider = tokenProvider;
        this.totalBudget = properties.timeout();
        this.nanoTime = nanoTime;
        this.retryDelay = retryDelay;
        this.wallClock = wallClock;
    }

    @Override
    public RetrievalResult retrieve(RetrievalQuery query) {
        long deadline = saturatedAdd(nanoTime.getAsLong(), totalBudget.toNanos());
        String accessToken;
        try {
            accessToken = tokenProvider.accessToken();
        } catch (RuntimeException exception) {
            throw new RagUnavailableException("Unable to acquire the RAG access token", exception);
        }

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            ensureBudget(deadline, null);
            try {
                var response = execute(query, accessToken);
                return toResult(query, response);
            } catch (RagContractException exception) {
                throw exception;
            } catch (TransientRagException | RestClientException exception) {
                if (attempt == MAX_RETRIES) {
                    throw new RagUnavailableException("RAG remained unavailable after retries", exception);
                }
                var retryAfter = exception instanceof TransientRagException transientFailure
                        ? transientFailure.retryAfter()
                        : Duration.ZERO;
                waitBeforeRetry(deadline, retryAfter, exception);
            }
        }
        throw new IllegalStateException("unreachable retry state");
    }

    private Response execute(RetrievalQuery query, String accessToken) {
        return client.post()
                .uri(QUERY_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("X-Correlation-ID", query.correlationId())
                .body(Request.from(query))
                .retrieve()
                .onStatus(status -> status.value() == 401 || status.value() == 403 || status.value() == 422,
                        (request, responseStatus) -> {
                            throw new RagContractException("RAG rejected the request with status "
                                    + responseStatus.getStatusCode().value());
                        })
                .onStatus(status -> status.value() == 429 || status.is5xxServerError(),
                        (request, responseStatus) -> {
                            throw new TransientRagException(
                                    retryAfter(responseStatus.getHeaders().getFirst("Retry-After")));
                        })
                .body(Response.class);
    }

    private void waitBeforeRetry(long deadline, Duration requestedDelay, RuntimeException cause) {
        long remaining = deadline - nanoTime.getAsLong();
        if (remaining <= 0 || safeNanos(requestedDelay) >= remaining) {
            throw new RagUnavailableException("RAG retry budget is exhausted", cause);
        }
        try {
            retryDelay.sleep(requestedDelay.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RagUnavailableException("RAG retry was interrupted", exception);
        }
        ensureBudget(deadline, cause);
    }

    private void ensureBudget(long deadline, RuntimeException cause) {
        if (nanoTime.getAsLong() >= deadline) {
            throw new RagUnavailableException("RAG request exceeded the total time budget", cause);
        }
    }

    private Duration retryAfter(String value) {
        if (value == null) {
            return Duration.ZERO;
        }
        try {
            long seconds = Long.parseLong(value);
            return seconds < 0 ? Duration.ZERO : Duration.ofSeconds(seconds);
        } catch (NumberFormatException ignored) {
            try {
                var retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                var delay = Duration.between(wallClock.instant(), retryAt);
                return delay.isNegative() ? Duration.ZERO : delay;
            } catch (RuntimeException invalidDate) {
                return Duration.ZERO;
            }
        }
    }

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static long safeNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    @FunctionalInterface
    interface RetryDelay {
        void sleep(long milliseconds) throws InterruptedException;
    }

    private static final class TransientRagException extends RuntimeException {
        private final Duration retryAfter;

        private TransientRagException(Duration retryAfter) {
            this.retryAfter = retryAfter;
        }

        private Duration retryAfter() {
            return retryAfter;
        }
    }

    private static RetrievalResult toResult(RetrievalQuery query, Response response) {
        if (response == null || !"1.0".equals(response.contractVersion())) {
            throw new RagContractException("RAG returned an unsupported contract version");
        }
        if (!query.correlationId().equals(response.correlationId())) {
            throw new RagContractException("RAG returned a different correlation id");
        }
        var matches = response.matches() == null ? List.<KnowledgeMatch>of() : response.matches().stream()
                .map(Match::toDomain)
                .toList();
        return switch (response.status()) {
            case "matches" -> new RetrievalResult.Matches(response.correlationId(), matches);
            case "no_match" -> {
                if (!matches.isEmpty()) {
                    throw new RagContractException("no_match response contains matches");
                }
                yield new RetrievalResult.NoMatch(response.correlationId());
            }
            default -> throw new RagContractException("RAG returned an unknown status");
        };
    }

    private record Request(
            @JsonProperty("contract_version") String contractVersion,
            @JsonProperty("correlation_id") String correlationId,
            String query,
            @JsonProperty("authorization_scope") Scope authorizationScope,
            @JsonProperty("allowed_corpus_kinds") List<CorpusKind> allowedCorpusKinds,
            @JsonProperty("top_k") int topK) {
        static Request from(RetrievalQuery query) {
            return new Request("1.0", query.correlationId(), query.query(), Scope.from(query.authorizationScope()),
                    query.allowedCorpusKinds(), query.topK());
        }
    }

    private record Scope(
            @JsonProperty("tenant_id") UUID tenantId,
            @JsonProperty("github_installation_id") String githubInstallationId,
            @JsonProperty("github_repository_id") String githubRepositoryId,
            @JsonProperty("pipeline_id") UUID pipelineId,
            @JsonProperty("scope_revision") String scopeRevision) {
        static Scope from(AuthorizationScope scope) {
            return new Scope(scope.tenantId(), scope.githubInstallationId(), scope.githubRepositoryId(),
                    scope.pipelineId(), scope.scopeRevision());
        }
    }

    private record Response(
            @JsonProperty("contract_version") String contractVersion,
            @JsonProperty("correlation_id") String correlationId,
            String status,
            List<Match> matches) {
    }

    private record Match(
            @JsonProperty("chunk_id") UUID chunkId,
            @JsonProperty("document_id") UUID documentId,
            @JsonProperty("corpus_kind") CorpusKind corpusKind,
            String title,
            String content,
            String provenance,
            double score) {
        KnowledgeMatch toDomain() {
            return new KnowledgeMatch(chunkId, documentId, corpusKind, title, content, provenance, score);
        }
    }
}
