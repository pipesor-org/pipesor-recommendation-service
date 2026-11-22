package com.pipesor.recommendation.shared.security;

import com.azure.core.credential.TokenCredential;
import com.azure.core.util.Context;
import com.azure.security.keyvault.keys.cryptography.CryptographyClient;
import com.azure.security.keyvault.keys.cryptography.CryptographyClientBuilder;
import com.azure.security.keyvault.keys.cryptography.models.SignatureAlgorithm;

import java.security.interfaces.RSAPublicKey;
import java.util.Objects;

/** Azure signing key: a non-exportable Key Vault RSA key used through the workload identity. */
public final class KeyVaultSigningKey implements SigningKey {
    private final CryptographyClient client;
    private final RSAPublicKey publicKey;

    public KeyVaultSigningKey(String keyId, TokenCredential credential) {
        client = new CryptographyClientBuilder().keyIdentifier(Objects.requireNonNull(keyId))
                .credential(Objects.requireNonNull(credential)).buildClient();
        var key = client.getKey().getKey();
        if (key == null || key.getKeyType() == null || !key.getKeyType().toString().startsWith("RSA")) {
            throw new IllegalStateException("A Key Vault RSA signing key is required");
        }
        publicKey = (RSAPublicKey) key.toRsa(false).getPublic();
        if (publicKey.getModulus().bitLength() < 2048) throw new IllegalStateException("RSA ≥ 2048 bits is required");
    }

    @Override public RSAPublicKey publicKey() { return publicKey; }

    @Override public byte[] sign(byte[] signingInput) {
        return client.signData(SignatureAlgorithm.RS256, signingInput, Context.NONE).getSignature();
    }

    @Override public String toString() { return "KeyVaultSigningKey[redacted]"; }
}
