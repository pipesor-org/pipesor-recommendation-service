package com.pipesor.recommendation.shared.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.EnumSet;

/** Local signing key: an operator-owned PKCS#8 PEM file with mode 0600 (local profile only). */
public final class PemSigningKey implements SigningKey {
    private final RSAPrivateCrtKey privateKey;
    private final RSAPublicKey publicKey;

    public PemSigningKey(Path file) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || !Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS)
                            .equals(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
                    && !Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS)
                            .equals(EnumSet.of(PosixFilePermission.OWNER_READ))) {
                throw new IllegalArgumentException("The signing key must be a regular file with mode 0600 or 0400");
            }
            String pem = Files.readString(file, StandardCharsets.US_ASCII);
            if (!pem.contains("-----BEGIN PRIVATE KEY-----")) {
                throw new IllegalArgumentException("A PKCS#8 RSA private key is required");
            }
            byte[] der = Base64.getMimeDecoder().decode(pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", ""));
            var factory = KeyFactory.getInstance("RSA");
            privateKey = (RSAPrivateCrtKey) factory.generatePrivate(new PKCS8EncodedKeySpec(der));
            if (privateKey.getModulus().bitLength() < 2048) throw new IllegalArgumentException("RSA ≥ 2048 bits is required");
            publicKey = (RSAPublicKey) factory.generatePublic(
                    new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
        } catch (IOException | GeneralSecurityException | ClassCastException invalid) {
            throw new IllegalArgumentException("The local signing key is unavailable", invalid);
        }
    }

    @Override public RSAPublicKey publicKey() { return publicKey; }

    @Override public byte[] sign(byte[] signingInput) {
        try {
            var signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(signingInput);
            return signature.sign();
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Local signing failed", failure);
        }
    }

    @Override public String toString() { return "PemSigningKey[redacted]"; }
}
