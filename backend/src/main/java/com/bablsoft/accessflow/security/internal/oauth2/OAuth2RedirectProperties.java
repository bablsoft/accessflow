package com.bablsoft.accessflow.security.internal.oauth2;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Properties for the OAuth2 redirect-with-exchange-code flow.
 * - {@code frontendCallbackUrl}: where the success/failure handler redirects after the provider
 *   roundtrip completes. The frontend page parses {@code ?code=...} or {@code ?error=...} from
 *   the query string and calls {@code POST /api/v1/auth/oauth2/exchange} to claim the JWT pair.
 * - {@code exchangeCodeTtl}: lifetime of the one-time code in Redis.
 */
@ConfigurationProperties(prefix = "accessflow.oauth2")
public record OAuth2RedirectProperties(
        String frontendCallbackUrl,
        Duration exchangeCodeTtl) {
}
