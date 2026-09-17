package com.bablsoft.accessflow.security.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;

import static org.assertj.core.api.Assertions.assertThat;

class JwtClaimsTest {

    /**
     * The principal's shape is the contract ~100 controllers and the MCP tools read via
     * {@code (JwtClaims) authentication.getPrincipal()}. Epic #867 keeps request-channel data
     * (the API key id, later the on-behalf-of principal) OUT of it on purpose — anything added
     * here becomes readable during permission resolution. Fail loudly on any drift.
     */
    @Test
    void recordComponentsAreExactlyThePrincipalContract() {
        assertThat(JwtClaims.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("userId", "email", "role", "roleId", "roleName",
                        "permissions", "organizationId", "platformAdmin");
    }
}
