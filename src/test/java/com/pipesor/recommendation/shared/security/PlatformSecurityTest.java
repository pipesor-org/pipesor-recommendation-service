package com.pipesor.recommendation.shared.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformSecurityTest {
    private static final UUID TENANT = UUID.fromString("24c96be0-50f3-4064-b5e4-643088141ae8");
    private static final KeyPair NLP = rsa();
    private static final KeyPair OTHER = rsa();
    private static final KeyPair SESSION = rsa();

    @Test
    void identifiersMatchTheContractTable() {
        assertThat(PlatformIds.workload("nlp")).hasToString("7c3b75a2-74cf-59a0-babf-1c0415d48762");
        assertThat(PlatformIds.workload("correlation")).hasToString("e790fc63-c636-5471-b10f-8dd0b9733fe5");
        assertThat(PlatformIds.workload("log-event")).hasToString("b0b9b288-0e8f-566e-985e-4d2e685dd827");
        assertThat(PlatformIds.DASHBOARD_CLIENT).hasToString("777ec465-1e81-5415-8dd4-c34c64d038e9");
        assertThat(PlatformIds.human(87343021)).hasToString("c6419aef-19d8-5131-bc13-8e9e6e43b727");
    }

    @Test
    void transportAllowsPlainHttpOnlyToLoopbackUnderTheLocalProfile() {
        var local = TransportPolicy.forProfiles("local");
        var azure = TransportPolicy.forProfiles("azure");
        assertThat(local.require(URI.create("http://127.0.0.1:8084/x"))).isNotNull();
        assertThat(azure.require(URI.create("https://nlp.internal.example/x"))).isNotNull();
        assertThatThrownBy(() -> azure.require(URI.create("http://127.0.0.1:8084"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> local.require(URI.create("http://10.0.0.4"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> local.require(URI.create("https://user:pw@host/"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(local.resolve(URI.create("http://127.0.0.1:8082/"), "/internal/v1/x"))
                .hasToString("http://127.0.0.1:8082/internal/v1/x");
    }

    @Test
    void acceptsOnlyTheTrustedCallerWithItsRoleForThisAudience() {
        var decoder = decoder();
        var nlp = new WorkloadTokens("nlp", TENANT, key(NLP), Clock.systemUTC());
        var jwt = decoder.decode(nlp.issue("correlation", "Nlp.ProcessedEvidence.Send").value());
        assertThat(jwt.getClaimAsString("oid")).isEqualTo(PlatformIds.workload("nlp").toString());

        var converted = PlatformAuthorities.converter().convert(jwt);
        assertThat(converted.getAuthorities()).extracting(Object::toString)
                .containsExactlyInAnyOrder("workload:nlp", "role:Nlp.ProcessedEvidence.Send");

        assertThatThrownBy(() -> decoder.decode(nlp.issue("correlation", "Rag.Query").value())).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(nlp.issue("pipeline", "Nlp.ProcessedEvidence.Send").value()))
                .isInstanceOf(JwtException.class);
        var impostor = new WorkloadTokens("nlp", TENANT, key(OTHER), Clock.systemUTC());
        assertThatThrownBy(() -> decoder.decode(impostor.issue("correlation", "Nlp.ProcessedEvidence.Send").value()))
                .isInstanceOf(JwtException.class);
        var untrusted = new WorkloadTokens("rag", TENANT, key(NLP), Clock.systemUTC());
        assertThatThrownBy(() -> decoder.decode(untrusted.issue("correlation", "Nlp.ProcessedEvidence.Send").value()))
                .isInstanceOf(JwtException.class);
        var otherTenant = new WorkloadTokens("nlp", UUID.randomUUID(), key(NLP), Clock.systemUTC());
        assertThatThrownBy(() -> decoder.decode(otherTenant.issue("correlation", "Nlp.ProcessedEvidence.Send").value()))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void reusesWorkloadTokensUntilShortlyBeforeExpiry() {
        var tokens = new WorkloadTokens("nlp", TENANT, key(NLP), Clock.systemUTC());
        var first = tokens.issue("correlation", "Nlp.ProcessedEvidence.Send");
        assertThat(tokens.issue("correlation", "Nlp.ProcessedEvidence.Send")).isSameAs(first);
        assertThat(Duration.between(Instant.now(), first.expiresAt())).isLessThanOrEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void rejectsWorkloadTokensWithScopesOrExcessiveLifetime() throws Exception {
        var decoder = decoder();
        String subject = PlatformIds.workload("nlp").toString();
        var base = workloadClaims(subject);
        assertThat(decoder.decode(sign(NLP, base.build()))).isNotNull();
        assertThatThrownBy(() -> decoder.decode(sign(NLP, workloadClaims(subject).claim("scp", "X").build())))
                .isInstanceOf(JwtException.class);
        Instant now = Instant.now();
        assertThatThrownBy(() -> decoder.decode(sign(NLP, workloadClaims(subject)
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(30)))).build()))).isInstanceOf(JwtException.class);
    }

    @Test
    void acceptsSessionsBoundToTheDashboardAndAGitHubUser() throws Exception {
        var decoder = decoder();
        String human = PlatformIds.human(87343021).toString();
        var jwt = decoder.decode(sign(SESSION, sessionClaims(human, "87343021").build()));
        assertThat(PlatformAuthorities.converter().convert(jwt).getAuthorities()).extracting(Object::toString)
                .contains("session", "SCOPE_Diagnosis.Read");
        assertThatThrownBy(() -> decoder.decode(sign(SESSION, sessionClaims(human, "1").build())))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(sign(SESSION, sessionClaims(human, "87343021").claim("roles", List.of("x")).build())))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(sign(NLP, sessionClaims(human, "87343021").build())))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void localPemKeysMustBeOwnerOnly(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("nlp.pem");
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(NLP.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n";
        Files.writeString(file, pem);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(() -> new PemSigningKey(file)).isInstanceOf(IllegalArgumentException.class);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        var key = new PemSigningKey(file);
        assertThat(key.publicKey()).isEqualTo(NLP.getPublic());
        assertThat(key.keyId()).isEqualTo(key(NLP).keyId());
    }

    private static PlatformJwtDecoder decoder() {
        var settings = new PlatformSecurityProperties("correlation", TENANT, Path.of("/unused"), null,
                new PlatformSecurityProperties.Security(PlatformIds.audience("correlation"), null,
                        List.of(new PlatformSecurityProperties.TrustedWorkload("nlp",
                                URI.create("http://127.0.0.1:8084/.well-known/jwks.json"), Set.of("Nlp.ProcessedEvidence.Send"))),
                        new PlatformSecurityProperties.Session(URI.create("http://127.0.0.1:8081/.well-known/jwks.json"))));
        Map<String, KeyPair> keys = Map.of("8084", NLP, "8081", SESSION);
        return new PlatformJwtDecoder(settings, TransportPolicy.forProfiles("local"), uri ->
                NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.get(String.valueOf(uri.getPort())).getPublic()).build());
    }

    private static JWTClaimsSet.Builder workloadClaims(String subject) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder().issuer("urn:pipesor:workload:nlp").audience("urn:pipesor:api:correlation")
                .subject(subject).claim("oid", subject).claim("azp", subject).claim("tid", TENANT.toString())
                .claim("roles", List.of("Nlp.ProcessedEvidence.Send")).claim("idtyp", "app").claim("ver", "2.0")
                .issueTime(Date.from(now)).notBeforeTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(120)));
    }

    private static JWTClaimsSet.Builder sessionClaims(String human, String githubUser) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder().issuer("urn:pipesor:session")
                .audience(List.of("urn:pipesor:api:pipeline", "urn:pipesor:api:correlation"))
                .subject(human).claim("oid", human).claim("azp", PlatformIds.DASHBOARD_CLIENT.toString())
                .claim("tid", TENANT.toString()).claim("scp", "GitHub.Self Diagnosis.Read").claim("github_user_id", githubUser)
                .claim("ver", "2.0").issueTime(Date.from(now)).notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600)));
    }

    private static String sign(KeyPair pair, JWTClaimsSet claims) throws Exception {
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner(pair.getPrivate()));
        return jwt.serialize();
    }

    private static SigningKey key(KeyPair pair) {
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
}
