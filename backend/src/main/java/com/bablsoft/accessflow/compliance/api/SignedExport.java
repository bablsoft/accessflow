package com.bablsoft.accessflow.compliance.api;

/**
 * A rendered, digitally-signed compliance export (#459). The {@code signatureBase64} is a detached
 * signature over the exact {@code content} bytes (verifiable offline with the public key), and
 * {@code contentSha256Hex} is the SHA-256 of those bytes — the same hash recorded in the
 * tamper-evident audit log when the export was produced.
 */
public record SignedExport(
        byte[] content,
        String filename,
        String contentType,
        String contentSha256Hex,
        String signatureBase64,
        String signatureAlgorithm,
        boolean truncated) {

    public SignedExport {
        content = content == null ? new byte[0] : content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
