package com.bablsoft.accessflow.security.internal.saml;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Properties for the SAML redirect-with-exchange-code flow.
 * - {@code frontendCallbackUrl}: where the success / failure handler redirects after the IdP
 *   roundtrip completes. The frontend page parses {@code ?code=...} or {@code ?error=...} from
 *   the query string and calls {@code POST /api/v1/auth/saml/exchange} to claim the JWT pair.
 * - {@code exchangeCodeTtl}: lifetime of the one-time code in Redis.
 */
@ConfigurationProperties(prefix = "accessflow.saml")
public record SamlRedirectProperties(
        String frontendCallbackUrl,
        Duration exchangeCodeTtl) {
}
