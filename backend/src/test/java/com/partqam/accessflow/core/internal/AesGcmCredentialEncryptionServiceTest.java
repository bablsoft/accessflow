package com.partqam.accessflow.core.internal;

import com.partqam.accessflow.core.internal.config.EncryptionProperties;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmCredentialEncryptionServiceTest {

    private static final String VALID_HEX_KEY =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void encryptThenDecryptRoundTrips() {
        var service = service(VALID_HEX_KEY);
        var ciphertext = service.encrypt("super-secret-password");

        assertThat(service.decrypt(ciphertext)).isEqualTo("super-secret-password");
    }

    @Test
    void encryptProducesDistinctCiphertextsForSamePlaintext() {
        var service = service(VALID_HEX_KEY);

        var first = service.encrypt("same");
        var second = service.encrypt("same");

        assertThat(first).isNotEqualTo(second);
        assertThat(service.decrypt(first)).isEqualTo("same");
        assertThat(service.decrypt(second)).isEqualTo("same");
    }

    @Test
    void decryptOfTamperedCiphertextThrows() {
        var service = service(VALID_HEX_KEY);
        var ciphertext = service.encrypt("payload");
        var bytes = Base64.getDecoder().decode(ciphertext);
        bytes[bytes.length - 1] ^= 0x01;
        var tampered = Base64.getEncoder().encodeToString(bytes);

        assertThatThrownBy(() -> service.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decryptOfNonBase64Throws() {
        var service = service(VALID_HEX_KEY);

        assertThatThrownBy(() -> service.decrypt("not-base64!"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decryptOfTooShortInputThrows() {
        var service = service(VALID_HEX_KEY);
        var tooShort = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4});

        assertThatThrownBy(() -> service.decrypt(tooShort))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void encryptWithMissingKeyThrows() {
        var service = service(null);

        assertThatThrownBy(() -> service.encrypt("anything"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("encryption-key");
    }

    @Test
    void encryptWithBlankKeyThrows() {
        var service = service("");

        assertThatThrownBy(() -> service.encrypt("anything"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("encryption-key");
    }

    @Test
    void encryptWithWrongLengthKeyThrows() {
        var service = service("abcd");

        assertThatThrownBy(() -> service.encrypt("anything"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void encryptWithNonHexKeyThrows() {
        var service = service("zz".repeat(32));

        assertThatThrownBy(() -> service.encrypt("anything"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hex");
    }

    @Test
    void encryptOfNullPlaintextThrows() {
        var service = service(VALID_HEX_KEY);

        assertThatThrownBy(() -> service.encrypt(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decryptOfNullCiphertextThrows() {
        var service = service(VALID_HEX_KEY);

        assertThatThrownBy(() -> service.decrypt(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private AesGcmCredentialEncryptionService service(String hexKey) {
        return new AesGcmCredentialEncryptionService(new EncryptionProperties(hexKey));
    }
}
