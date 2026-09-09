package com.bablsoft.accessflow.engine.snowflake;

import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.SslMode;
import net.snowflake.client.jdbc.internal.org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import net.snowflake.client.jdbc.internal.org.bouncycastle.jce.provider.BouncyCastleProvider;
import net.snowflake.client.jdbc.internal.org.bouncycastle.pkcs.jcajce.JcaPKCS8EncryptedPrivateKeyInfoBuilder;
import net.snowflake.client.jdbc.internal.org.bouncycastle.pkcs.jcajce.JcePKCSPBEOutputEncryptorBuilder;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnowflakeConnectionFactoryTest {

    private final SnowflakeEngineSettings settings = SnowflakeEngineSettings.from(Map.of());
    private final SnowflakeConnectionFactory factory =
            new SnowflakeConnectionFactory(ciphertext -> ciphertext.replace("enc:", ""), settings);

    private static DatasourceConnectionDescriptor descriptor(String host, String database,
                                                             String username, String password,
                                                             String urlOverride) {        // that predate the SNOWFLAKE enum value.
        return new DatasourceConnectionDescriptor(UUID.randomUUID(), UUID.randomUUID(),
                DbType.SNOWFLAKE, host, 443, database, username, password, SslMode.REQUIRE,
                1, 1000, false, null, false, null, "snowflake", urlOverride,
                null, null, null, true);
    }

    /** Descriptor carrying an encrypted key passphrase (the canonical post-#632 shape). */
    private static DatasourceConnectionDescriptor descriptorWithPassphrase(
            String password, String passphraseEncrypted) {
        return new DatasourceConnectionDescriptor(UUID.randomUUID(), UUID.randomUUID(),
                DbType.SNOWFLAKE, "h", 443, "db", "svc", password, SslMode.REQUIRE,
                1, 1000, false, null, false, null, "snowflake", null,
                List.of(), true, null, null, false, null, passphraseEncrypted);
    }

    private static String encryptedPem(String passphrase) throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var encryptor = new JcePKCSPBEOutputEncryptorBuilder(PKCSObjectIdentifiers.des_EDE3_CBC)
                .setProvider(new BouncyCastleProvider())
                .build(passphrase.toCharArray());
        var encrypted = new JcaPKCS8EncryptedPrivateKeyInfoBuilder(
                generator.generateKeyPair().getPrivate()).build(encryptor);
        return "-----BEGIN ENCRYPTED PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(encrypted.getEncoded())
                + "\n-----END ENCRYPTED PRIVATE KEY-----\n";
    }

    @Test
    void buildsUrlFromHostWhenNoOverride() {
        var descriptor = descriptor("myorg-acct.snowflakecomputing.com", "ANALYTICS",
                "svc", "enc:secret", null);
        assertThat(factory.url(descriptor))
                .isEqualTo("jdbc:snowflake://myorg-acct.snowflakecomputing.com");
    }

    @Test
    void usesUrlOverrideVerbatim() {
        var override = "jdbc:snowflake://acct.snowflakecomputing.com/?warehouse=WH&role=R&schema=S";
        var descriptor = descriptor("ignored-host", "ANALYTICS", "svc", "enc:secret", override);
        assertThat(factory.url(descriptor)).isEqualTo(override);
    }

    @Test
    void trimsUrlOverrideWhitespace() {
        var descriptor = descriptor("h", "db", "svc", "enc:secret",
                "  jdbc:snowflake://acct.snowflakecomputing.com  ");
        assertThat(factory.url(descriptor))
                .isEqualTo("jdbc:snowflake://acct.snowflakecomputing.com");
    }

    @Test
    void rejectsNonSnowflakeUrlOverride() {
        var descriptor = descriptor("h", "db", "svc", "enc:secret", "jdbc:postgresql://evil/db");
        assertThatThrownBy(() -> factory.url(descriptor))
                .isInstanceOf(SnowflakeConfigException.class)
                .hasMessage("error.snowflake.invalid_url_override");
    }

    @Test
    void assemblesUserDbTimeoutAndPasswordProperties() {
        var descriptor = descriptor("h", " ANALYTICS ", " svc ", "enc:hunter2", null);
        var properties = factory.connectionProperties(descriptor);
        assertThat(properties.get("db")).isEqualTo("ANALYTICS");
        assertThat(properties.get("user")).isEqualTo("svc");
        assertThat(properties.get("password")).isEqualTo("hunter2");
        assertThat(properties.get("loginTimeout")).isEqualTo("30");
        assertThat(properties.get("networkTimeout")).isEqualTo("60000");
        assertThat(properties.containsKey("privateKey")).isFalse();
    }

    @Test
    void omitsBlankDbAndUser() {
        var descriptor = descriptor("h", " ", null, "enc:pw", null);
        var properties = factory.connectionProperties(descriptor);
        assertThat(properties.containsKey("db")).isFalse();
        assertThat(properties.containsKey("user")).isFalse();
    }

    @Test
    void honorsConfiguredTimeouts() {
        var tuned = new SnowflakeConnectionFactory(ciphertext -> "pw",
                SnowflakeEngineSettings.from(Map.of(
                        "login-timeout", "PT5S", "network-timeout", "PT90S")));
        var properties = tuned.connectionProperties(descriptor("h", "db", "u", "x", null));
        assertThat(properties.get("loginTimeout")).isEqualTo("5");
        assertThat(properties.get("networkTimeout")).isEqualTo("90000");
    }

    @Test
    void privateKeyPemCredentialBecomesObjectValuedPrivateKeyProperty() throws Exception {
        var encoded = KeyPairGenerator.getInstance("RSA").generateKeyPair()
                .getPrivate().getEncoded();
        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(encoded)
                + "\n-----END PRIVATE KEY-----\n";
        var descriptor = descriptor("h", "db", "svc", "enc:" + pem, null);
        var properties = factory.connectionProperties(descriptor);
        assertThat(properties.get("privateKey")).isInstanceOf(PrivateKey.class);
        assertThat(properties.containsKey("password")).isFalse();
    }

    @Test
    void encryptedPrivateKeyPemIsOpenedWithTheStoredPassphrase() throws Exception {
        // The stored passphrase carries the fake decryptor's "enc:" prefix, so this only passes
        // because the factory runs it through the CredentialDecryptor like the credential itself.
        var descriptor = descriptorWithPassphrase(
                "enc:" + encryptedPem("hunter2"), "enc:hunter2");
        var properties = factory.connectionProperties(descriptor);
        assertThat(properties.get("privateKey")).isInstanceOf(PrivateKey.class);
        assertThat(properties.containsKey("password")).isFalse();
    }

    @Test
    void wrongPassphraseIsReportedAsSuch() throws Exception {
        var descriptor = descriptorWithPassphrase(
                "enc:" + encryptedPem("hunter2"), "enc:wrong");
        assertThatThrownBy(() -> factory.connectionProperties(descriptor))
                .isInstanceOf(SnowflakeConfigException.class)
                .hasMessage("error.snowflake.private_key_passphrase_invalid");
    }

    @Test
    void encryptedPrivateKeyPemWithoutAPassphraseIsRejected() throws Exception {
        for (String absent : new String[] {null, "", "   "}) {
            var descriptor = descriptorWithPassphrase("enc:" + encryptedPem("hunter2"), absent);
            assertThatThrownBy(() -> factory.connectionProperties(descriptor))
                    .isInstanceOf(SnowflakeConfigException.class)
                    .hasMessage("error.snowflake.private_key_passphrase_required");
        }
    }

    @Test
    void passwordCredentialIgnoresTheStoredPassphrase() {
        var descriptor = descriptorWithPassphrase("enc:secret", "enc:hunter2");
        var properties = factory.connectionProperties(descriptor);
        assertThat(properties.get("password")).isEqualTo("secret");
        assertThat(properties.containsKey("privateKey")).isFalse();
    }

    @Test
    void malformedPrivateKeyPemIsRejected() {
        var descriptor = descriptor("h", "db", "svc",
                "enc:-----BEGIN PRIVATE KEY-----\n@@@\n-----END PRIVATE KEY-----", null);
        assertThatThrownBy(() -> factory.connectionProperties(descriptor))
                .isInstanceOf(SnowflakeConfigException.class)
                .hasMessage("error.snowflake.invalid_private_key");
    }
}
