package com.pipesor.recommendation.diagnosis.infrastructure;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator;
import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator.Evidence;
import com.pipesor.recommendation.retrieval.domain.AuthorizationScope;
import com.pipesor.recommendation.retrieval.domain.CorpusKind;
import com.pipesor.recommendation.retrieval.domain.RetrievalQuery;
import com.pipesor.recommendation.retrieval.domain.RetrievalResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AzureOpenAiInferenceTest {
    private static final URI ENDPOINT = URI.create("https://synthetic.openai.azure.com");
    private static final UUID EVIDENCE_ID = UUID.fromString("e6988490-6322-590d-b48d-662b154b55d9");
    private static final UUID OTHER_ID = UUID.fromString("f6988490-6322-590d-b48d-662b154b55d9");
    private static final List<Evidence> SIGNALS = List.of(
            new Evidence(EVIDENCE_ID, OTHER_ID, "INFRASTRUCTURE", "Could not resolve host: example.invalid"));
    private static final String PATH = ENDPOINT + "/openai/v1/chat/completions";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final TokenCredential credential = request -> {
        assertThat(request.getScopes()).containsExactly("https://ai.azure.com/.default");
        return Mono.just(new AccessToken("synthetic-entra-token", OffsetDateTime.now().plusMinutes(5)));
    };
    private final AzureOpenAiInference inference =
            new AzureOpenAiInference(properties(ENDPOINT), credential, builder.build());

    @Test
    void sendsScopedRequestAndComposesProviderHypothesisWithEvidence() {
        server.expect(requestTo(PATH))
                .andExpect(header("Authorization", "Bearer synthetic-entra-token"))
                .andExpect(jsonPath("$.model").value("fixture-deployment"))
                .andExpect(jsonPath("$.max_completion_tokens").value(512))
                .andExpect(jsonPath("$.messages[0].content").value(AzureOpenAiInference.SYSTEM_PROMPT))
                .andExpect(jsonPath("$.response_format.json_schema.strict").value(true))
                .andExpect(jsonPath("$.response_format.json_schema.schema.properties.evidenceId.enum[0]").value(EVIDENCE_ID.toString()))
                .andExpect(jsonPath("$.response_format.json_schema.schema.properties.chunkIds.type").value("null"))
                .andExpect(jsonPath("$.messages[1].content").value(org.hamcrest.Matchers.containsString(EVIDENCE_ID.toString())))
                .andExpect(jsonPath("$.messages[1].content").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(OTHER_ID.toString()))))
                .andRespond(withSuccess(reply("stop", hypothesis(EVIDENCE_ID)), MediaType.APPLICATION_JSON));
        var result = orchestrator().diagnose(OTHER_ID, OTHER_ID, OTHER_ID, query(), SIGNALS);
        assertThat(result.hypothesis().probableCause()).isEqualTo("Host resolution failed");
        assertThat(result.hypothesis().model()).isEqualTo(new DiagnosticOrchestrator.Model("fixture-deployment",
                "gpt-fixture-2026-01-01", AzureOpenAiInference.PROMPT_VERSION));
        assertThat(result.evidence()).containsExactlyElementsOf(SIGNALS);
        assertThat(result.correlationId()).isEqualTo("fixture-correlation");
        server.verify();
    }

    @Test
    void acceptsOnlyAnAzureAccountOriginForFoundryChat() {
        var endpoint = URI.create("https://synthetic.services.ai.azure.com");
        var provider = new AzureOpenAiInference(properties(endpoint), credential, builder.build());
        server.expect(requestTo(endpoint + "/openai/v1/chat/completions"))
                .andExpect(header("Authorization", "Bearer synthetic-entra-token"))
                .andRespond(withSuccess(reply("stop", hypothesis(EVIDENCE_ID)), MediaType.APPLICATION_JSON));
        assertThat(provider.infer(SIGNALS, List.of()).probableCause()).isEqualTo("Host resolution failed");
        server.verify();

        for (var value : List.of("https://synthetic.services.ai.azure.com.example.invalid",
                "https://synthetic.services.ai.azure.com/api/projects/demo",
                "http://synthetic.services.ai.azure.com")) {
            assertThatThrownBy(() -> new AzureOpenAiInference(properties(URI.create(value)), credential,
                    builder.build())).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void refusesIncompleteOutputAndHidesProviderDetails() {
        server.expect(requestTo(PATH)).andRespond(withSuccess(reply("length", "sensitive fixture"), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> inference.infer(SIGNALS, List.of()))
                .hasMessage("Azure OpenAI inference failed or returned invalid output").hasNoCause();
        server.verify();
    }

    @Test
    void refusesUnauthorizedAndUnavailableProviderResponses() {
        server.expect(requestTo(PATH)).andRespond(withStatus(HttpStatus.FORBIDDEN).body("private provider detail"));
        server.expect(requestTo(PATH)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("private provider detail"));
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> inference.infer(SIGNALS, List.of()))
                    .hasMessage("Azure OpenAI inference failed or returned invalid output").hasNoCause();
        }
        server.verify();
    }

    @Test
    void rejectsMalformedStructuredOutput() {
        server.expect(requestTo(PATH)).andRespond(withSuccess(reply("stop", "{}"), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> inference.infer(SIGNALS, List.of())).isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    @Test
    void orchestratorRejectsUnsupportedProviderCitations() {
        server.expect(requestTo(PATH)).andRespond(withSuccess(reply("stop", hypothesis(OTHER_ID)), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> orchestrator().diagnose(OTHER_ID, OTHER_ID, OTHER_ID, query(), SIGNALS))
                .hasMessage("Invalid diagnostic hypothesis or citations");
        server.verify();
    }

    @Test
    void toleratesARepeatedPrimaryCitationButNotOtherDuplicates() {
        // GPT-4o repeated the single evidence id in additionalEvidenceIds on the authentic run 43055757606.
        var repeated = JsonMapper.builder().build().readTree(hypothesis(EVIDENCE_ID));
        ((tools.jackson.databind.node.ObjectNode) repeated).putArray("additionalEvidenceIds").add(EVIDENCE_ID.toString());
        server.expect(requestTo(PATH)).andRespond(withSuccess(reply("stop", repeated.toString()), MediaType.APPLICATION_JSON));
        var result = orchestrator().diagnose(OTHER_ID, OTHER_ID, OTHER_ID, query(), SIGNALS);
        assertThat(result.hypothesis().evidenceIds()).containsExactly(EVIDENCE_ID);
        server.verify();
    }

    private static InferenceProperties properties(URI endpoint) {
        return new InferenceProperties(endpoint, "fixture-deployment", null, Duration.ofSeconds(2), 512);
    }

    private DiagnosticOrchestrator orchestrator() {
        return new DiagnosticOrchestrator(query -> new RetrievalResult.NoMatch(query.correlationId()), inference);
    }

    private static RetrievalQuery query() {
        return new RetrievalQuery("fixture-correlation", "fixture", new AuthorizationScope(OTHER_ID, "1", "2", null,
                "fixture-scope"), List.of(CorpusKind.AUTHORIZED_TECHNICAL_DOCUMENTATION), 1);
    }

    private static String hypothesis(UUID id) {
        var node = JsonMapper.builder().build().createObjectNode()
                .put("probableCause", "Host resolution failed").put("recommendation", "Check DNS")
                .put("limitations", "Synthetic evidence only").put("evidenceId", id.toString())
                .putNull("chunkIds");
        node.putArray("additionalEvidenceIds");
        return node.toString();
    }

    private static String reply(String finish, String content) {
        return JsonMapper.builder().build().writeValueAsString(Map.of("model", "gpt-fixture-2026-01-01", "choices",
                List.of(Map.of("finish_reason", finish, "message", Map.of("content", content)))));
    }
}
