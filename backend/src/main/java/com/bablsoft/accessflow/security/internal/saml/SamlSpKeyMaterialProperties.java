package com.bablsoft.accessflow.security.internal.saml;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Optional environment-supplied SP signing keypair. When both fields are populated, they
 * take precedence over the auto-generated keypair persisted in {@code saml_config}.
 *
 * Operators use this in tightly controlled environments where the SP keypair is provisioned
 * out of band (HSM-exported, sealed in a secret manager, etc.). Otherwise SamlSpKeyProvider
 * generates a self-signed RSA-2048 keypair on first use and persists it (encrypted) so the
 * value survives restarts.
 */
@ConfigurationProperties(prefix = "accessflow.saml.sp")
public record SamlSpKeyMaterialProperties(
        String signingKeyPem,
        String signingCertPem) {

    public boolean isConfigured() {
        return signingKeyPem != null && !signingKeyPem.isBlank()
                && signingCertPem != null && !signingCertPem.isBlank();
    }
}
