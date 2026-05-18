package com.bablsoft.accessflow.security.internal.config;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RsaKeyLoaderTest {

    @Test
    void loadsPkcs8Pem() throws Exception {
        var generated = generateRsaKey();
        var pem = wrapAsPem("PRIVATE KEY", generated.getEncoded());

        var loaded = RsaKeyLoader.loadPrivateKey(pem);

        assertThat(loaded.getModulus()).isEqualTo(generated.getModulus());
        assertThat(loaded.getPrivateExponent()).isEqualTo(generated.getPrivateExponent());
    }

    @Test
    void loadsPkcs1PemAsHelmGeneratesIt() throws Exception {
        // Sprig's `genPrivateKey "rsa"` (used by the chart-managed Secret) emits a
        // PKCS#1 PEM — `-----BEGIN RSA PRIVATE KEY-----` — not the PKCS#8 form that
        // PKCS8EncodedKeySpec expects. The loader must accept both.
        var generated = generateRsaKey();
        var pkcs1Bytes = encodeAsPkcs1(generated);
        var pem = wrapAsPem("RSA PRIVATE KEY", pkcs1Bytes);

        var loaded = RsaKeyLoader.loadPrivateKey(pem);

        assertThat(loaded.getModulus()).isEqualTo(generated.getModulus());
        assertThat(loaded.getPrivateExponent()).isEqualTo(generated.getPrivateExponent());
    }

    @Test
    void rejectsNonPem() {
        assertThatThrownBy(() -> RsaKeyLoader.loadPrivateKey("not-a-pem"))
                .isInstanceOfAny(IllegalArgumentException.class, GeneralSecurityException.class);
    }

    @Test
    void derivePublicKeyMatchesPrivateModulus() throws Exception {
        var generated = generateRsaKey();
        var pem = wrapAsPem("PRIVATE KEY", generated.getEncoded());
        var privateKey = RsaKeyLoader.loadPrivateKey(pem);

        var publicKey = RsaKeyLoader.derivePublicKey(privateKey);

        assertThat(publicKey.getModulus()).isEqualTo(generated.getModulus());
        assertThat(publicKey.getPublicExponent()).isEqualTo(generated.getPublicExponent());
    }

    private static RSAPrivateCrtKey generateRsaKey() throws Exception {
        var keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        return (RSAPrivateCrtKey) keyGen.generateKeyPair().getPrivate();
    }

    private static byte[] encodeAsPkcs1(RSAPrivateCrtKey key) throws Exception {
        var spec = KeyFactory.getInstance("RSA").getKeySpec(key, RSAPrivateCrtKeySpec.class);
        var seq = new ByteArrayOutputStream();
        seq.write(derInteger(BigInteger.ZERO));
        seq.write(derInteger(spec.getModulus()));
        seq.write(derInteger(spec.getPublicExponent()));
        seq.write(derInteger(spec.getPrivateExponent()));
        seq.write(derInteger(spec.getPrimeP()));
        seq.write(derInteger(spec.getPrimeQ()));
        seq.write(derInteger(spec.getPrimeExponentP()));
        seq.write(derInteger(spec.getPrimeExponentQ()));
        seq.write(derInteger(spec.getCrtCoefficient()));
        var body = seq.toByteArray();
        var out = new ByteArrayOutputStream();
        out.write(0x30);
        writeDerLength(out, body.length);
        out.write(body);
        return out.toByteArray();
    }

    private static byte[] derInteger(BigInteger value) {
        var bytes = value.toByteArray();
        var out = new ByteArrayOutputStream();
        out.write(0x02);
        writeDerLength(out, bytes.length);
        out.write(bytes, 0, bytes.length);
        return out.toByteArray();
    }

    private static void writeDerLength(ByteArrayOutputStream out, int length) {
        if (length < 0x80) {
            out.write(length);
        } else if (length <= 0xff) {
            out.write(0x81);
            out.write(length);
        } else if (length <= 0xffff) {
            out.write(0x82);
            out.write((length >>> 8) & 0xff);
            out.write(length & 0xff);
        } else {
            out.write(0x83);
            out.write((length >>> 16) & 0xff);
            out.write((length >>> 8) & 0xff);
            out.write(length & 0xff);
        }
    }

    private static String wrapAsPem(String label, byte[] der) {
        var base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + base64 + "\n-----END " + label + "-----\n";
    }
}
