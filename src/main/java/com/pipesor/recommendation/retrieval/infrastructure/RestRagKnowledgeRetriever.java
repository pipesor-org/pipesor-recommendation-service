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

import java.util.List;
import java.util.UUID;

public final class RestRagKnowledgeRetriever implements RagKnowledgeRetriever {
    static final String QUERY_PATH = "/internal/v1/retrieval/query";
    private final RestClient client;
    private final RagAccessTokenProvider tokenProvider;

    public RestRagKnowledgeRetriever(RestClient.Builder builder, RagClientProperties properties,
                                     RagAccessTokenProvider tokenProvider) {
        this.client = builder.baseUrl(properties.endpoint().toString()).build();
        this.tokenProvider = tokenProvider;
    }

    @Override
    public RetrievalResult retrieve(RetrievalQuery query) {
        try {
            var response = client.post()
                    .uri(QUERY_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.accessToken())
                    .header("X-Correlation-ID", query.correlationId())
                    .body(Request.from(query))
                    .retrieve()
                    .onStatus(status -> status.value() == 401 || status.value() == 403 || status.value() == 422,
                            (request, responseStatus) -> {
                                throw new RagContractException("RAG rejected the request with status "
                                        + responseStatus.getStatusCode().value());
                            })
                    .onStatus(HttpStatusCode::is5xxServerError, (request, responseStatus) -> {
                        throw new RagUnavailableException("RAG is unavailable", null);
                    })
                    .body(Response.class);
            return toResult(query, response);
        } catch (RagContractException | RagUnavailableException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new RagUnavailableException("RAG request failed", exception);
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
