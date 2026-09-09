package com.bablsoft.accessflow.engine.snowflake;

import net.snowflake.client.jdbc.internal.org.bouncycastle.jce.provider.BouncyCastleProvider;
import net.snowflake.client.jdbc.internal.org.bouncycastle.openssl.PEMException;
import net.snowflake.client.jdbc.internal.org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import net.snowflake.client.jdbc.internal.org.bouncycastle.pkcs.PKCSException;
import net.snowflake.client.jdbc.internal.org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import net.snowflake.client.jdbc.internal.org.bouncycastle.pkcs.jcajce.JcePKCSPBEInputDecryptorProviderBuilder;
import net.snowflake.client.jdbc.internal.org.bouncycastle.util.encoders.DecoderException;
import net.snowflake.client.jdbc.internal.org.bouncycastle.util.io.pem.PemReader;

import java.io.IOException;
import java.io.StringReader;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Parses a PKCS#8 private-key PEM into a {@link PrivateKey} for Snowflake key-pair authentication —
 * the object-valued {@code privateKey} connection property. RSA is tried first (Snowflake's
 * documented key type); EC is a fallback.
 *
 * <p>Both PEM forms are supported: the plain {@code -----BEGIN PRIVATE KEY-----} form via
 * {@link #parse(String)}, and the passphrase-protected {@code -----BEGIN ENCRYPTED PRIVATE KEY-----}
 * form via {@link #parseEncrypted(String, String)} (issue #632), whose passphrase is stored
 * separately in the datasource's {@code private_key_passphrase_encrypted} column. Malformed input
 * raises {@link SnowflakeConfigException} ({@code error.snowflake.invalid_private_key}).
 *
 * <p>The encrypted form is opened with <strong>Bouncy Castle</strong> rather than
 * {@code javax.crypto.EncryptedPrivateKeyInfo}, deliberately: SunJCE's PBES2 support is AES-only, so
 * the JDK cannot read a key produced by the very command Snowflake's own documentation prints
 * ({@code openssl pkcs8 -topk8 -v2 des3 ...}), which is PBES2 with DES-EDE3-CBC. Bouncy Castle is
 * not a new dependency: the Snowflake JDBC driver already bundles it, relocated under
 * {@code net.snowflake.client.jdbc.internal.org.bouncycastle}, and the plugin shades that driver
 * whole — so this costs no jar growth. The coupling is to the driver's <em>internal</em> relocated
 * package: a driver upgrade that changes the relocation prefix breaks this file at compile time,
 * which is the intended failure mode.
 *
 * <p>The provider is a local instance passed explicitly to each builder, never registered through
 * {@code Security.addProvider(...)} — the plugin lives in an isolated classloader and must not
 * install a relocated provider into the host JVM.
 */
final class SnowflakePrivateKeyParser {

    private static final String ENCRYPTED_HEADER = "-----BEGIN ENCRYPTED PRIVATE KEY";
    private static final String HEADER = "-----BEGIN PRIVATE KEY";

    private static final Provider BOUNCY_CASTLE = new BouncyCastleProvider();

    private SnowflakePrivateKeyParser() {
    }

    /** Whether the credential is a passphrase-protected PKCS#8 PEM. */
    static boolean isEncryptedPrivateKeyPem(String credential) {
        return credential != null && credential.strip().startsWith(ENCRYPTED_HEADER);
    }

    /** Whether the credential is an unencrypted PKCS#8 private-key PEM. */
    static boolean isPrivateKeyPem(String credential) {
        return credential != null && credential.strip().startsWith(HEADER);
    }

    static PrivateKey parse(String pem) {
        var base64 = pem.strip()
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der;
        try {
            der = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new SnowflakeConfigException("error.snowflake.invalid_private_key");
        }
        var spec = new PKCS8EncodedKeySpec(der);
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (GeneralSecurityException rsaFailure) {
            try {
                return KeyFactory.getInstance("EC").generatePrivate(spec);
            } catch (GeneralSecurityException ecFailure) {
                throw new SnowflakeConfigException("error.snowflake.invalid_private_key");
            }
        }
    }

    /**
     * Opens a passphrase-protected PKCS#8 PEM. A missing passphrase and a wrong one are reported
     * separately, because they need different fixes from the operator.
     */
    static PrivateKey parseEncrypted(String pem, String passphrase) {
        if (passphrase == null || passphrase.isBlank()) {
            throw new SnowflakeConfigException("error.snowflake.private_key_passphrase_required");
        }
        byte[] der;
        try (var reader = new PemReader(new StringReader(pem.strip()))) {
            var pemObject = reader.readPemObject();
            if (pemObject == null) {
                throw new SnowflakeConfigException("error.snowflake.invalid_private_key");
            }
            der = pemObject.getContent();
        } catch (IOException | DecoderException e) {
            // DecoderException is Bouncy Castle's unchecked signal for a corrupt base64 body; it
            // must surface as the same localized error as any other malformed PEM.
            throw new SnowflakeConfigException("error.snowflake.invalid_private_key");
        }
        PKCS8EncryptedPrivateKeyInfo encrypted;
        try {
            encrypted = new PKCS8EncryptedPrivateKeyInfo(der);
        } catch (IOException | IllegalArgumentException e) {
            throw new SnowflakeConfigException("error.snowflake.invalid_private_key");
        }
        try {
            var decryptor = new JcePKCSPBEInputDecryptorProviderBuilder()
                    .setProvider(BOUNCY_CASTLE)
                    .build(passphrase.toCharArray());
            return new JcaPEMKeyConverter()
                    .setProvider(BOUNCY_CASTLE)
                    .getPrivateKey(encrypted.decryptPrivateKeyInfo(decryptor));
        } catch (PKCSException | PEMException e) {
            // A wrong passphrase and an unsupported PBE algorithm both surface here; the message
            // covers both causes.
            throw new SnowflakeConfigException("error.snowflake.private_key_passphrase_invalid");
        }
    }
}
