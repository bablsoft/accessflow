package com.partqam.accessflow.core.internal.totp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TotpCodecTest {

    private final TotpCodec codec = new TotpCodec();

    @Test
    void newSecretGeneratesNonBlankStringSuitableForBase32() {
        var secret = codec.newSecret();
        assertThat(secret).isNotBlank();
        // DefaultSecretGenerator produces 32 base32 chars by default.
        assertThat(secret).hasSizeGreaterThanOrEqualTo(16);
    }

    @Test
    void buildEnrollmentReturnsOtpauthUrlAndDataUri() {
        var secret = codec.newSecret();
        var enrollment = codec.buildEnrollment(secret, "AccessFlow", "alice@example.com");
        assertThat(enrollment.secret()).isEqualTo(secret);
        assertThat(enrollment.otpauthUrl()).startsWith("otpauth://totp/");
        assertThat(enrollment.otpauthUrl()).contains("secret=" + secret);
        assertThat(enrollment.otpauthUrl()).contains("issuer=AccessFlow");
        assertThat(enrollment.qrDataUri()).startsWith("data:image/png;base64,");
    }

    @Test
    void verifyCodeReturnsFalseForNullInputs() {
        assertThat(codec.verifyCode(null, "123456")).isFalse();
        assertThat(codec.verifyCode("AAAA", null)).isFalse();
    }

    @Test
    void verifyCodeReturnsFalseForBogusCode() {
        var secret = codec.newSecret();
        assertThat(codec.verifyCode(secret, "000000")).isFalse();
    }

    @Test
    void generateRecoveryCodesReturnsTenDistinctCodes() {
        var codes = codec.generateRecoveryCodes();
        assertThat(codes).hasSize(10);
        assertThat(codes).doesNotHaveDuplicates();
        assertThat(codes).allMatch(s -> s != null && !s.isBlank());
    }

    @Test
    void recoveryCodeCountIsTen() {
        assertThat(codec.recoveryCodeCount()).isEqualTo(10);
    }
}
