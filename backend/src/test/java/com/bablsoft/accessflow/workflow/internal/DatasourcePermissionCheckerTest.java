package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import com.bablsoft.accessflow.core.api.QueryType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DatasourcePermissionCheckerTest {

    @Test
    void hasCapabilityMapsQueryTypeToFlag() {
        assertThat(DatasourcePermissionChecker.hasCapability(
                perm(true, false, false), QueryType.SELECT)).isTrue();
        assertThat(DatasourcePermissionChecker.hasCapability(
                perm(false, true, false), QueryType.INSERT)).isTrue();
        assertThat(DatasourcePermissionChecker.hasCapability(
                perm(false, true, false), QueryType.UPDATE)).isTrue();
        assertThat(DatasourcePermissionChecker.hasCapability(
                perm(false, true, false), QueryType.DELETE)).isTrue();
        assertThat(DatasourcePermissionChecker.hasCapability(
                perm(false, false, true), QueryType.DDL)).isTrue();
        assertThat(DatasourcePermissionChecker.hasCapability(
                perm(true, true, true), QueryType.OTHER)).isFalse();
        assertThat(DatasourcePermissionChecker.hasCapability(
                perm(false, true, true), QueryType.SELECT)).isFalse();
    }

    @Test
    void noAllowListMeansEverythingAllowed() {
        var rejected = DatasourcePermissionChecker.rejectedTables(
                perm(List.of(), List.of()), Set.of("orders", "users"));
        assertThat(rejected).isEmpty();
    }

    @Test
    void emptyReferencedTablesNeverRejected() {
        var rejected = DatasourcePermissionChecker.rejectedTables(
                perm(List.of("public"), List.of("orders")), Set.of());
        assertThat(rejected).isEmpty();
    }

    @Test
    void tableAllowListAdmitsListedTablesOnly() {
        var rejected = DatasourcePermissionChecker.rejectedTables(
                perm(List.of(), List.of("orders")), Set.of("orders", "secrets"));
        assertThat(rejected).containsExactly("secrets");
    }

    @Test
    void schemaAllowListAdmitsSchemaQualifiedTables() {
        var rejected = DatasourcePermissionChecker.rejectedTables(
                perm(List.of("public"), List.of()), Set.of("public.orders", "private.secrets"));
        assertThat(rejected).containsExactly("private.secrets");
    }

    @Test
    void normalizeStripsQuotesAndLowercases() {
        assertThat(DatasourcePermissionChecker.normalizeList(List.of("\"Public\"", "`Orders`", "[x]")))
                .containsExactly("public", "orders", "x");
        assertThat(DatasourcePermissionChecker.normalizeList(null)).isEmpty();
        assertThat(DatasourcePermissionChecker.normalizeList(java.util.Arrays.asList((String) null)))
                .isEmpty();
    }

    @Test
    void primitiveHasCapabilityOverloadMatchesViewOverload() {
        assertThat(DatasourcePermissionChecker.hasCapability(true, false, false,
                QueryType.SELECT)).isTrue();
        assertThat(DatasourcePermissionChecker.hasCapability(false, true, false,
                QueryType.DELETE)).isTrue();
        assertThat(DatasourcePermissionChecker.hasCapability(false, false, true,
                QueryType.DDL)).isTrue();
        assertThat(DatasourcePermissionChecker.hasCapability(true, false, false,
                QueryType.UPDATE)).isFalse();
        assertThat(DatasourcePermissionChecker.hasCapability(true, true, true,
                QueryType.OTHER)).isFalse();
    }

    @Test
    void listRejectedTablesOverloadMatchesViewOverload() {
        assertThat(DatasourcePermissionChecker.rejectedTables(null, null, Set.of("orders")))
                .isEmpty();
        assertThat(DatasourcePermissionChecker.rejectedTables(List.of("public"), List.of("orders"),
                Set.of("orders", "public.users", "private.secrets")))
                .containsExactly("private.secrets");
    }

    private DatasourceUserPermissionView perm(boolean canRead, boolean canWrite, boolean canDdl) {
        return new DatasourceUserPermissionView(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), canRead, canWrite, canDdl, false,
                List.of(), List.of(), List.of(), null);
    }

    private DatasourceUserPermissionView perm(List<String> allowedSchemas, List<String> allowedTables) {
        return new DatasourceUserPermissionView(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), true, true, true, true,
                allowedSchemas, allowedTables, List.of(), null);
    }
}
