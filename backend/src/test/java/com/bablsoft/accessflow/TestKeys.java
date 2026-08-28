package com.bablsoft.accessflow;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;

/**
 * JVM-wide test credentials shared by every integration test.
 *
 * <p>The RSA keypair is generated <b>once per JVM</b> in a static initialiser rather than per test
 * class. Generating it inline in a per-class {@code @DynamicPropertySource} — as ~123 classes used
 * to — cost 123 keygens and, far worse, gave every class a distinct Spring test-context cache key
 * (see {@link TestcontainersConfig} for the full explanation).
 *
 * <p>Both values are throwaway test material with no production counterpart. The PEM is generated
 * at runtime so no key material is ever committed; the encryption key is the all-zeros-ish hex
 * constant the suite has always used.
 */
public final class TestKeys {

    /** 64 hex chars, as required by {@code AesGcmCredentialEncryptionService.resolveKey}. */
    public static final String ENCRYPTION_KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    /** PKCS#8 PEM for a freshly generated RSA-2048 key. {@code RsaKeyLoader} needs a CRT key. */
    public static final String JWT_PRIVATE_KEY_PEM = generatePkcs8Pem();

    private TestKeys() {}

    private static String generatePkcs8Pem() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var privateKey = (RSAPrivateCrtKey) generator.generateKeyPair().getPrivate();
            return "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(privateKey.getEncoded())
                    + "\n-----END PRIVATE KEY-----";
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Failed to generate the shared test RSA keypair", e);
        }
    }
}
