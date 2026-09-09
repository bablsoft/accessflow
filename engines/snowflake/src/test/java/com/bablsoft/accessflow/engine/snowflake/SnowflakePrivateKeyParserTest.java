package com.bablsoft.accessflow.engine.snowflake;

import net.snowflake.client.jdbc.internal.org.bouncycastle.asn1.ASN1ObjectIdentifier;
import net.snowflake.client.jdbc.internal.org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import net.snowflake.client.jdbc.internal.org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import net.snowflake.client.jdbc.internal.org.bouncycastle.jce.provider.BouncyCastleProvider;
import net.snowflake.client.jdbc.internal.org.bouncycastle.pkcs.jcajce.JcaPKCS8EncryptedPrivateKeyInfoBuilder;
import net.snowflake.client.jdbc.internal.org.bouncycastle.pkcs.jcajce.JcePKCSPBEOutputEncryptorBuilder;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Security;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnowflakePrivateKeyParserTest {

    private static final Provider BC = new BouncyCastleProvider();

    /** The three PBE flavours openssl emits for an encrypted PKCS#8 key. */
    private static final ASN1ObjectIdentifier PBES2_AES_256_CBC = NISTObjectIdentifiers.id_aes256_CBC;
    private static final ASN1ObjectIdentifier PBES2_DES_EDE3_CBC = PKCSObjectIdentifiers.des_EDE3_CBC;
    private static final ASN1ObjectIdentifier PBES1_SHA1_3DES =
            PKCSObjectIdentifiers.pbeWithSHAAnd3_KeyTripleDES_CBC;

    private static KeyPair rsaKeyPair() throws NoSuchAlgorithmException {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    /**
     * Encrypts a key the way {@code openssl pkcs8 -topk8} does, so the fixtures are real
     * EncryptedPrivateKeyInfo structures rather than checked-in PEM files.
     */
    private static String encryptedPkcs8Pem(KeyPair keyPair, ASN1ObjectIdentifier algorithm,
                                            String passphrase) throws Exception {
        var encryptor = new JcePKCSPBEOutputEncryptorBuilder(algorithm)
                .setProvider(BC)
                .build(passphrase.toCharArray());
        var encrypted = new JcaPKCS8EncryptedPrivateKeyInfoBuilder(keyPair.getPrivate())
                .build(encryptor);
        var base64 = Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(encrypted.getEncoded());
        return "-----BEGIN ENCRYPTED PRIVATE KEY-----\n" + base64
                + "\n-----END ENCRYPTED PRIVATE KEY-----\n";
    }

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
    void parsesEncryptedPkcs8WithPbes2AndAes256() throws Exception {
        var pem = encryptedPkcs8Pem(rsaKeyPair(), PBES2_AES_256_CBC, "hunter2");
        assertThat(SnowflakePrivateKeyParser.parseEncrypted(pem, "hunter2").getAlgorithm())
                .isEqualTo("RSA");
    }

    @Test
    void parsesEncryptedPkcs8WithPbes2AndTripleDes() throws Exception {
        // The regression this feature exists for: `openssl pkcs8 -topk8 -v2 des3` is the command
        // Snowflake's own key-pair documentation prints, and SunJCE cannot read it (its PBES2
        // support is AES-only) — which is why the parser goes through Bouncy Castle.
        var pem = encryptedPkcs8Pem(rsaKeyPair(), PBES2_DES_EDE3_CBC, "hunter2");
        assertThat(SnowflakePrivateKeyParser.parseEncrypted(pem, "hunter2").getAlgorithm())
                .isEqualTo("RSA");
    }

    @Test
    void parsesEncryptedPkcs8WithPbes1ShaAndTripleDes() throws Exception {
        var pem = encryptedPkcs8Pem(rsaKeyPair(), PBES1_SHA1_3DES, "hunter2");
        assertThat(SnowflakePrivateKeyParser.parseEncrypted(pem, "hunter2").getAlgorithm())
                .isEqualTo("RSA");
    }

    @Test
    void wrongPassphraseIsRejected() throws Exception {
        var pem = encryptedPkcs8Pem(rsaKeyPair(), PBES2_AES_256_CBC, "hunter2");
        assertThatThrownBy(() -> SnowflakePrivateKeyParser.parseEncrypted(pem, "wrong"))
                .isInstanceOf(SnowflakeConfigException.class)
                .hasMessage("error.snowflake.private_key_passphrase_invalid");
    }

    @Test
    void missingPassphraseIsRejectedDistinctlyFromAWrongOne() throws Exception {
        var pem = encryptedPkcs8Pem(rsaKeyPair(), PBES2_AES_256_CBC, "hunter2");
        for (String absent : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> SnowflakePrivateKeyParser.parseEncrypted(pem, absent))
                    .isInstanceOf(SnowflakeConfigException.class)
                    .hasMessage("error.snowflake.private_key_passphrase_required");
        }
    }

    @Test
    void malformedEncryptedPemIsRejected() {
        assertThatThrownBy(() -> SnowflakePrivateKeyParser.parseEncrypted(
                "-----BEGIN ENCRYPTED PRIVATE KEY-----\nnot-base64!!\n"
                        + "-----END ENCRYPTED PRIVATE KEY-----", "hunter2"))
                .isInstanceOf(SnowflakeConfigException.class)
                .hasMessage("error.snowflake.invalid_private_key");
    }

    @Test
    void headerWithoutAPemBodyIsRejected() {
        assertThatThrownBy(() -> SnowflakePrivateKeyParser.parseEncrypted(
                "-----BEGIN ENCRYPTED PRIVATE KEY-----", "hunter2"))
                .isInstanceOf(SnowflakeConfigException.class)
                .hasMessage("error.snowflake.invalid_private_key");
    }

    @Test
    void parsingNeverInstallsTheRelocatedProviderIntoTheJvm() throws Exception {
        // The plugin runs in an isolated classloader; registering the driver's relocated Bouncy
        // Castle globally would leak it into the host JVM and collide with other plugins.
        var pem = encryptedPkcs8Pem(rsaKeyPair(), PBES2_AES_256_CBC, "hunter2");
        SnowflakePrivateKeyParser.parseEncrypted(pem, "hunter2");
        assertThat(Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)).isNull();
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
