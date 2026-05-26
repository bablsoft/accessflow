package com.bablsoft.accessflow.security.internal.oauth2;

import com.bablsoft.accessflow.security.api.OAuth2ProviderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the set of "organizations" the authenticated OAuth2 user belongs to, as understood by
 * each provider. Used by {@link OAuth2LoginSuccessHandler} to enforce per-config
 * {@code allowed_organizations} allowlists.
 *
 * <p>Per-provider semantics:
 * <ul>
 *     <li><b>GITHUB</b> — calls {@code GET https://api.github.com/user/orgs} with the issued
 *         access token (requires the {@code read:org} scope) and returns the set of org
 *         {@code login} values. Returns an empty set on HTTP failure so a configured allowlist
 *         rejects the login rather than failing open.</li>
 *     <li><b>GITHUB_ENTERPRISE</b> — same as GITHUB but at {@code {base}/api/v3/user/orgs} on the
 *         operator's self-hosted instance.</li>
 *     <li><b>GITLAB</b>, <b>GITLAB_ENTERPRISE</b> — reads the OIDC {@code groups} claim from
 *         userinfo (full group paths).</li>
 *     <li><b>MICROSOFT</b> — reads the {@code groups} claim from the ID token / userinfo response
 *         (object IDs of AAD groups; only emitted when the app's token configuration enables it).</li>
 *     <li><b>GOOGLE</b> — returns an empty set. Google's organization surface is the email domain,
 *         enforced by the {@code allowed_email_domains} allowlist.</li>
 *     <li><b>OIDC</b> — reads the claim named by {@code groupsAttribute} on the config row. When
 *         the column is null, returns an empty set so the allowlist (if configured) rejects the
 *         login rather than failing open.</li>
 * </ul>
 */
@Component
public class OAuth2MembershipResolver {

    private static final Logger log = LoggerFactory.getLogger(OAuth2MembershipResolver.class);
    private static final String GITHUB_ORGS_URI = "https://api.github.com/user/orgs";

    private final RestClient restClient;

    public OAuth2MembershipResolver() {
        this(RestClient.builder().build());
    }

    OAuth2MembershipResolver(RestClient restClient) {
        this.restClient = restClient;
    }

    public Set<String> resolveOrganizations(OAuth2ProviderType provider,
                                            Map<String, Object> attributes,
                                            String accessToken) {
        return resolveOrganizations(provider, attributes, accessToken, null, null);
    }

    public Set<String> resolveOrganizations(OAuth2ProviderType provider,
                                            Map<String, Object> attributes,
                                            String accessToken,
                                            String groupsAttribute) {
        return resolveOrganizations(provider, attributes, accessToken, groupsAttribute, null);
    }

    public Set<String> resolveOrganizations(OAuth2ProviderType provider,
                                            Map<String, Object> attributes,
                                            String accessToken,
                                            String groupsAttribute,
                                            String baseUrl) {
        return switch (provider) {
            case GITHUB -> githubOrgLogins(accessToken, GITHUB_ORGS_URI);
            case GITHUB_ENTERPRISE -> {
                var uri = githubEnterpriseOrgsUri(baseUrl);
                yield uri == null ? Set.of() : githubOrgLogins(accessToken, uri);
            }
            case GITLAB, GITLAB_ENTERPRISE, MICROSOFT -> claimList(attributes, "groups");
            case GOOGLE -> Set.of();
            case OIDC -> groupsAttribute == null || groupsAttribute.isBlank()
                    ? Set.of()
                    : claimList(attributes, groupsAttribute);
        };
    }

    private Set<String> githubOrgLogins(String accessToken, String orgsUri) {
        if (accessToken == null || accessToken.isBlank()) {
            log.warn("Skipping GitHub /user/orgs lookup — no access token available");
            return Set.of();
        }
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> orgs = (List<Map<String, Object>>) restClient.get()
                    .uri(orgsUri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .retrieve()
                    .body(List.class);
            if (orgs == null) {
                return Set.of();
            }
            var out = new LinkedHashSet<String>();
            for (var org : orgs) {
                var login = org.get("login");
                if (login instanceof String s && !s.isBlank()) {
                    out.add(s);
                }
            }
            return out;
        } catch (RuntimeException ex) {
            log.warn("GitHub /user/orgs lookup failed at {}: {}", orgsUri, ex.getMessage());
            return Set.of();
        }
    }

    private static String githubEnterpriseOrgsUri(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        var trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + "/api/v3/user/orgs";
    }

    private static Set<String> claimList(Map<String, Object> attributes, String claim) {
        var raw = attributes.get(claim);
        if (!(raw instanceof Collection<?> values)) {
            return Set.of();
        }
        var out = new LinkedHashSet<String>();
        for (var v : values) {
            if (v instanceof String s && !s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }
}
