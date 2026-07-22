package com.bablsoft.accessflow.engine.snowflake;

import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnowflakePrivateKeyParserTest {

    private static String pkcs8Pem(String algorithm) throws NoSuchAlgorithmException {
        var generator = KeyPairGenerator.getInstance(algorithm);
        var encoded = generator.generateKeyPair().getPrivate().getEncoded(); // PKCS#8 DER
        var base64 = Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(encoded);
        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
    }

    @Test
    void parsesRsaPkcs8Pem() throws Exception {
        var key = SnowflakePrivateKeyParser.parse(pkcs8Pem("RSA"));
        assertThat(key.getAlgorithm()).isEqualTo("RSA");
    }

    @Test
    void fallsBackToEcWhenRsaRejectsTheKey() throws Exception {
        var key = SnowflakePrivateKeyParser.parse(pkcs8Pem("EC"));
        assertThat(key.getAlgorithm()).isEqualTo("EC");
    }

    @Test
    void detectsPemHeaders() {
        assertThat(SnowflakePrivateKeyParser.isPrivateKeyPem(
                "-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----")).isTrue();
        assertThat(SnowflakePrivateKeyParser.isPrivateKeyPem("  \n-----BEGIN PRIVATE KEY-----"))
                .isTrue();
        assertThat(SnowflakePrivateKeyParser.isPrivateKeyPem("hunter2")).isFalse();
        assertThat(SnowflakePrivateKeyParser.isPrivateKeyPem(null)).isFalse();

        assertThat(SnowflakePrivateKeyParser.isEncryptedPrivateKeyPem(
                "-----BEGIN ENCRYPTED PRIVATE KEY-----\nabc")).isTrue();
        assertThat(SnowflakePrivateKeyParser.isEncryptedPrivateKeyPem(
                "-----BEGIN PRIVATE KEY-----")).isFalse();
        assertThat(SnowflakePrivateKeyParser.isEncryptedPrivateKeyPem(null)).isFalse();
    }

    @Test
    void encryptedHeaderIsAlsoAPrivateKeyPemPrefixButStaysDistinct() {
        // isPrivateKeyPem must not claim encrypted PEMs — the factory checks encrypted first,
        // and the plain header check is on the distinct "-----BEGIN PRIVATE KEY" prefix.
        assertThat(SnowflakePrivateKeyParser.isPrivateKeyPem(
                "-----BEGIN ENCRYPTED PRIVATE KEY-----")).isFalse();
    }

    @Test
    void garbageBase64IsRejected() {
        assertThatThrownBy(() -> SnowflakePrivateKeyParser.parse(
                "-----BEGIN PRIVATE KEY-----\nnot!!base64@@\n-----END PRIVATE KEY-----"))
                .isInstanceOf(SnowflakeConfigException.class)
                .hasMessage("error.snowflake.invalid_private_key");
    }

    @Test
    void validBase64ThatIsNotAKeyIsRejected() {
        var bogus = Base64.getEncoder().encodeToString("not a key".getBytes());
        assertThatThrownBy(() -> SnowflakePrivateKeyParser.parse(
                "-----BEGIN PRIVATE KEY-----\n" + bogus + "\n-----END PRIVATE KEY-----"))
                .isInstanceOf(SnowflakeConfigException.class)
                .hasMessage("error.snowflake.invalid_private_key");
    }
}
