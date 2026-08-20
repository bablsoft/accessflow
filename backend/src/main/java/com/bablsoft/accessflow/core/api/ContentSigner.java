package com.bablsoft.accessflow.core.api;

/**
 * Produces a detached digital signature over a byte payload using the deployment's identity key.
 * Extracted from the security module's {@code ExportSignatureService} (which extends this
 * interface) so modules the security module itself depends on — notably {@code audit}, whose
 * S3 Object Lock segments carry a chain-head signature (#628) — can sign content without
 * creating a module cycle.
 *
 * <p>Intentionally free of any third-party type so it can live in a module {@code api} package.
 */
public interface ContentSigner {

    /**
     * Signs {@code content} and returns the signature Base64-encoded. The signature is detached —
     * it does not modify {@code content}; callers stamp it into a header or sidecar object.
     */
    String sign(byte[] content);

    /** The signature algorithm identifier callers stamp alongside the signature, e.g. {@code SHA256withRSA}. */
    String algorithm();

    /**
     * The PEM-encoded X.509 SubjectPublicKeyInfo of the signing key, so an auditor can verify a
     * detached signature offline (e.g. {@code openssl dgst -sha256 -verify key.pem -signature sig export}).
     */
    String publicKeyPem();
}
