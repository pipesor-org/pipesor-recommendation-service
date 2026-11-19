package com.pipesor.recommendation.diagnosis.interfaces.rest;

import com.pipesor.recommendation.diagnosis.application.DiagnosticOrchestrator.Diagnosis;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Explicit local-fixture composition for one synthetic result. */
public final class LocalDiagnosticEndpoint {
    public static final String PATH = "/local-demo/v1/diagnosis";

    private LocalDiagnosticEndpoint() { }

    public static RouterFunction<ServerResponse> routes(String token, Supplier<Diagnosis> result) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("An ephemeral 256-bit base64url demo token is required");
        }
        Objects.requireNonNull(result);
        byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.US_ASCII);
        return RouterFunctions.route().GET(PATH, request -> {
            var authorization = request.headers().header("Authorization");
            if (authorization.size() != 1 || !MessageDigest.isEqual(expected,
                    authorization.getFirst().getBytes(StandardCharsets.UTF_8))) {
                return ServerResponse.status(401).header("WWW-Authenticate", "Bearer")
                        .cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("code", "unauthenticated"));
            }
            Diagnosis diagnosis = result.get();
            if (diagnosis == null) {
                return ServerResponse.status(503).cacheControl(CacheControl.noStore())
                        .contentType(MediaType.APPLICATION_JSON).body(Map.of("code", "diagnosis_unavailable"));
            }
            return ServerResponse.ok().cacheControl(CacheControl.noStore())
                    .contentType(MediaType.APPLICATION_JSON).body(diagnosis);
        }).build();
    }
}
