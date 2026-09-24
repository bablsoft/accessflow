package com.bablsoft.accessflow.security.internal;

import com.bablsoft.accessflow.audit.api.AuditMetadataContributor;
import com.bablsoft.accessflow.security.api.RequestApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * Stamps every audit row written on a request that names a calling application (#938) with
 * {@code application_name} and {@code application_name_source} ({@code api_key} or {@code header}).
 * The keys ride in {@code audit_log.metadata}, already inside the HMAC chain — no schema change.
 */
@Component
@RequiredArgsConstructor
class ApplicationAuditMetadataContributor implements AuditMetadataContributor {

    static final String APPLICATION_NAME = "application_name";
    static final String APPLICATION_NAME_SOURCE = "application_name_source";

    private final RequestApplicationService requestApplicationService;

    @Override
    public Map<String, Object> contribute() {
        return requestApplicationService.current()
                .<Map<String, Object>>map(app -> Map.of(
                        APPLICATION_NAME, app.name(),
                        APPLICATION_NAME_SOURCE, app.source().name().toLowerCase(Locale.ROOT)))
                .orElse(Map.of());
    }
}
