package com.bablsoft.accessflow.compliance.internal.web;

/**
 * Public verification material for compliance-export signatures (#459): the signature algorithm and
 * the PEM-encoded public key. An auditor uses these to verify a downloaded export offline.
 */
public record SigningCertificateResponse(String algorithm, String publicKeyPem) {
}
