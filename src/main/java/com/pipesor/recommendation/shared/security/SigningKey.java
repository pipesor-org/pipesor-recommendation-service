package com.pipesor.recommendation.shared.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;

import java.security.interfaces.RSAPublicKey;

/** RS256 signing port. Adapters: a local PEM file or a non-exportable Key Vault key. */
public interface SigningKey {
    RSAPublicKey publicKey();

    /** RSASSA-PKCS1-v1_5 SHA-256 signature over the JWS signing input. */
    byte[] sign(byte[] signingInput);

    default String keyId() {
        try {
            return new RSAKey.Builder(publicKey()).build().computeThumbprint().toString();
        } catch (JOSEException invalid) {
            throw new IllegalStateException("Signing key thumbprint unavailable", invalid);
        }
    }

    default RSAKey publicJwk() {
        return new RSAKey.Builder(publicKey()).keyID(keyId())
                .algorithm(com.nimbusds.jose.JWSAlgorithm.RS256)
                .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE).build();
    }
}
