package com.partqam.accessflow.security.internal.config;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

final class RsaKeyLoader {

    private RsaKeyLoader() {}

    static RSAPrivateKey loadPrivateKey(String pem) throws GeneralSecurityException {
        var stripped = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        var decoded = Base64.getDecoder().decode(stripped);
        var keySpec = new PKCS8EncodedKeySpec(decoded);
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(keySpec);
    }

    static RSAPublicKey derivePublicKey(RSAPrivateKey privateKey) throws GeneralSecurityException {
        var crtKey = (RSAPrivateCrtKey) privateKey;
        var pubKeySpec = new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent());
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(pubKeySpec);
    }
}
