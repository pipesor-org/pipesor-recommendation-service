package com.pipesor.recommendation.receipt;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Explicit composition only. The authority must verify caller, current scope and references. */
public final class ReceiptEndpoint {
    private static final String PATH = "/internal/v1/correlated-contexts";

    private ReceiptEndpoint() { }

    public enum Decision { AUTHORIZED, UNAUTHENTICATED, FORBIDDEN, UNAVAILABLE }

    @FunctionalInterface
    public interface Authority {
        Decision verify(String bearerToken, ReceiptSubmission submission);
    }

    public static RouterFunction<ServerResponse> routes(DataSource dataSource, Authority authority, int maxRequestBytes) {
        Objects.requireNonNull(authority);
        if (maxRequestBytes < 1 || maxRequestBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("An explicit request byte limit is required");
        }
        var store = new ReceiptStore(Objects.requireNonNull(dataSource));
        return RouterFunctions.route().POST(PATH, request -> receive(request, store, authority, maxRequestBytes)).build();
    }

    private static ServerResponse receive(ServerRequest request, ReceiptStore store, Authority authority, int limit) {
        String header = single(request, "Authorization");
        if (header == null || !header.startsWith("Bearer ") || header.length() == 7
                || header.substring(7).chars().anyMatch(c -> c <= 0x20 || c >= 0x7f)) {
            return error(401, "unauthenticated");
        }
        ReceiptSubmission submission;
        try {
            var contentType = request.headers().contentType().orElse(null);
            if (contentType == null || !contentType.getType().equalsIgnoreCase("application")
                    || !contentType.getSubtype().equalsIgnoreCase("json")
                    || (contentType.getCharset() != null && !StandardCharsets.UTF_8.equals(contentType.getCharset()))) {
                return error(400, "invalid_context");
            }
            if (request.servletRequest().getContentLengthLong() > limit) return error(413, "payload_too_large");
            byte[] body = request.servletRequest().getInputStream().readNBytes(limit + 1);
            if (body.length > limit) return error(413, "payload_too_large");
            submission = ReceiptJson.decode(body, single(request, "Idempotency-Key"), single(request, "X-Correlation-ID"));
        } catch (IllegalArgumentException | IOException failure) {
            return error(400, "invalid_context");
        }
        try {
            return switch (authority.verify(header.substring(7), submission)) {
                case AUTHORIZED -> {
                    boolean inserted = store.accept(submission);
                    String id = submission.operationId().toString();
                    yield ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(Map.of(
                            "contract_version", "1.0", "operation_id", id, "context_id", id,
                            "status", inserted ? "accepted" : "duplicate"));
                }
                case UNAUTHENTICATED -> error(401, "unauthenticated");
                case FORBIDDEN -> error(403, "forbidden");
                case UNAVAILABLE -> error(503, "temporarily_unavailable");
            };
        } catch (ReceiptStore.Conflict failure) {
            return error(409, "operation_conflict");
        } catch (RuntimeException failure) {
            return error(503, "temporarily_unavailable");
        }
    }

    private static String single(ServerRequest request, String name) {
        List<String> values = request.headers().header(name);
        return values.size() == 1 ? values.getFirst() : null;
    }

    private static ServerResponse error(int status, String code) {
        return ServerResponse.status(status).contentType(MediaType.APPLICATION_JSON).body(Map.of("code", code));
    }
}
