package com.bablsoft.accessflow.workflow.api;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.UUID;

/**
 * An external decision hook (#945). The signing secret is never part of the view; only whether one
 * is stored.
 *
 * @param datasourceId {@code null} for the organization default
 */
public record DecisionHookView(
        UUID id,
        UUID organizationId,
        UUID datasourceId,
        String name,
        String endpointUrl,
        int timeoutMs,
        boolean includeSql,
        boolean enabled,
        boolean secretConfigured,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * {@code scheme://host[:port]} of the endpoint — where submitter data is sent, for the audit
     * trail — without the path or query, which may carry tokens. {@code null} if it does not parse.
     */
    public String endpointOrigin() {
        try {
            var uri = new URI(endpointUrl);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            return uri.getScheme() + "://" + uri.getHost()
                    + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        } catch (URISyntaxException ex) {
            return null;
        }
    }
}
