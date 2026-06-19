package com.bablsoft.accessflow.security.api;

/**
 * Produces a detached digital signature over an exported artifact (e.g. a compliance report) using
 * the deployment's RSA key pair — the same key pair that signs JWTs. The signature lets an external
 * auditor verify a downloaded export is authentic and unmodified, offline, using only the public key
 * returned by {@link #publicKeyPem()}.
 *
 * <p>This interface is intentionally free of any third-party type so it can live in a module {@code
 * api} package; the implementation lives in {@code security.internal}.
 */
public interface ExportSignatureService {

    /**
     * Signs {@code content} and returns the signature Base64-encoded. The signature is detached — it
     * does not modify {@code content}; callers stamp it into a response header / sidecar.
     */
    String sign(byte[] content);

    /** The signature algorithm identifier callers stamp alongside the signature, e.g. {@code SHA256withRSA}. */
    String algorithm();

    /**
     * The PEM-encoded X.509 SubjectPublicKeyInfo of the signing key, so an auditor can verify a
     * detached signature offline (e.g. {@code openssl dgst -sha256 -verify key.pem -signature sig export}).
     */
    String publicKeyPem();

    /** Verifies a Base64-encoded {@code signatureBase64} against {@code content}. */
    boolean verify(byte[] content, String signatureBase64);
}
