package com.bablsoft.accessflow.security.internal.config;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

final class RsaKeyLoader {

    private static final String PKCS8_BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String PKCS8_END = "-----END PRIVATE KEY-----";
    private static final String PKCS1_BEGIN = "-----BEGIN RSA PRIVATE KEY-----";
    private static final String PKCS1_END = "-----END RSA PRIVATE KEY-----";

    private RsaKeyLoader() {}

    static RSAPrivateKey loadPrivateKey(String pem) throws GeneralSecurityException {
        var trimmed = pem.trim();
        byte[] decoded;
        if (trimmed.contains(PKCS1_BEGIN)) {
            var body = trimmed.replace(PKCS1_BEGIN, "").replace(PKCS1_END, "").replaceAll("\\s", "");
            decoded = wrapPkcs1InPkcs8(Base64.getDecoder().decode(body));
        } else {
            var body = trimmed.replace(PKCS8_BEGIN, "").replace(PKCS8_END, "").replaceAll("\\s", "");
            decoded = Base64.getDecoder().decode(body);
        }
        var keySpec = new PKCS8EncodedKeySpec(decoded);
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(keySpec);
    }

    static RSAPublicKey derivePublicKey(RSAPrivateKey privateKey) throws GeneralSecurityException {
        var crtKey = (RSAPrivateCrtKey) privateKey;
        var pubKeySpec = new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent());
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(pubKeySpec);
    }

    /**
     * Wraps a PKCS#1 RSAPrivateKey ASN.1 blob in a PKCS#8 PrivateKeyInfo envelope so
     * the JDK's {@link PKCS8EncodedKeySpec} (which only consumes PKCS#8) can parse it.
     * The added header is a constant ASN.1 SEQUENCE of:
     *   INTEGER 0 (version), AlgorithmIdentifier(rsaEncryption, NULL), OCTET STRING (the PKCS#1 bytes).
     */
    private static byte[] wrapPkcs1InPkcs8(byte[] pkcs1) {
        var version = new byte[] {0x02, 0x01, 0x00};
        var algorithmIdentifier = new byte[] {
                0x30, 0x0d,
                0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01,
                0x05, 0x00
        };
        var octetStringHeader = derTagAndLength((byte) 0x04, pkcs1.length);
        var bodyLength = version.length + algorithmIdentifier.length + octetStringHeader.length + pkcs1.length;
        var sequenceHeader = derTagAndLength((byte) 0x30, bodyLength);

        var out = new byte[sequenceHeader.length + bodyLength];
        var off = 0;
        System.arraycopy(sequenceHeader, 0, out, off, sequenceHeader.length);
        off += sequenceHeader.length;
        System.arraycopy(version, 0, out, off, version.length);
        off += version.length;
        System.arraycopy(algorithmIdentifier, 0, out, off, algorithmIdentifier.length);
        off += algorithmIdentifier.length;
        System.arraycopy(octetStringHeader, 0, out, off, octetStringHeader.length);
        off += octetStringHeader.length;
        System.arraycopy(pkcs1, 0, out, off, pkcs1.length);
        return out;
    }

    private static byte[] derTagAndLength(byte tag, int length) {
        if (length < 0x80) {
            return new byte[] {tag, (byte) length};
        }
        if (length <= 0xff) {
            return new byte[] {tag, (byte) 0x81, (byte) length};
        }
        if (length <= 0xffff) {
            return new byte[] {tag, (byte) 0x82, (byte) (length >>> 8), (byte) (length & 0xff)};
        }
        return new byte[] {
                tag, (byte) 0x83,
                (byte) (length >>> 16), (byte) ((length >>> 8) & 0xff), (byte) (length & 0xff)
        };
    }
}
