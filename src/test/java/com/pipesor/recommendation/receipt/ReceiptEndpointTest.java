package com.pipesor.recommendation.receipt;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "pipesor.rag.endpoint=https://rag.example.invalid", "pipesor.rag.audience=api://00000000-0000-0000-0000-000000000001"
}, classes = {com.pipesor.recommendation.RecommendationServiceApplication.class, ReceiptEndpointTest.Fixture.class})
public class ReceiptEndpointTest {
    static final int LIMIT = 8192;
    static final JsonMapper JSON = JsonMapper.builder().build();
    @LocalServerPort int port;

    @TestConfiguration(proxyBeanMethods = false)
    public static class Fixture {
        @Bean(initMethod = "start", destroyMethod = "stop")
        PostgreSQLContainer receiptDatabase() {
            return new PostgreSQLContainer("postgres:17.11");
        }

        @Bean
        PGSimpleDataSource receiptDataSource(PostgreSQLContainer database) {
            var source = new PGSimpleDataSource();
            source.setURL(database.getJdbcUrl());
            source.setUser(database.getUsername());
            source.setPassword(database.getPassword());
            Flyway.configure().dataSource(source).defaultSchema("diagnosis").load().migrate();
            return source;
        }

        @Bean
        RouterFunction<ServerResponse> receiptRoutes(PGSimpleDataSource source) {
            return ReceiptEndpoint.routes(source, (token, submission) -> switch (token) {
                case "synthetic-authorized" -> ReceiptEndpoint.Decision.AUTHORIZED;
                case "synthetic-forbidden" -> ReceiptEndpoint.Decision.FORBIDDEN;
                case "synthetic-unavailable" -> ReceiptEndpoint.Decision.UNAVAILABLE;
                default -> ReceiptEndpoint.Decision.UNAUTHENTICATED;
            }, LIMIT);
        }
    }

    @org.springframework.beans.factory.annotation.Autowired PGSimpleDataSource source;

    @Test
    void recoversDuplicateWhenFirstResponseIsDiscardedAndPreservesExactPayload() throws Exception {
        var fixture = fixture(1);
        var request = request(fixture, fixture.get("proposed_canonical_json").asString(), "synthetic-authorized");
        try (var client = HttpClient.newHttpClient()) {
            assertThat(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
            var duplicate = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertThat(duplicate.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(duplicate.body())).isEqualTo(fixture.get("duplicate_receipt"));
        }
        var submission = fixture.get("submission");
        try (var connection = source.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT request_fingerprint, signals, scope_revision
                     FROM diagnosis.correlated_context_receipts WHERE operation_id = ?::uuid
                     """)) {
            statement.setString(1, submission.get("operation_id").asString());
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo(fixture.get("proposed_fingerprint").asString());
                assertThat(JSON.readTree(rows.getString(2))).isEqualTo(submission.get("signals"));
                assertThat(rows.getString(3)).isEqualTo("scope-13");
                assertThat(rows.next()).isFalse();
            }
        }
        assertThat(new ReceiptStore(source).accept(ReceiptJson.decode(
                fixture.get("proposed_canonical_json").asString().getBytes(StandardCharsets.UTF_8),
                fixture.get("headers").get("Idempotency-Key").asString(),
                fixture.get("headers").get("X-Correlation-ID").asString()))).isFalse();
    }

    @Test
    void checksCurrentAuthorizationBeforeDuplicateAndRejectsChangedOrOversizedRequests() throws Exception {
        var fixture = fixture(0);
        String body = fixture.get("proposed_canonical_json").asString();
        try (var client = HttpClient.newHttpClient()) {
            var first = client.send(request(fixture, body, "synthetic-authorized"), HttpResponse.BodyHandlers.ofString());
            assertThat(first.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(first.body())).isEqualTo(fixture.get("accepted_receipt"));
            error(client, fixture, body, "synthetic-forbidden", 403, "forbidden");
            error(client, fixture, body, "synthetic-unavailable", 503, "temporarily_unavailable");
            error(client, fixture, body, "synthetic-bad-token", 401, "unauthenticated");
            error(client, fixture, body.replace("scope-12", "scope-99"), "synthetic-authorized", 409, "operation_conflict");
            error(client, fixture, body + " {}", "synthetic-authorized", 400, "invalid_context");
            error(client, fixture, " ".repeat(LIMIT) + body, "synthetic-authorized", 413, "payload_too_large");
            var duplicate = client.send(request(fixture, " " + body, "synthetic-authorized"), HttpResponse.BodyHandlers.ofString());
            assertThat(duplicate.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(duplicate.body())).isEqualTo(fixture.get("duplicate_receipt"));
        }
    }

    private void error(HttpClient client, JsonNode fixture, String body, String token, int status, String code) throws Exception {
        var response = client.send(request(fixture, body, token), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(JSON.readTree(response.body())).isEqualTo(JSON.createObjectNode().put("code", code));
    }

    static JsonNode fixture(int index) throws Exception {
        try (var input = ReceiptEndpointTest.class.getResourceAsStream("/correlation-recommendation-v1.examples.json")) {
            return JSON.readTree(input.readAllBytes()).get("fixtures").get(index);
        }
    }

    private HttpRequest request(JsonNode fixture, String body, String token) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/v1/correlated-contexts"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", fixture.get("headers").get("Idempotency-Key").asString())
                .header("X-Correlation-ID", fixture.get("headers").get("X-Correlation-ID").asString())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }
}
