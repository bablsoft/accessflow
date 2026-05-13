package com.bablsoft.accessflow.security.internal.oauth2;

import com.bablsoft.accessflow.security.api.OAuth2ProviderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Extracts (email, displayName, emailVerified) from an OAuth2 userinfo claims map.
 * For GitHub the {@code /user} response often hides {@code email} when the user has it set to
 * private — we then issue a second call to {@code /user/emails} with the access token and pick
 * the primary verified address.
 */
@Component
public class OAuth2EmailResolver {

    private static final Logger log = LoggerFactory.getLogger(OAuth2EmailResolver.class);
    private static final String GITHUB_EMAILS_URI = "https://api.github.com/user/emails";

    private final RestClient restClient;

    public OAuth2EmailResolver() {
        this(RestClient.builder().build());
    }

    OAuth2EmailResolver(RestClient restClient) {
        this.restClient = restClient;
    }

    public Resolved resolve(OAuth2ProviderType provider,
                            Map<String, Object> attributes,
                            String accessToken) {
        var template = OAuth2ProviderTemplate.forProvider(provider);
        var email = stringAttr(attributes, template.emailAttributeName());
        var displayName = stringAttr(attributes, template.displayNameAttributeName());

        Boolean verified = null;
        if (template.emailVerifiedAttributeName() != null) {
            var raw = attributes.get(template.emailVerifiedAttributeName());
            if (raw instanceof Boolean b) {
                verified = b;
            } else if (raw instanceof String s) {
                verified = Boolean.parseBoolean(s);
            }
        }

        if (provider == OAuth2ProviderType.GITHUB) {
            var fallback = githubPrimaryEmail(accessToken);
            if (fallback != null) {
                if (email == null || email.isBlank()) {
                    email = fallback.email();
                }
                verified = fallback.verified();
            } else if (verified == null && email != null) {
                // GitHub only returns verified emails on /user/emails — if /user gave us a string
                // we can't independently verify, so be conservative.
                verified = false;
            }
        } else if (verified == null) {
            verified = false;
        }

        return new Resolved(email, displayName, verified);
    }

    private GithubEmail githubPrimaryEmail(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> emails = (List<Map<String, Object>>) restClient.get()
                    .uri(GITHUB_EMAILS_URI)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .retrieve()
                    .body(List.class);
            if (emails == null) {
                return null;
            }
            for (var entry : emails) {
                var primary = Boolean.TRUE.equals(entry.get("primary"));
                var verified = Boolean.TRUE.equals(entry.get("verified"));
                if (primary && verified) {
                    return new GithubEmail((String) entry.get("email"), true);
                }
            }
        } catch (RuntimeException ex) {
            log.warn("GitHub /user/emails lookup failed: {}", ex.getMessage());
        }
        return null;
    }

    private static String stringAttr(Map<String, Object> attrs, String name) {
        if (name == null) return null;
        var raw = attrs.get(name);
        return raw instanceof String s ? s : null;
    }

    public record Resolved(String email, String displayName, Boolean emailVerified) {}

    private record GithubEmail(String email, boolean verified) {}
}
