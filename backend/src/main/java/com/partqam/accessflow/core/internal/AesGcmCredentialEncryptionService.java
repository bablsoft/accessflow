package com.partqam.accessflow.core.internal;

import com.partqam.accessflow.core.api.CredentialEncryptionService;
import com.partqam.accessflow.core.internal.config.EncryptionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
class AesGcmCredentialEncryptionService implements CredentialEncryptionService {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH_BYTES = 32;

    private final EncryptionProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();
    private volatile SecretKeySpec keySpec;

    private SecretKeySpec resolveKey() {
        var cached = keySpec;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (keySpec != null) {
                return keySpec;
            }
            var raw = properties.encryptionKey();
            if (raw == null || raw.isBlank()) {
                throw new IllegalStateException(
                        "accessflow.encryption-key is required (64 hex characters = 32 bytes)");
            }
            byte[] key;
            try {
                key = HexFormat.of().parseHex(raw.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        "accessflow.encryption-key must be a hex string", e);
            }
            if (key.length != KEY_LENGTH_BYTES) {
                throw new IllegalStateException(
                        "accessflow.encryption-key must decode to " + KEY_LENGTH_BYTES
                                + " bytes; got " + key.length);
            }
            keySpec = new SecretKeySpec(key, "AES");
            return keySpec;
        }
    }

    @Override
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("plaintext must not be null");
        }
        try {
            var iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            var cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, resolveKey(), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            var ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            var combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt credential", e);
        }
    }

    @Override
    public String decrypt(String ciphertext) {
        if (ciphertext == null) {
            throw new IllegalArgumentException("ciphertext must not be null");
        }
        byte[] combined;
        try {
            combined = Base64.getDecoder().decode(ciphertext);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Ciphertext is not valid base64", e);
        }
        if (combined.length <= IV_LENGTH) {
            throw new IllegalStateException("Ciphertext too short");
        }
        try {
            var iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            var encrypted = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, encrypted, 0, encrypted.length);
            var cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, resolveKey(), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            var plain = cipher.doFinal(encrypted);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to decrypt credential", e);
        }
    }
}
