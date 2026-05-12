package com.bablsoft.accessflow.core.internal.totp;

import com.bablsoft.accessflow.core.api.TotpEnrollment;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.exceptions.QrGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.recovery.RecoveryCodeGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import dev.samstevens.totp.util.Utils;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class TotpCodec {

    private static final int RECOVERY_CODE_COUNT = 10;

    private final SecretGenerator secretGenerator = new DefaultSecretGenerator();
    private final QrGenerator qrGenerator = new ZxingPngQrGenerator();
    private final RecoveryCodeGenerator recoveryCodeGenerator = new RecoveryCodeGenerator();
    private final TimeProvider timeProvider = new SystemTimeProvider();
    private final CodeVerifier codeVerifier =
            new DefaultCodeVerifier(new DefaultCodeGenerator(), timeProvider);

    public String newSecret() {
        return secretGenerator.generate();
    }

    public TotpEnrollment buildEnrollment(String secret, String issuer, String accountName) {
        var qrData = new QrData.Builder()
                .label(issuer + ":" + accountName)
                .secret(secret)
                .issuer(issuer)
                .algorithm(HashingAlgorithm.SHA1)
                .digits(6)
                .period(30)
                .build();
        try {
            var pngBytes = qrGenerator.generate(qrData);
            var qrDataUri = Utils.getDataUriForImage(pngBytes, qrGenerator.getImageMimeType());
            return new TotpEnrollment(secret, qrData.getUri(), qrDataUri);
        } catch (QrGenerationException ex) {
            throw new IllegalStateException("Failed to generate TOTP QR code", ex);
        }
    }

    public boolean verifyCode(String secret, String code) {
        if (secret == null || code == null) {
            return false;
        }
        return codeVerifier.isValidCode(secret, code);
    }

    public List<String> generateRecoveryCodes() {
        return Arrays.asList(recoveryCodeGenerator.generateCodes(RECOVERY_CODE_COUNT));
    }

    public int recoveryCodeCount() {
        return RECOVERY_CODE_COUNT;
    }
}
