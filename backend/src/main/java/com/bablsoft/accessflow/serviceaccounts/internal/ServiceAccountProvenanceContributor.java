package com.bablsoft.accessflow.serviceaccounts.internal;

import com.bablsoft.accessflow.audit.api.AuditMetadataContributor;
import com.bablsoft.accessflow.security.api.ApiKeyAuthentication;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.serviceaccounts.api.OnBehalfOfPrincipalService;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountLookupService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stamps every audit row written on an API-key request with who really acted (#874):
 * {@code api_key_id} (which key), {@code service_account} (whether the key's owner is a
 * non-human identity) and, when the request named one, {@code on_behalf_of_user_id}. The keys ride
 * in {@code audit_log.metadata}, which is already inside the HMAC chain — no schema change. Rows a
 * JWT session writes, and rows written off the request thread, get nothing from here.
 *
 * <p>The service-account flag is resolved lazily (one PK read per audit row, never per request):
 * {@code JwtClaims} deliberately carries no {@code principalType}.
 */
@Component
@RequiredArgsConstructor
class ServiceAccountProvenanceContributor implements AuditMetadataContributor {

    static final String API_KEY_ID = "api_key_id";
    static final String SERVICE_ACCOUNT = "service_account";
    static final String ON_BEHALF_OF_USER_ID = "on_behalf_of_user_id";

    private final ServiceAccountLookupService lookupService;
    private final OnBehalfOfPrincipalService onBehalfOfPrincipalService;

    @Override
    public Map<String, Object> contribute() {
        if (RequestContextHolder.getRequestAttributes() == null) {
            return Map.of();
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof ApiKeyAuthentication apiKey)
                || !(authentication.getPrincipal() instanceof JwtClaims claims)) {
            return Map.of();
        }
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put(API_KEY_ID, apiKey.apiKeyId().toString());
        metadata.put(SERVICE_ACCOUNT, lookupService.findByUserId(claims.userId()).isPresent());
        onBehalfOfPrincipalService.current()
                .ifPresent(principal -> metadata.put(ON_BEHALF_OF_USER_ID, principal.toString()));
        return metadata;
    }
}
