package com.pipesor.recommendation.retrieval.infrastructure;

import com.pipesor.recommendation.retrieval.application.ports.RagContractException;
import com.pipesor.recommendation.retrieval.application.ports.RagUnavailableException;
import com.pipesor.recommendation.retrieval.domain.AuthorizationScope;
import com.pipesor.recommendation.retrieval.domain.CorpusKind;
import com.pipesor.recommendation.retrieval.domain.RetrievalQuery;
import com.pipesor.recommendation.retrieval.domain.RetrievalResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestRagKnowledgeRetrieverTest {
    private static final String CORRELATION_ID = "corr-123";
    private MockRestServiceServer server;
    private RestRagKnowledgeRetriever retriever;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var properties = new RagClientProperties(
                URI.create("https://rag.internal"),
                "api://11111111-1111-1111-1111-111111111111",
                Duration.ofSeconds(15));
        retriever = new RestRagKnowledgeRetriever(builder, properties, () -> "entra-token");
    }

    @Test
    void sendsTheApprovedContractAndPreservesMatchProvenance() {
        server.expect(once(), requestTo("https://rag.internal/internal/v1/retrieval/query"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer entra-token"))
                .andExpect(header("X-Correlation-ID", CORRELATION_ID))
                .andExpect(jsonPath("$.contract_version").value("1.0"))
                .andExpect(jsonPath("$.authorization_scope.github_repository_id").value("456"))
                .andExpect(jsonPath("$.allowed_corpus_kinds[0]").value("reviewed_incidents"))
                .andRespond(withSuccess(matchesResponse(), MediaType.APPLICATION_JSON));

        var result = retriever.retrieve(query());

        var matches = (RetrievalResult.Matches) result;
        assertThat(matches.correlationId()).isEqualTo(CORRELATION_ID);
        assertThat(matches.matches()).singleElement().satisfies(match -> {
            assertThat(match.provenance()).isEqualTo("incident:verified-7");
            assertThat(match.content()).isEqualTo("Sanitized evidence");
            assertThat(match.corpusKind()).isEqualTo(CorpusKind.REVIEWED_INCIDENTS);
        });
        server.verify();
    }

    @Test
    void distinguishesAValidNoMatchFromFailure() {
        server.expect(once(), requestTo("https://rag.internal/internal/v1/retrieval/query"))
                .andRespond(withSuccess("""
                        {"contract_version":"1.0","correlation_id":"corr-123","status":"no_match","matches":[]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(retriever.retrieve(query())).isEqualTo(new RetrievalResult.NoMatch(CORRELATION_ID));
    }

    @Test
    void exposesDependencyFailureAsRecoverableUnavailability() {
        server.expect(org.springframework.test.web.client.ExpectedCount.times(3),
                        requestTo("https://rag.internal/internal/v1/retrieval/query"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> retriever.retrieve(query()))
                .isInstanceOf(RagUnavailableException.class);
    }

    @Test
    void retriesTransientFailureAndHonorsRetryAfterWithinTheBudget() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var properties = properties();
        var time = new AtomicLong();
        var delays = new AtomicInteger();
        retriever = new RestRagKnowledgeRetriever(builder, properties, () -> "entra-token", time::get,
                milliseconds -> {
                    delays.incrementAndGet();
                    assertThat(milliseconds).isEqualTo(2000);
                    time.addAndGet(Duration.ofMillis(milliseconds).toNanos());
                });
        server.expect(once(), requestTo("https://rag.internal/internal/v1/retrieval/query"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "2"));
        server.expect(once(), requestTo("https://rag.internal/internal/v1/retrieval/query"))
                .andRespond(withSuccess("""
                        {"contract_version":"1.0","correlation_id":"corr-123","status":"no_match","matches":[]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(retriever.retrieve(query())).isEqualTo(new RetrievalResult.NoMatch(CORRELATION_ID));
        assertThat(delays).hasValue(1);
        server.verify();
    }

    @Test
    void doesNotRetryWhenRetryAfterExceedsTheTotalBudget() {
        server.expect(once(), requestTo("https://rag.internal/internal/v1/retrieval/query"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "15"));

        assertThatThrownBy(() -> retriever.retrieve(query()))
                .isInstanceOf(RagUnavailableException.class)
                .hasMessageContaining("budget");
        server.verify();
    }

    @Test
    void doesNotRetryAuthenticationOrAuthorizationFailures() {
        server.expect(once(), requestTo("https://rag.internal/internal/v1/retrieval/query"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> retriever.retrieve(query()))
                .isInstanceOf(RagContractException.class)
                .hasMessageContaining("403");
        server.verify();
    }

    @Test
    void rejectsMismatchedCorrelationContext() {
        server.expect(once(), requestTo("https://rag.internal/internal/v1/retrieval/query"))
                .andRespond(withSuccess("""
                        {"contract_version":"1.0","correlation_id":"other","status":"no_match","matches":[]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> retriever.retrieve(query()))
                .isInstanceOf(RagContractException.class)
                .hasMessageContaining("correlation id");
    }

    private static RetrievalQuery query() {
        return new RetrievalQuery(
                CORRELATION_ID,
                "sanitized failure evidence",
                new AuthorizationScope(
                        UUID.fromString("22222222-2222-2222-2222-222222222222"),
                        "123",
                        "456",
                        UUID.fromString("33333333-3333-3333-3333-333333333333"),
                        "scope-v1"),
                List.of(CorpusKind.REVIEWED_INCIDENTS),
                5);
    }

    private static RagClientProperties properties() {
        return new RagClientProperties(
                URI.create("https://rag.internal"),
                "api://11111111-1111-1111-1111-111111111111",
                Duration.ofSeconds(15));
    }

    private static String matchesResponse() {
        return """
                {
                  "contract_version": "1.0",
                  "correlation_id": "corr-123",
                  "status": "matches",
                  "matches": [{
                    "chunk_id": "44444444-4444-4444-4444-444444444444",
                    "document_id": "55555555-5555-5555-5555-555555555555",
                    "corpus_kind": "reviewed_incidents",
                    "title": "Verified incident",
                    "content": "Sanitized evidence",
                    "provenance": "incident:verified-7",
                    "score": 0.91
                  }]
                }
                """;
    }
}
