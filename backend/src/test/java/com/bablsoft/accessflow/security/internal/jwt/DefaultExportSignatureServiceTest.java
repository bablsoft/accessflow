package com.bablsoft.accessflow.security.internal.jwt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class DefaultExportSignatureServiceTest {

    private DefaultExportSignatureService service;

    @BeforeEach
    void setUp() throws Exception {
        var keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        service = new DefaultExportSignatureService(
                (RSAPrivateKey) keyPair.getPrivate(), (RSAPublicKey) keyPair.getPublic());
    }

    @Test
    void signAndVerifyRoundtrips() {
        var content = "compliance report bytes".getBytes(UTF_8);

        var signature = service.sign(content);

        assertThat(signature).isNotBlank();
        assertThat(service.verify(content, signature)).isTrue();
    }

    @Test
    void verifyFailsWhenContentTampered() {
        var content = "original".getBytes(UTF_8);
        var signature = service.sign(content);

        assertThat(service.verify("tampered".getBytes(UTF_8), signature)).isFalse();
    }

    @Test
    void verifyFailsOnMalformedSignature() {
        assertThat(service.verify("x".getBytes(UTF_8), "not-base64-$$$")).isFalse();
    }

    @Test
    void algorithmIsSha256WithRsa() {
        assertThat(service.algorithm()).isEqualTo("SHA256withRSA");
    }

    @Test
    void publicKeyPemParsesBackIntoUsableKey() throws Exception {
        var pem = service.publicKeyPem();

        assertThat(pem).startsWith("-----BEGIN PUBLIC KEY-----").contains("-----END PUBLIC KEY-----");

        var base64 = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        var decoded = Base64.getDecoder().decode(base64);
        var publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decoded));

        assertThat(publicKey.getAlgorithm()).isEqualTo("RSA");
    }
}
