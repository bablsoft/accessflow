package com.bablsoft.accessflow.notifications.internal.push;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validates the RFC 8291 ({@code aes128gcm}) and RFC 8292 (VAPID) primitives by round-tripping
 * them against an independent receiver/verifier implemented here with pure JDK crypto — proving a
 * real browser push service (encrypting) and Mozilla/FCM (verifying the JWT) would interoperate.
 */
class WebPushCryptoTest {

    @Test
    void encodeDecodePublicKeyRoundTrips() {
        var key = (ECPublicKey) WebPushCrypto.generateVapidKeyPair().getPublic();
        var encoded = WebPushCrypto.encodePublicKey(key);

        assertThat(encoded).hasSize(65);
        assertThat(encoded[0]).isEqualTo((byte) 0x04);
        var decoded = WebPushCrypto.decodePublicKey(encoded);
        assertThat(decoded.getW()).isEqualTo(key.getW());
    }

    @Test
    void encodeDecodePrivateKeyRoundTrips() {
        var key = (ECPrivateKey) WebPushCrypto.generateVapidKeyPair().getPrivate();
        var encoded = WebPushCrypto.encodePrivateKey(key);

        assertThat(encoded).hasSize(32);
        var decoded = WebPushCrypto.decodePrivateKey(encoded);
        assertThat(decoded.getS()).isEqualTo(key.getS());
    }

    @Test
    void decodePublicKeyRejectsMalformedPoint() {
        assertThatThrownBy(() -> WebPushCrypto.decodePublicKey(new byte[]{0x01, 0x02}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void encryptedMessageDecryptsBackToPlaintext() throws Exception {
        var subscriber = WebPushCrypto.generateVapidKeyPair();
        var uaPublic = WebPushCrypto.encodePublicKey((ECPublicKey) subscriber.getPublic());
        var authSecret = new byte[16];
        new java.security.SecureRandom().nextBytes(authSecret);
        var plaintext = "{\"title\":\"Review request\"}".getBytes(StandardCharsets.UTF_8);

        var body = WebPushCrypto.encrypt(plaintext, uaPublic, authSecret);

        // Header is salt(16) || rs(4) || idlen(1) || as_public(65), then the AES-GCM ciphertext.
        assertThat(body.length).isGreaterThan(16 + 4 + 1 + 65 + 16);
        assertThat(body[20]).isEqualTo((byte) 65);
        var decrypted = decryptAsSubscriber(body, (ECPrivateKey) subscriber.getPrivate(), uaPublic,
                authSecret);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void encryptUsesAFreshSaltAndEphemeralKeyEachCall() {
        var subscriber = WebPushCrypto.generateVapidKeyPair();
        var uaPublic = WebPushCrypto.encodePublicKey((ECPublicKey) subscriber.getPublic());
        var auth = new byte[16];

        var first = WebPushCrypto.encrypt(new byte[]{1}, uaPublic, auth);
        var second = WebPushCrypto.encrypt(new byte[]{1}, uaPublic, auth);

        assertThat(Arrays.copyOf(first, 16)).isNotEqualTo(Arrays.copyOf(second, 16));
    }

    @Test
    void vapidAuthorizationProducesAVerifiableEs256Jwt() throws Exception {
        var keyPair = WebPushCrypto.generateVapidKeyPair();
        var publicKey = WebPushCrypto.encodePublicKey((ECPublicKey) keyPair.getPublic());

        var header = WebPushCrypto.vapidAuthorization("https://fcm.googleapis.com/fcm/send/abc",
                (ECPrivateKey) keyPair.getPrivate(), publicKey, "mailto:ops@acme.test",
                Instant.parse("2026-06-22T00:00:00Z"));

        assertThat(header).startsWith("vapid t=");
        var token = header.substring("vapid t=".length(), header.indexOf(", k="));
        var k = header.substring(header.indexOf(", k=") + 4);
        assertThat(k).isEqualTo(Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey));

        var parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        var headerJson = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        var payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertThat(headerJson).contains("\"alg\":\"ES256\"");
        assertThat(payloadJson).contains("\"aud\":\"https://fcm.googleapis.com\"");
        assertThat(payloadJson).contains("\"sub\":\"mailto:ops@acme.test\"");

        // The signature must verify against the VAPID public key (jose R||S -> DER first).
        var verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        var jose = Base64.getUrlDecoder().decode(parts[2]);
        assertThat(verifier.verify(joseToDer(jose))).isTrue();
    }

    @Test
    void originStripsPathAndKeepsNonDefaultPort() {
        assertThat(WebPushCrypto.origin("https://updates.push.services.mozilla.com/wpush/v2/xyz"))
                .isEqualTo("https://updates.push.services.mozilla.com");
        assertThat(WebPushCrypto.origin("http://localhost:8090/push/abc"))
                .isEqualTo("http://localhost:8090");
    }

    // --- independent receiver / verifier helpers (pure JDK) -------------------------------------

    private static byte[] decryptAsSubscriber(byte[] body, ECPrivateKey uaPrivate, byte[] uaPublic,
                                              byte[] authSecret) throws Exception {
        var salt = Arrays.copyOfRange(body, 0, 16);
        int idlen = body[20] & 0xff;
        var asPublic = Arrays.copyOfRange(body, 21, 21 + idlen);
        var ciphertext = Arrays.copyOfRange(body, 21 + idlen, body.length);

        var agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(uaPrivate);
        agreement.doPhase(WebPushCrypto.decodePublicKey(asPublic), true);
        var sharedSecret = agreement.generateSecret();

        var prkKey = hmac(authSecret, sharedSecret);
        var keyInfo = concat("WebPush: info".getBytes(StandardCharsets.US_ASCII), new byte[]{0x00},
                uaPublic, asPublic);
        var ikm = Arrays.copyOf(hmac(prkKey, concat(keyInfo, new byte[]{0x01})), 32);
        var prk = hmac(salt, ikm);
        var cek = Arrays.copyOf(hmac(prk, concat(
                "Content-Encoding: aes128gcm".getBytes(StandardCharsets.US_ASCII),
                new byte[]{0x00, 0x01})), 16);
        var nonce = Arrays.copyOf(hmac(prk, concat(
                "Content-Encoding: nonce".getBytes(StandardCharsets.US_ASCII),
                new byte[]{0x00, 0x01})), 12);

        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        var padded = cipher.doFinal(ciphertext);
        return Arrays.copyOf(padded, padded.length - 1); // strip the 0x02 record delimiter
    }

    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static byte[] joseToDer(byte[] jose) {
        var r = trimLeadingZeros(Arrays.copyOfRange(jose, 0, 32));
        var s = trimLeadingZeros(Arrays.copyOfRange(jose, 32, 64));
        var rDer = withSignByte(r);
        var sDer = withSignByte(s);
        var out = new ByteArrayOutputStream();
        out.write(0x30);
        out.write(2 + rDer.length + 2 + sDer.length);
        out.write(0x02);
        out.write(rDer.length);
        out.writeBytes(rDer);
        out.write(0x02);
        out.write(sDer.length);
        out.writeBytes(sDer);
        return out.toByteArray();
    }

    private static byte[] trimLeadingZeros(byte[] value) {
        int start = 0;
        while (start < value.length - 1 && value[start] == 0) {
            start++;
        }
        return Arrays.copyOfRange(value, start, value.length);
    }

    private static byte[] withSignByte(byte[] value) {
        if ((value[0] & 0x80) != 0) {
            var padded = new byte[value.length + 1];
            System.arraycopy(value, 0, padded, 1, value.length);
            return padded;
        }
        return value;
    }
}
