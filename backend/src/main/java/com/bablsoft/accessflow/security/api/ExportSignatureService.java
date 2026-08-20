package com.bablsoft.accessflow.security.api;

import com.bablsoft.accessflow.core.api.ContentSigner;

/**
 * Produces a detached digital signature over an exported artifact (e.g. a compliance report) using
 * the deployment's RSA key pair — the same key pair that signs JWTs. The signature lets an external
 * auditor verify a downloaded export is authentic and unmodified, offline, using only the public key
 * returned by {@link #publicKeyPem()}.
 *
 * <p>The sign/algorithm/public-key contract lives on {@link ContentSigner} in {@code core.api} so
 * modules the security module depends on (e.g. {@code audit}, for the #628 S3 segment signature)
 * can consume the same bean without a module cycle; this sub-interface adds server-side
 * verification for callers inside the security module's dependency cone.
 *
 * <p>This interface is intentionally free of any third-party type so it can live in a module {@code
 * api} package; the implementation lives in {@code security.internal}.
 */
public interface ExportSignatureService extends ContentSigner {

    /** Verifies a Base64-encoded {@code signatureBase64} against {@code content}. */
    boolean verify(byte[] content, String signatureBase64);
}
