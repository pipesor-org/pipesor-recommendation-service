package com.pipesor.recommendation.api;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.pipesor.recommendation.shared.security.PlatformIds;
import com.pipesor.recommendation.shared.security.SigningKey;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Test-only peers on loopback: JWKS for Correlation, sessions and GitHub Actions, a Pipeline
 * entitlement resolver, Pipeline's diagnosis summary receiver and a RAG endpoint. Simulated
 * backends exist only in automated tests.
 */
final class PlatformFixture implements AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final UUID TENANT = UUID.fromString("24c96be0-50f3-4064-b5e4-643088141ae8");
    final KeyPair correlation = rsa();
    final KeyPair session = rsa();
    final KeyPair github = rsa();
    final KeyPair recommendation = rsa();
    final Path signingKeyFile;
    final HttpServer server;
    final Map<String, Recorded> requests = new ConcurrentHashMap<>();
    volatile Function<Recorded, Reply> pipeline = request -> new Reply(503, "{}");
    volatile Function<Recorded, Reply> rag = request -> new Reply(503, "{}");
    /** Diagnosis summary pushes in arrival order; the default reply acknowledges by echoing the request. */
    final List<Push> summaries = new CopyOnWriteArrayList<>();
    volatile Function<Push, Reply> summary = PlatformFixture::acknowledge;

    record Recorded(Map<String, List<String>> headers, String body) {
        String header(String name) {
            return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(entry -> entry.getValue().getFirst()).findFirst().orElse(null);
        }
    }

    record Reply(int status, String body) { }

    record Push(String method, String path, Recorded request) { }

    PlatformFixture() throws IOException {
        var directory = Files.createTempDirectory("pipesor-recommendation-test");
        signingKeyFile = directory.resolve("recommendation.pem");
        Files.writeString(signingKeyFile, "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(recommendation.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n");
        Files.setPosixFilePermissions(signingKeyFile, PosixFilePermissions.fromString("rw-------"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwks("/jwks/correlation", correlation);
        jwks("/jwks/session", session);
        jwks("/jwks/github", github);
        server.createContext("/pipeline/internal/v1/diagnosis-read-entitlements", exchange -> answer(exchange, "pipeline"));
        server.createContext("/rag/internal/v1/retrieval/query", exchange -> answer(exchange, "rag"));
        server.createContext("/pipeline/internal/v1/incidents/", this::receiveSummary);
        server.start();
    }

    String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    private void jwks(String path, KeyPair pair) {
        // Same key id as SigningKey (RFC 7638 thumbprint), so workload tokens resolve their kid.
        String body = new JWKSet(key(pair).publicJwk()).toString(true);
        server.createContext(path, exchange -> write(exchange, 200, body));
    }

    private void answer(HttpExchange exchange, String peer) throws IOException {
        var recorded = new Recorded(Map.copyOf(exchange.getRequestHeaders()),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        requests.put(peer, recorded);
        var reply = ("pipeline".equals(peer) ? pipeline : rag).apply(recorded);
        write(exchange, reply.status(), reply.body());
    }

    private void receiveSummary(HttpExchange exchange) throws IOException {
        var push = new Push(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                new Recorded(Map.copyOf(exchange.getRequestHeaders()),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        summaries.add(push);
        var reply = summary.apply(push);
        write(exchange, reply.status(), reply.body());
    }

    private static Reply acknowledge(Push push) {
        var body = JSON.readTree(push.request().body());
        return new Reply(200, JSON.writeValueAsString(Map.of("incidentId", push.path().split("/")[5],
                "diagnosisId", body.get("diagnosisId").asString(), "version", body.get("version").asInt(),
                "reviewRevision", body.get("reviewRevision").asInt(), "status", body.get("status").asString(),
                "applied", true)));
    }

    private static void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    SigningKey correlationKey() { return key(correlation); }

    String session(long githubUser, String scopes) throws Exception {
        String human = PlatformIds.human(githubUser).toString();
        Instant now = Instant.now();
        return sign(session, new JWTClaimsSet.Builder().issuer("urn:pipesor:session")
                .audience(List.of("urn:pipesor:api:pipeline", "urn:pipesor:api:recommendation"))
                .subject(human).claim("oid", human).claim("azp", PlatformIds.DASHBOARD_CLIENT.toString())
                .claim("tid", TENANT.toString()).claim("scp", scopes).claim("github_user_id", Long.toString(githubUser))
                .claim("ver", "2.0").issueTime(Date.from(now)).notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600))).jwtID(UUID.randomUUID().toString()).build());
    }

    String gitHubActions(String repositoryId) throws Exception {
        Instant now = Instant.now();
        return sign(github, new JWTClaimsSet.Builder().issuer("https://token.actions.githubusercontent.com")
                .audience("urn:pipesor:api:recommendation").subject("repo:octo/demo:ref:refs/heads/main")
                .claim("repository_id", repositoryId).claim("repository_owner_id", "9001").claim("run_id", "77")
                .issueTime(Date.from(now)).notBeforeTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300)))
                .build());
    }

    static String sign(KeyPair pair, JWTClaimsSet claims) throws Exception {
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner(pair.getPrivate()));
        return jwt.serialize();
    }

    static SigningKey key(KeyPair pair) {
        return new SigningKey() {
            @Override public RSAPublicKey publicKey() { return (RSAPublicKey) pair.getPublic(); }
            @Override public byte[] sign(byte[] input) {
                try {
                    var signature = Signature.getInstance("SHA256withRSA");
                    signature.initSign(pair.getPrivate());
                    signature.update(input);
                    return signature.sign();
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            }
        };
    }

    private static KeyPair rsa() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    @Override public void close() { server.stop(0); }
}
