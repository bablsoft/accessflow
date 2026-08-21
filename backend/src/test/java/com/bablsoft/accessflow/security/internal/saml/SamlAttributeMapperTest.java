package com.bablsoft.accessflow.security.internal.saml;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.SamlConfigView;
import org.junit.jupiter.api.Test;
import org.springframework.security.saml2.provider.service.authentication.Saml2ResponseAssertion;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SamlAttributeMapperTest {

    private final UUID orgId = UUID.randomUUID();

    @Test
    void mapsEmailDisplayNameAndRoleFromConfiguredAttributes() {
        var assertion = assertion("alice", Map.of(
                "email", List.of("alice@example.com"),
                "displayName", List.of("Alice Liddell"),
                "role", List.of("REVIEWER")));
        var config = config("email", "displayName", "role", UserRoleType.ANALYST);

        var mapped = SamlAttributeMapper.map(assertion, config);

        assertThat(mapped.email()).isEqualTo("alice@example.com");
        assertThat(mapped.displayName()).isEqualTo("Alice Liddell");
        assertThat(mapped.role()).isEqualTo(UserRoleType.REVIEWER);
    }

    @Test
    void fallsBackToNameIdWhenEmailAttributeMissingButNameLooksLikeEmail() {
        var assertion = assertion("bob@example.com", Map.of(
                "displayName", List.of("Bob")));
        var config = config("email", "displayName", null, UserRoleType.ANALYST);

        var mapped = SamlAttributeMapper.map(assertion, config);

        assertThat(mapped.email()).isEqualTo("bob@example.com");
        assertThat(mapped.displayName()).isEqualTo("Bob");
    }

    @Test
    void returnsNullEmailWhenAttributeAbsentAndNameIsNotEmail() {
        var assertion = assertion("opaque-id", Map.of("displayName", List.of("Carol")));
        var config = config("email", "displayName", null, UserRoleType.ANALYST);

        var mapped = SamlAttributeMapper.map(assertion, config);

        assertThat(mapped.email()).isNull();
    }

    @Test
    void fallsBackToEmailForDisplayNameWhenAttributeBlank() {
        var assertion = assertion("dave@example.com", Map.of(
                "email", List.of("dave@example.com"),
                "displayName", List.of("   ")));
        var config = config("email", "displayName", null, UserRoleType.ANALYST);

        var mapped = SamlAttributeMapper.map(assertion, config);

        assertThat(mapped.displayName()).isEqualTo("dave@example.com");
    }

    @Test
    void usesDefaultRoleWhenAttrRoleIsNull() {
        var assertion = assertion("eve", Map.of(
                "email", List.of("eve@example.com")));
        var config = config("email", "displayName", null, UserRoleType.ADMIN);

        var mapped = SamlAttributeMapper.map(assertion, config);

        assertThat(mapped.role()).isEqualTo(UserRoleType.ADMIN);
    }

    @Test
    void usesDefaultRoleWhenAttrRoleConfiguredButAssertionMissesIt() {
        var assertion = assertion("frank", Map.of(
                "email", List.of("frank@example.com")));
        var config = config("email", "displayName", "role", UserRoleType.REVIEWER);

        var mapped = SamlAttributeMapper.map(assertion, config);

        assertThat(mapped.role()).isEqualTo(UserRoleType.REVIEWER);
    }

    @Test
    void fallsBackToDefaultRoleWhenAssertedRoleNotInEnum() {
        var assertion = assertion("grace", Map.of(
                "email", List.of("grace@example.com"),
                "role", List.of("SUPERUSER")));
        var config = config("email", "displayName", "role", UserRoleType.ANALYST);

        var mapped = SamlAttributeMapper.map(assertion, config);

        assertThat(mapped.role()).isEqualTo(UserRoleType.ANALYST);
    }

    @Test
    void handlesLowercaseRoleValues() {
        var assertion = assertion("heidi", Map.of(
                "email", List.of("heidi@example.com"),
                "role", List.of("admin")));
        var config = config("email", "displayName", "role", UserRoleType.ANALYST);

        var mapped = SamlAttributeMapper.map(assertion, config);

        assertThat(mapped.role()).isEqualTo(UserRoleType.ADMIN);
    }

    private Saml2ResponseAssertion assertion(String nameId, Map<String, List<Object>> attributes) {
        return Saml2ResponseAssertion.withResponseValue("response")
                .nameId(nameId)
                .attributes(attributes)
                .build();
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
                null,
                java.util.Map.of(),
                defaultRole,
                true,
                Instant.now(),
                Instant.now());
    }
}
