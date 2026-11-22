package com.pipesor.recommendation.diagnosis.interfaces.rest;

import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator.Diagnosis;
import com.pipesor.recommendation.diagnosis.application.queryhandlers.ReviewAccessHandler;
import com.pipesor.recommendation.diagnosis.review.ReviewStore;
import com.pipesor.recommendation.shared.web.ApiProblem;
import com.pipesor.recommendation.shared.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Human review of one admitted diagnosis: {@code GET} needs session scope {@code Diagnosis.Read},
 * {@code POST} needs {@code Diagnosis.Review} and OPERATE on the incident's pipeline. The reviewer
 * is the session subject, never a body field. {@code expectedRevision} gives optimistic concurrency;
 * an optional {@code Idempotency-Key} makes a retried submission return the same result.
 */
@RestController
public class ReviewController {
    public static final String PATH = "/api/v1/diagnoses/{diagnosisId}/reviews";
    private static final int MAX_BYTES = 8192;
    private static final Set<String> FIELDS = Set.of("diagnosisVersion", "expectedRevision", "decision", "comment",
            "actualCause", "correctedRecommendation");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION).build();
    private final ReviewAccessHandler handler;

    public record View(UUID diagnosticId, int diagnosisVersion, int reviewRevision, String status, Diagnosis original,
                       List<ReviewStore.Review> history) {
        static View of(ReviewStore.Snapshot snapshot) {
            var history = snapshot.history();
            return new View(snapshot.diagnosticId(), snapshot.version(), history.size(),
                    history.isEmpty() ? "PENDING" : history.getLast().decision().name(), snapshot.original(), history);
        }
    }

    public ReviewController(ReviewAccessHandler handler) {
        this.handler = Objects.requireNonNull(handler);
    }

    @GetMapping(PATH)
    public ResponseEntity<View> read(@PathVariable String diagnosisId, @AuthenticationPrincipal Jwt session,
            HttpServletRequest request) {
        var snapshot = handler.read(Ids.uuid(diagnosisId), session.getTokenValue(), CorrelationIdFilter.current(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(View.of(snapshot));
    }

    @PostMapping(PATH)
    public ResponseEntity<View> review(@PathVariable String diagnosisId, @AuthenticationPrincipal Jwt session,
            HttpServletRequest request) throws IOException {
        UUID id = Ids.uuid(diagnosisId);
        var type = request.getContentType() == null ? null : parse(request.getContentType());
        if (type == null || !MediaType.APPLICATION_JSON.includes(type)) throw new ApiProblem(415, "json_required");
        if (request.getContentLengthLong() > MAX_BYTES) throw new ApiProblem(413, "too_large");
        byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (body.length > MAX_BYTES) throw new ApiProblem(413, "too_large");
        UUID requestId = idempotencyKey(request);
        var parsed = parse(body);
        var result = handler.append(id, session.getTokenValue(), CorrelationIdFilter.current(request), requestId,
                session.getSubject(), parsed.version(), parsed.expectedRevision(), parsed.decision(), parsed.comment(),
                parsed.actualCause(), parsed.correctedRecommendation());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(View.of(result));
    }

    private static MediaType parse(String value) {
        try {
            return MediaType.parseMediaType(value);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static UUID idempotencyKey(HttpServletRequest request) {
        var keys = Collections.list(request.getHeaders("Idempotency-Key"));
        if (keys.isEmpty()) return UUID.randomUUID();
        try {
            if (keys.size() == 1) {
                var key = UUID.fromString(keys.getFirst());
                if (key.toString().equals(keys.getFirst())) return key;
            }
        } catch (IllegalArgumentException invalid) {
            // Reported below.
        }
        throw new ApiProblem(400, "invalid_review");
    }

    private record Parsed(Integer version, int expectedRevision, ReviewStore.Decision decision, String comment,
                          String actualCause, String correctedRecommendation) { }

    private static Parsed parse(byte[] bytes) {
        try {
            var tree = JSON.readTree(bytes);
            if (!tree.isObject() || !tree.path("expectedRevision").isInt()
                    || tree.propertyNames().stream().anyMatch(name -> !FIELDS.contains(name))) {
                throw new IllegalArgumentException();
            }
            Integer version = null;
            if (tree.has("diagnosisVersion")) {
                if (!tree.get("diagnosisVersion").isInt() || tree.get("diagnosisVersion").asInt() < 1) {
                    throw new IllegalArgumentException();
                }
                version = tree.get("diagnosisVersion").asInt();
            }
            var decision = ReviewStore.Decision.valueOf(tree.path("decision").asString(""));
            int revision = tree.get("expectedRevision").asInt();
            String comment = text(tree.get("comment"), false);
            String cause = text(tree.get("actualCause"), decision != ReviewStore.Decision.CORRECTED);
            String corrected = text(tree.get("correctedRecommendation"), true);
            if (revision < 0 || (decision != ReviewStore.Decision.CORRECTED && (cause != null || corrected != null))) {
                throw new IllegalArgumentException();
            }
            return new Parsed(version, revision, decision, comment, cause, corrected);
        } catch (RuntimeException failure) {
            throw new ApiProblem(400, "invalid_review");
        }
    }

    private static String text(JsonNode node, boolean optional) {
        if (optional && (node == null || node.isNull())) return null;
        if (node == null || !node.isString()) throw new IllegalArgumentException();
        String value = node.asString().strip();
        if (value.isBlank() || value.length() > 1500 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException();
        }
        return value;
    }
}
