package com.bablsoft.accessflow.security.internal.saml;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.SamlConfigView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;

import java.util.Locale;

/**
 * Maps the attributes carried by an IdP-issued {@link Saml2AuthenticatedPrincipal} onto the
 * AccessFlow identity triple {@code (email, displayName, role)} using the per-org attribute
 * names from {@link SamlConfigView}.
 *
 * Pure logic — no Spring, no IO; the success handler injects this and tests cover every branch.
 */
public final class SamlAttributeMapper {

    private static final Logger log = LoggerFactory.getLogger(SamlAttributeMapper.class);

    private SamlAttributeMapper() {
    }

    public static Mapped map(Saml2AuthenticatedPrincipal principal, SamlConfigView config) {
        var email = firstAttribute(principal, config.attrEmail());
        if (email == null && principal.getName() != null && principal.getName().contains("@")) {
            email = principal.getName();
        }
        var displayName = firstAttribute(principal, config.attrDisplayName());
        if (displayName == null || displayName.isBlank()) {
            displayName = email != null ? email : principal.getName();
        }
        var role = resolveRole(principal, config);
        return new Mapped(trimToNull(email), trimToNull(displayName), role);
    }

    private static UserRoleType resolveRole(Saml2AuthenticatedPrincipal principal, SamlConfigView config) {
        if (config.attrRole() == null || config.attrRole().isBlank()) {
            return config.defaultRole();
        }
        var asserted = firstAttribute(principal, config.attrRole());
        if (asserted == null || asserted.isBlank()) {
            return config.defaultRole();
        }
        try {
            return UserRoleType.valueOf(asserted.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            log.warn("SAML asserted role '{}' is not a recognised AccessFlow role — falling back to {}",
                    asserted, config.defaultRole());
            return config.defaultRole();
        }
    }

    private static String firstAttribute(Saml2AuthenticatedPrincipal principal, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        var value = principal.getFirstAttribute(name);
        return value != null ? value.toString() : null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record Mapped(String email, String displayName, UserRoleType role) {
    }
}
