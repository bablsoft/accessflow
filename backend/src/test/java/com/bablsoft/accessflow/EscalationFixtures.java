package com.bablsoft.accessflow;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

/**
 * Raw-JDBC seeding for the #622 escalation and nudge scans.
 *
 * <p>Deliberately not JPA: the queries under test are native SQL, so the point of the fixture is to
 * put real rows in the real tables and let PostgreSQL judge the SQL. Going through the entities
 * would let a column the entity does not map (the {@code request_group_items} join key, say) stay
 * invisible — which is exactly the class of bug these tests exist to catch.
 */
public final class EscalationFixtures {

    private EscalationFixtures() {
    }

    private static final String ORG_NAME = "Escalation Fixture Org";

    /**
     * Removes only the rows these fixtures created, children first. Scoped by the marker
     * organization rather than truncating: the Testcontainers PostgreSQL instance is shared across
     * the whole suite, so a blanket {@code DELETE FROM query_requests} would either destroy another
     * test's data or trip a foreign key from a table this class knows nothing about.
     */
    public static void cleanup(JdbcTemplate jdbc) {
        var orgs = "(SELECT id FROM organizations WHERE name = '" + ORG_NAME + "')";
        jdbc.execute("DELETE FROM request_group_items WHERE group_id IN "
                + "(SELECT id FROM request_groups WHERE organization_id IN " + orgs + ")");
        jdbc.execute("DELETE FROM request_groups WHERE organization_id IN " + orgs);
        jdbc.execute("DELETE FROM api_requests WHERE organization_id IN " + orgs);
        jdbc.execute("DELETE FROM api_connectors WHERE organization_id IN " + orgs);
        jdbc.execute("DELETE FROM query_requests WHERE datasource_id IN "
                + "(SELECT id FROM datasources WHERE organization_id IN " + orgs + ")");
        jdbc.execute("DELETE FROM datasources WHERE organization_id IN " + orgs);
        jdbc.execute("DELETE FROM review_plans WHERE organization_id IN " + orgs);
        jdbc.execute("DELETE FROM users WHERE organization_id IN " + orgs);
        jdbc.execute("DELETE FROM organizations WHERE name = '" + ORG_NAME + "'");
    }

    public static UUID organization(JdbcTemplate jdbc) {
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name, slug) VALUES (?, ?, ?)",
                id, ORG_NAME, "escalation-" + id);
        return id;
    }

    public static UUID user(JdbcTemplate jdbc, UUID organizationId) {
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, organization_id, email, role) "
                        + "VALUES (?, ?, ?, 'REVIEWER'::user_role_type)",
                id, organizationId, id + "@example.com");
        return id;
    }

    /** A review plan with the two #622 knobs set; either may be null to switch that half off. */
    public static UUID reviewPlan(JdbcTemplate jdbc, UUID organizationId,
                                  Integer escalationAfterHours, Integer nudgeIntervalHours) {
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO review_plans (id, organization_id, name, escalation_after_hours, "
                        + "nudge_interval_hours) VALUES (?, ?, ?, ?, ?)",
                id, organizationId, "plan-" + id, escalationAfterHours, nudgeIntervalHours);
        return id;
    }

    public static UUID datasource(JdbcTemplate jdbc, UUID organizationId, UUID reviewPlanId) {
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO datasources (id, organization_id, name, db_type, host, port, "
                        + "database_name, username, password_encrypted, review_plan_id) "
                        + "VALUES (?, ?, ?, 'POSTGRESQL'::db_type, 'localhost', 5432, 'app', "
                        + "'app', 'enc', ?)",
                id, organizationId, "ds-" + id, reviewPlanId);
        return id;
    }

    public static UUID apiConnector(JdbcTemplate jdbc, UUID organizationId, UUID reviewPlanId) {
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO api_connectors (id, organization_id, name, protocol, base_url, "
                        + "review_plan_id) VALUES (?, ?, ?, 'REST'::api_protocol, "
                        + "'https://api.example.com', ?)",
                id, organizationId, "conn-" + id, reviewPlanId);
        return id;
    }

    /**
     * A {@code PENDING_REVIEW} query submitted at {@code createdAt}. There is no
     * {@code organization_id} on this table — the org is reached through the datasource.
     */
    public static UUID pendingQuery(JdbcTemplate jdbc, UUID datasourceId, UUID submittedBy,
                                    Instant createdAt, Instant lastNudgedAt) {
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO query_requests (id, datasource_id, submitted_by, sql_text, "
                        + "query_type, status, created_at, last_nudged_at) "
                        + "VALUES (?, ?, ?, 'SELECT 1', 'SELECT'::query_type, "
                        + "'PENDING_REVIEW'::query_status, ?, ?)",
                id, datasourceId, submittedBy, java.sql.Timestamp.from(createdAt),
                lastNudgedAt == null ? null : java.sql.Timestamp.from(lastNudgedAt));
        return id;
    }

    /** A {@code PENDING_REVIEW} API request submitted at {@code createdAt}. */
    public static UUID pendingApiRequest(JdbcTemplate jdbc, UUID organizationId, UUID connectorId,
                                         UUID submittedBy, Instant createdAt,
                                         Instant lastNudgedAt) {
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO api_requests (id, organization_id, connector_id, submitted_by, "
                        + "verb, request_path, status, created_at, last_nudged_at) "
                        + "VALUES (?, ?, ?, ?, 'GET', '/things', 'PENDING_REVIEW'::query_status, "
                        + "?, ?)",
                id, organizationId, connectorId, submittedBy, java.sql.Timestamp.from(createdAt),
                lastNudgedAt == null ? null : java.sql.Timestamp.from(lastNudgedAt));
        return id;
    }

    /**
     * A {@code PENDING_REVIEW} bundle with no members yet — add them with {@link #queryMember}.
     * No nudge cursor: {@code request_groups} has no {@code last_nudged_at}, because grouped
     * requests have no notification path for a reminder to travel down.
     */
    public static UUID pendingGroup(JdbcTemplate jdbc, UUID organizationId, UUID submittedBy,
                                    Instant createdAt) {
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO request_groups (id, organization_id, name, status, submitted_by, "
                        + "created_at) VALUES (?, ?, ?, "
                        + "'PENDING_REVIEW'::request_group_status, ?, ?)",
                id, organizationId, "group-" + id, submittedBy,
                java.sql.Timestamp.from(createdAt));
        return id;
    }

    public static void queryMember(JdbcTemplate jdbc, UUID groupId, int order, UUID datasourceId) {
        jdbc.update("INSERT INTO request_group_items (id, group_id, sequence_order, target_kind, "
                        + "datasource_id, sql_text, query_type) VALUES (?, ?, ?, "
                        + "'QUERY'::request_group_target_kind, ?, 'SELECT 1', 'SELECT'::query_type)",
                UUID.randomUUID(), groupId, order, datasourceId);
    }

    public static void apiMember(JdbcTemplate jdbc, UUID groupId, int order, UUID connectorId) {
        jdbc.update("INSERT INTO request_group_items (id, group_id, sequence_order, target_kind, "
                        + "api_connector_id, verb, request_path) VALUES (?, ?, ?, "
                        + "'API_CALL'::request_group_target_kind, ?, 'GET', '/things')",
                UUID.randomUUID(), groupId, order, connectorId);
    }
}
