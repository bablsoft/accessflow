package com.bablsoft.accessflow;

import com.bablsoft.accessflow.core.api.SystemRolePermissions;
import com.bablsoft.accessflow.core.api.UserRoleType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

/**
 * Restores the V114 global system-role seed rows after a test wipes them. Integration tests that
 * clean the shared Testcontainers database with {@code TRUNCATE TABLE organizations CASCADE}
 * truncate every transitively-referencing table — including {@code roles} (its
 * {@code organization_id} FK makes the whole table a cascade target, system rows included). Any
 * later context that resolves a role name (row-security reveal roles, API-masking reveal roles,
 * requester-role routing conditions) then fails on the missing seed. Call
 * {@link #reseedSystemRoles(JdbcTemplate)} immediately after such a truncate so suite ordering
 * never matters.
 *
 * <p>Ids and descriptions mirror {@code V114__create_roles_and_permissions.sql}; the permission
 * sets come from {@link SystemRolePermissions}, which the seed-parity test guarantees is identical
 * to the migration. Inserts are idempotent ({@code ON CONFLICT DO NOTHING}).
 */
public final class TestSystemRoleSeeder {

    private static final Map<UserRoleType, String> SEED_IDS = Map.of(
            UserRoleType.ADMIN, "c0000000-0000-0000-0000-000000000001",
            UserRoleType.REVIEWER, "c0000000-0000-0000-0000-000000000002",
            UserRoleType.ANALYST, "c0000000-0000-0000-0000-000000000003",
            UserRoleType.READONLY, "c0000000-0000-0000-0000-000000000004",
            UserRoleType.AUDITOR, "c0000000-0000-0000-0000-000000000005");

    private static final Map<UserRoleType, String> DESCRIPTIONS = Map.of(
            UserRoleType.ADMIN, "Full administrative access to every AccessFlow capability.",
            UserRoleType.REVIEWER, "Submits SELECT/DML queries and reviews queries, access requests, "
                    + "API requests, erasure requests, and attestation items.",
            UserRoleType.ANALYST, "Submits SELECT and DML queries and views own history.",
            UserRoleType.READONLY, "Submits SELECT queries only.",
            UserRoleType.AUDITOR, "Read-only compliance role: compliance reports, attestation "
                    + "evidence, break-glass events, and anomalies.");

    private TestSystemRoleSeeder() {
    }

    public static void reseedSystemRoles(JdbcTemplate jdbcTemplate) {
        for (var role : UserRoleType.values()) {
            var id = SEED_IDS.get(role);
            jdbcTemplate.update("""
                    INSERT INTO roles (id, organization_id, name, description, is_system)
                    VALUES (?::uuid, NULL, ?, ?, TRUE)
                    ON CONFLICT (id) DO NOTHING""",
                    id, role.name(), DESCRIPTIONS.get(role));
            for (var permission : SystemRolePermissions.of(role)) {
                jdbcTemplate.update("""
                        INSERT INTO role_permissions (role_id, permission)
                        VALUES (?::uuid, ?)
                        ON CONFLICT DO NOTHING""",
                        id, permission.name());
            }
        }
    }
}
