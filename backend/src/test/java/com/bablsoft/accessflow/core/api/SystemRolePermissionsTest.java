package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

class SystemRolePermissionsTest {

    @Test
    void everySystemRoleHasAPermissionSet() {
        for (var role : UserRoleType.values()) {
            assertThat(SystemRolePermissions.of(role)).isNotNull();
        }
    }

    @Test
    void adminHoldsTheEntireCatalog() {
        assertThat(SystemRolePermissions.of(UserRoleType.ADMIN))
                .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(Permission.class));
    }

    @Test
    void readonlyMatchesMatrix() {
        assertThat(SystemRolePermissions.of(UserRoleType.READONLY))
                .containsExactlyInAnyOrder(Permission.QUERY_SUBMIT_SELECT);
    }

    @Test
    void analystMatchesMatrix() {
        assertThat(SystemRolePermissions.of(UserRoleType.ANALYST))
                .containsExactlyInAnyOrder(Permission.QUERY_SUBMIT_SELECT,
                        Permission.QUERY_SUBMIT_DML);
    }

    @Test
    void reviewerMatchesMatrix() {
        assertThat(SystemRolePermissions.of(UserRoleType.REVIEWER))
                .containsExactlyInAnyOrder(
                        Permission.QUERY_SUBMIT_SELECT,
                        Permission.QUERY_SUBMIT_DML,
                        Permission.QUERY_VIEW_ALL,
                        Permission.QUERY_REVIEW,
                        Permission.ACCESS_REQUEST_REVIEW,
                        Permission.API_REQUEST_REVIEW,
                        Permission.ERASURE_REVIEW,
                        Permission.ATTESTATION_REVIEW);
    }

    @Test
    void auditorMatchesMatrix() {
        assertThat(SystemRolePermissions.of(UserRoleType.AUDITOR))
                .containsExactlyInAnyOrder(
                        Permission.COMPLIANCE_REPORT_VIEW,
                        Permission.ATTESTATION_EVIDENCE_EXPORT,
                        Permission.BREAK_GLASS_VIEW,
                        Permission.ANOMALY_VIEW);
    }

    @Test
    void nonAdminRolesNeverHoldAdminOnlyPermissions() {
        for (var role : UserRoleType.values()) {
            if (role == UserRoleType.ADMIN) {
                continue;
            }
            assertThat(SystemRolePermissions.of(role))
                    .doesNotContain(Permission.REVIEW_OVERRIDE, Permission.QUERY_ADMIN,
                            Permission.ROLE_MANAGE, Permission.USER_MANAGE);
        }
    }

    @Test
    void setsAreImmutable() {
        var set = SystemRolePermissions.of(UserRoleType.READONLY);
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> set.add(Permission.QUERY_ADMIN));
    }

    @Test
    void everyPermissionCarriesAGroup() {
        for (var permission : Permission.values()) {
            assertThat(permission.group()).isNotNull();
        }
    }
}
