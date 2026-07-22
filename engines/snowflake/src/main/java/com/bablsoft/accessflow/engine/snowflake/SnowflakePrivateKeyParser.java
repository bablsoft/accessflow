package com.bablsoft.accessflow.engine.snowflake;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Parses an unencrypted PKCS#8 private-key PEM (the {@code -----BEGIN PRIVATE KEY-----} form) into
 * a {@link PrivateKey} for Snowflake key-pair authentication — the object-valued {@code privateKey}
 * connection property. RSA is tried first (Snowflake's documented key type); EC is a fallback.
 * Passphrase-protected keys ({@code -----BEGIN ENCRYPTED PRIVATE KEY-----}) are detected but
 * deliberately unsupported — AccessFlow already encrypts the stored credential at rest, so
 * operators should store the decrypted PKCS#8 form. Malformed input raises
 * {@link SnowflakeConfigException} ({@code error.snowflake.invalid_private_key}).
 */
final class SnowflakePrivateKeyParser {

    private static final String ENCRYPTED_HEADER = "-----BEGIN ENCRYPTED PRIVATE KEY";
    private static final String HEADER = "-----BEGIN PRIVATE KEY";

    private SnowflakePrivateKeyParser() {
    }

    /** Whether the credential is a passphrase-protected PKCS#8 PEM (unsupported). */
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
}
