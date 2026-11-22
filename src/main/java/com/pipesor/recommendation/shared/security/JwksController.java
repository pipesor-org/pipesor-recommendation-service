package com.pipesor.recommendation.shared.security;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Publishes this workload's public signing keys (platform identity contract v1, §7). */
@RestController
public final class JwksController {
    public static final String PATH = "/.well-known/jwks.json";
    private final Map<String, Object> keys;

    public JwksController(List<SigningKey> signingKeys) {
        keys = new JWKSet(signingKeys.stream().<JWK>map(SigningKey::publicJwk).toList()).toJSONObject(true);
    }

    @GetMapping(path = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> keys() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic()).body(keys);
    }
}
