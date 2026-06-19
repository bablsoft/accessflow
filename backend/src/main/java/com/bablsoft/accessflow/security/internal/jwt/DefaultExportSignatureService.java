package com.bablsoft.accessflow.security.internal.jwt;

import com.bablsoft.accessflow.security.api.ExportSignatureService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

/**
 * RSA implementation of {@link ExportSignatureService}, reusing the JWT RS256 key pair (the
 * deployment's identity key) so signed exports require no extra secret. Signs with
 * {@code SHA256withRSA} and exposes the public key as PEM for offline verification.
 */
@Service
@RequiredArgsConstructor
class DefaultExportSignatureService implements ExportSignatureService {

    private static final String ALGORITHM = "SHA256withRSA";

    private final RSAPrivateKey rsaPrivateKey;
    private final RSAPublicKey rsaPublicKey;

    @Override
    public String sign(byte[] content) {
        try {
            var signature = Signature.getInstance(ALGORITHM);
            signature.initSign(rsaPrivateKey);
            signature.update(content);
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to sign export", e);
        }
    }

    @Override
    public String algorithm() {
        return ALGORITHM;
    }

    @Override
    public String publicKeyPem() {
        var base64 = Base64.getMimeEncoder(64, "\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                .encodeToString(rsaPublicKey.getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----\n";
    }

    @Override
    public boolean verify(byte[] content, String signatureBase64) {
        try {
            var signature = Signature.getInstance(ALGORITHM);
            signature.initVerify(rsaPublicKey);
            signature.update(content);
            return signature.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}
