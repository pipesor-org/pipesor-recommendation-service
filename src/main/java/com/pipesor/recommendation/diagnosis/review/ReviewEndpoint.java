package com.pipesor.recommendation.diagnosis.review;

import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator.Diagnosis;
import com.pipesor.recommendation.retrieval.domain.AuthorizationScope;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Explicit composition. The authority must verify both the current subject and repository grant. */
public final class ReviewEndpoint {
    public static final String PATH = "/local-demo/v1/diagnoses/{id}/review";
    private static final int MAX_BYTES = 8192;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private ReviewEndpoint() {}

    public interface Authority {
        String authenticate(String bearer);
        boolean allows(String reviewer, AuthorizationScope scope, boolean write);
    }

    public static RouterFunction<ServerResponse> routes(ReviewStore store, Authority authority) {
        Objects.requireNonNull(store);
        Objects.requireNonNull(authority);
        return RouterFunctions.route()
                .GET(PATH, request -> handle(request, store, authority, false))
                .POST(PATH, request -> handle(request, store, authority, true)).build();
    }

    private static ServerResponse handle(ServerRequest request, ReviewStore store, Authority authority, boolean write) {
        var headers = request.headers().header("Authorization");
        if (headers.size() != 1 || !headers.getFirst().matches("Bearer [!-~]{1,2048}")) {
            return error(401, "unauthenticated");
        }
        try {
            String reviewer = authority.authenticate(headers.getFirst().substring(7));
            if (reviewer == null) return error(401, "unauthenticated");
            UUID id;
            try {
                id = uuid(request.pathVariable("id"));
            } catch (IllegalArgumentException failure) {
                return error(404, "not_found");
            }
            var snapshot = store.read(id);
            if (snapshot == null || !authority.allows(reviewer, snapshot.scope(), write)) {
                return error(404, "not_found");
            }
            if (write) {
                var type = request.headers().contentType().orElse(null);
                if (type == null || !MediaType.APPLICATION_JSON.includes(type)) return error(415, "json_required");
                byte[] body = request.servletRequest().getInputStream().readNBytes(MAX_BYTES + 1);
                if (body.length > MAX_BYTES) return error(413, "too_large");
                var keys = request.headers().header("Idempotency-Key");
                try {
                    if (keys.size() != 1) throw new IllegalArgumentException();
                    snapshot = store.append(snapshot, uuid(keys.getFirst()), reviewer, submission(body));
                } catch (IllegalArgumentException failure) {
                    return error(400, "invalid_review");
                }
            }
            var history = snapshot.history();
            return ServerResponse.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON)
                    .body(new View(id, snapshot.version(), history.size(), history.isEmpty() ? "PENDING"
                            : history.getLast().decision().name(), snapshot.original(), history));
        } catch (ReviewStore.Conflict failure) {
            return error(409, "review_conflict");
        } catch (SQLException | java.io.IOException | RuntimeException failure) {
            return error(503, "temporarily_unavailable");
        }
    }

    private static UUID uuid(String value) {
        var id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw new IllegalArgumentException();
        return id;
    }

    private static ReviewStore.Submission submission(byte[] bytes) {
        try {
            var tree = JSON.readTree(bytes);
            if (!tree.isObject() || !tree.path("diagnosisVersion").isInt() || !tree.path("expectedRevision").isInt()
                    || tree.propertyNames().stream().anyMatch(name -> !List.of("diagnosisVersion", "expectedRevision",
                            "decision", "comment", "actualCause", "correctedRecommendation").contains(name))) {
                throw new IllegalArgumentException();
            }
            var decision = ReviewStore.Decision.valueOf(tree.path("decision").asString(""));
            int version = tree.get("diagnosisVersion").asInt();
            int revision = tree.get("expectedRevision").asInt();
            String comment = text(tree.get("comment"), false);
            String cause = text(tree.get("actualCause"), decision != ReviewStore.Decision.CORRECTED);
            String corrected = text(tree.get("correctedRecommendation"), true);
            if (version < 1 || revision < 0 || (decision != ReviewStore.Decision.CORRECTED
                    && (cause != null || corrected != null))) throw new IllegalArgumentException();
            return new ReviewStore.Submission(version, revision, decision, comment, cause, corrected);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Invalid review");
        }
    }

    private static String text(tools.jackson.databind.JsonNode node, boolean optional) {
        if (optional && (node == null || node.isNull())) return null;
        if (node == null || !node.isTextual()) throw new IllegalArgumentException();
        String value = node.asString().strip();
        if (value.isBlank() || value.length() > 1500 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException();
        }
        return value;
    }

    private static ServerResponse error(int status, String code) {
        var response = ServerResponse.status(status).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON);
        if (status == 401) response.header("WWW-Authenticate", "Bearer");
        return response.body(Map.of("code", code));
    }

    public record View(UUID diagnosticId, int diagnosisVersion, int reviewRevision, String status, Diagnosis original,
                       List<ReviewStore.Review> history) {}
}
