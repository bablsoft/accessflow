package com.bablsoft.accessflow.security.internal.saml;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.SamlConfigView;
import org.junit.jupiter.api.Test;
import org.springframework.security.saml2.provider.service.authentication.DefaultSaml2AuthenticatedPrincipal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SamlAttributeMapperTest {

    private final UUID orgId = UUID.randomUUID();

    @Test
    void mapsEmailDisplayNameAndRoleFromConfiguredAttributes() {
        var principal = principal("alice", Map.of(
                "email", List.of("alice@example.com"),
                "displayName", List.of("Alice Liddell"),
                "role", List.of("REVIEWER")));
        var config = config("email", "displayName", "role", UserRoleType.ANALYST);

        var mapped = SamlAttributeMapper.map(principal, config);

        assertThat(mapped.email()).isEqualTo("alice@example.com");
        assertThat(mapped.displayName()).isEqualTo("Alice Liddell");
        assertThat(mapped.role()).isEqualTo(UserRoleType.REVIEWER);
    }

    @Test
    void fallsBackToNameIdWhenEmailAttributeMissingButNameLooksLikeEmail() {
        var principal = principal("bob@example.com", Map.of(
                "displayName", List.of("Bob")));
        var config = config("email", "displayName", null, UserRoleType.ANALYST);

        var mapped = SamlAttributeMapper.map(principal, config);

        assertThat(mapped.email()).isEqualTo("bob@example.com");
        assertThat(mapped.displayName()).isEqualTo("Bob");
    }

    @Test
    void returnsNullEmailWhenAttributeAbsentAndNameIsNotEmail() {
        var principal = principal("opaque-id", Map.of("displayName", List.of("Carol")));
        var config = config("email", "displayName", null, UserRoleType.ANALYST);

        var mapped = SamlAttributeMapper.map(principal, config);

        assertThat(mapped.email()).isNull();
    }

    @Test
    void fallsBackToEmailForDisplayNameWhenAttributeBlank() {
        var principal = principal("dave@example.com", Map.of(
                "email", List.of("dave@example.com"),
                "displayName", List.of("   ")));
        var config = config("email", "displayName", null, UserRoleType.ANALYST);

        var mapped = SamlAttributeMapper.map(principal, config);

        assertThat(mapped.displayName()).isEqualTo("dave@example.com");
    }

    @Test
    void usesDefaultRoleWhenAttrRoleIsNull() {
        var principal = principal("eve", Map.of(
                "email", List.of("eve@example.com")));
        var config = config("email", "displayName", null, UserRoleType.ADMIN);

        var mapped = SamlAttributeMapper.map(principal, config);

        assertThat(mapped.role()).isEqualTo(UserRoleType.ADMIN);
    }

    @Test
    void usesDefaultRoleWhenAttrRoleConfiguredButAssertionMissesIt() {
        var principal = principal("frank", Map.of(
                "email", List.of("frank@example.com")));
        var config = config("email", "displayName", "role", UserRoleType.REVIEWER);

        var mapped = SamlAttributeMapper.map(principal, config);

        assertThat(mapped.role()).isEqualTo(UserRoleType.REVIEWER);
    }

    @Test
    void fallsBackToDefaultRoleWhenAssertedRoleNotInEnum() {
        var principal = principal("grace", Map.of(
                "email", List.of("grace@example.com"),
                "role", List.of("AUDITOR")));
        var config = config("email", "displayName", "role", UserRoleType.ANALYST);

        var mapped = SamlAttributeMapper.map(principal, config);

        assertThat(mapped.role()).isEqualTo(UserRoleType.ANALYST);
    }

    @Test
    void handlesLowercaseRoleValues() {
        var principal = principal("heidi", Map.of(
                "email", List.of("heidi@example.com"),
                "role", List.of("admin")));
        var config = config("email", "displayName", "role", UserRoleType.ANALYST);

        var mapped = SamlAttributeMapper.map(principal, config);

        assertThat(mapped.role()).isEqualTo(UserRoleType.ADMIN);
    }

    private DefaultSaml2AuthenticatedPrincipal principal(String nameId, Map<String, List<Object>> attributes) {
        return new DefaultSaml2AuthenticatedPrincipal(nameId, attributes);
    }

    private SamlConfigView config(String attrEmail, String attrDisplayName, String attrRole,
                                  UserRoleType defaultRole) {
        return new SamlConfigView(
                UUID.randomUUID(),
                orgId,
                "https://idp.example.com/metadata",
                "idp-entity",
                "sp-entity",
                "https://app.example.com/api/v1/auth/saml/acs",
                null,
                true,
                attrEmail,
                attrDisplayName,
                attrRole,
                defaultRole,
                true,
                Instant.now(),
                Instant.now());
    }
}
