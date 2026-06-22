package com.bablsoft.accessflow.notifications.internal.push;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;

/**
 * Pure-JDK implementation of the two crypto primitives a Web Push server needs (AF-444):
 *
 * <ul>
 *   <li>RFC 8291 message encryption (Content-Encoding {@code aes128gcm}) — ECDH on P-256, a pair
 *       of HKDF-SHA256 derivations, and AES-128-GCM — producing the request body.</li>
 *   <li>RFC 8292 VAPID — an ES256-signed JWT yielding the {@code Authorization: vapid t=…, k=…}
 *       header that identifies this application server to the push service.</li>
 * </ul>
 *
 * Implemented against {@code java.security} / {@code javax.crypto} only — no BouncyCastle, no
 * web-push library, no Netty — to keep the dependency surface flat and the JAR conflict-free.
 * All methods are stateless and side-effect free, which is what makes the round trip unit-testable.
 */
final class WebPushCrypto {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64URL_DEC = Base64.getUrlDecoder();
    private static final String JWT_HEADER = "{\"typ\":\"JWT\",\"alg\":\"ES256\"}";
    private static final byte[] KEY_INFO_PREFIX = ascii("WebPush: info");
    private static final byte[] CEK_INFO = withTrailingZero("Content-Encoding: aes128gcm");
    private static final byte[] NONCE_INFO = withTrailingZero("Content-Encoding: nonce");
    private static final int RECORD_SIZE = 4096;
    private static final long VAPID_EXPIRY_SECONDS = ChronoUnit.HOURS.getDuration().getSeconds() * 12;

    private WebPushCrypto() {
    }

    /** Generates a fresh P-256 keypair for use as the deployment VAPID keypair. */
    static KeyPair generateVapidKeyPair() {
        try {
            var generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to generate VAPID keypair", ex);
        }
    }

    /** Serialises an EC public key to the 65-byte uncompressed point form (0x04 || X || Y). */
    static byte[] encodePublicKey(ECPublicKey key) {
        var point = key.getW();
        var x = toFixed(point.getAffineX(), 32);
        var y = toFixed(point.getAffineY(), 32);
        var out = new byte[65];
        out[0] = 0x04;
        System.arraycopy(x, 0, out, 1, 32);
        System.arraycopy(y, 0, out, 33, 32);
        return out;
    }

    /** Serialises an EC private key to its raw 32-byte scalar (matches {@code web-push} tooling). */
    static byte[] encodePrivateKey(ECPrivateKey key) {
        return toFixed(key.getS(), 32);
    }

    static String base64Url(byte[] data) {
        return B64URL.encodeToString(data);
    }

    static byte[] decodeBase64Url(String value) {
        return B64URL_DEC.decode(value);
    }

    static ECPublicKey decodePublicKey(byte[] uncompressed) {
        if (uncompressed.length != 65 || uncompressed[0] != 0x04) {
            throw new IllegalArgumentException("Expected a 65-byte uncompressed P-256 point");
        }
        var x = new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33));
        var y = new BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65));
        try {
            var keyFactory = KeyFactory.getInstance("EC");
            return (ECPublicKey) keyFactory.generatePublic(
                    new ECPublicKeySpec(new ECPoint(x, y), p256Params()));
        } catch (GeneralSecurityException ex) {
            throw new IllegalArgumentException("Invalid P-256 public key", ex);
        }
    }

    static ECPrivateKey decodePrivateKey(byte[] scalar) {
        try {
            var keyFactory = KeyFactory.getInstance("EC");
            return (ECPrivateKey) keyFactory.generatePrivate(
                    new ECPrivateKeySpec(new BigInteger(1, scalar), p256Params()));
        } catch (GeneralSecurityException ex) {
            throw new IllegalArgumentException("Invalid P-256 private key", ex);
        }
    }

    /**
     * Encrypts {@code plaintext} for the subscription identified by its {@code p256dh} public key
     * and {@code auth} secret, producing the {@code aes128gcm} request body (header + ciphertext).
     */
    static byte[] encrypt(byte[] plaintext, byte[] uaPublicKey, byte[] authSecret) {
        try {
            var ephemeral = generateVapidKeyPair();
            var asPublic = encodePublicKey((ECPublicKey) ephemeral.getPublic());
            var sharedSecret = ecdh((ECPrivateKey) ephemeral.getPrivate(), decodePublicKey(uaPublicKey));
            var salt = new byte[16];
            RANDOM.nextBytes(salt);

            // RFC 8291 §3.4 — fold the auth secret and both public keys into the IKM.
            var prkKey = hmacSha256(authSecret, sharedSecret);
            var keyInfo = concat(KEY_INFO_PREFIX, new byte[]{0x00}, uaPublicKey, asPublic);
            var ikm = hkdfExpand(prkKey, keyInfo, 32);

            // RFC 8188 — derive the content-encryption key and nonce from the random salt.
            var prk = hmacSha256(salt, ikm);
            var cek = hkdfExpand(prk, CEK_INFO, 16);
            var nonce = hkdfExpand(prk, NONCE_INFO, 12);

            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"),
                    new GCMParameterSpec(128, nonce));
            // Single record: append the 0x02 last-record delimiter (no further padding).
            var ciphertext = cipher.doFinal(concat(plaintext, new byte[]{0x02}));

            var body = new ByteArrayOutputStream();
            body.writeBytes(salt);
            body.writeBytes(intToBytes(RECORD_SIZE));
            body.write(asPublic.length);
            body.writeBytes(asPublic);
            body.writeBytes(ciphertext);
            return body.toByteArray();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Web Push message encryption failed", ex);
        }
    }

    /**
     * Builds the {@code Authorization} header value ({@code vapid t=<jwt>, k=<publicKey>}) for a
     * request to {@code endpoint}, signed with the deployment VAPID private key.
     */
    static String vapidAuthorization(String endpoint, ECPrivateKey vapidPrivateKey,
                                     byte[] vapidPublicKey, String subject, Instant now) {
        var audience = origin(endpoint);
        var expiry = now.plusSeconds(VAPID_EXPIRY_SECONDS).getEpochSecond();
        var payload = "{\"aud\":\"" + audience + "\",\"exp\":" + expiry
                + ",\"sub\":\"" + subject + "\"}";
        var signingInput = base64Url(ascii(JWT_HEADER)) + "." + base64Url(ascii(payload));
        try {
            var signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(vapidPrivateKey);
            signer.update(ascii(signingInput));
            var jose = derToJose(signer.sign());
            var jwt = signingInput + "." + base64Url(jose);
            return "vapid t=" + jwt + ", k=" + base64Url(vapidPublicKey);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("VAPID JWT signing failed", ex);
        }
    }

    static String origin(String endpoint) {
        var uri = URI.create(endpoint);
        var origin = uri.getScheme() + "://" + uri.getHost();
        if (uri.getPort() != -1) {
            origin += ":" + uri.getPort();
        }
        return origin;
    }

    private static byte[] ecdh(ECPrivateKey privateKey, ECPublicKey publicKey)
            throws GeneralSecurityException {
        var agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(privateKey);
        agreement.doPhase(publicKey, true);
        return agreement.generateSecret();
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) throws GeneralSecurityException {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    // HKDF-Expand for a single output block (L <= 32): T(1) = HMAC(prk, info || 0x01).
    private static byte[] hkdfExpand(byte[] prk, byte[] info, int length)
            throws GeneralSecurityException {
        var block = hmacSha256(prk, concat(info, new byte[]{0x01}));
        return Arrays.copyOf(block, length);
    }

    /** Converts a DER-encoded ECDSA signature into the fixed 64-byte JOSE (R || S) form. */
    static byte[] derToJose(byte[] der) {
        int rLength = der[3];
        int rStart = 4;
        int sLengthPos = rStart + rLength + 1;
        int sLength = der[sLengthPos];
        int sStart = sLengthPos + 1;
        var out = new byte[64];
        copyRightAligned(der, rStart, rLength, out, 0, 32);
        copyRightAligned(der, sStart, sLength, out, 32, 32);
        return out;
    }

    private static void copyRightAligned(byte[] src, int srcOffset, int srcLength,
                                         byte[] dst, int dstOffset, int len) {
        int start = srcOffset;
        int remaining = srcLength;
        while (remaining > len && src[start] == 0) {
            start++;
            remaining--;
        }
        if (remaining > len) {
            remaining = len;
        }
        System.arraycopy(src, start, dst, dstOffset + (len - remaining), remaining);
    }

    private static byte[] toFixed(BigInteger value, int length) {
        var bytes = value.toByteArray();
        if (bytes.length == length) {
            return bytes;
        }
        var out = new byte[length];
        if (bytes.length > length) {
            System.arraycopy(bytes, bytes.length - length, out, 0, length);
        } else {
            System.arraycopy(bytes, 0, out, length - bytes.length, bytes.length);
        }
        return out;
    }

    private static ECParameterSpec p256Params() throws GeneralSecurityException {
        var params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec("secp256r1"));
        return params.getParameterSpec(ECParameterSpec.class);
    }

    private static byte[] intToBytes(int value) {
        return new byte[]{
                (byte) (value >>> 24), (byte) (value >>> 16),
                (byte) (value >>> 8), (byte) value};
    }

    private static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] withTrailingZero(String value) {
        return concat(ascii(value), new byte[]{0x00});
    }
}
