package com.bablsoft.accessflow.proxy.internal.dryrun;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryDryRunResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DryRunPlannerRegistryTest {

    private final DryRunPlanner postgres = stub(Set.of(DbType.POSTGRESQL));
    private final DryRunPlanner mysql = stub(Set.of(DbType.MYSQL, DbType.MARIADB));

    private final DryRunPlannerRegistry registry =
            new DryRunPlannerRegistry(List.of(postgres, mysql));

    @Test
    void resolvesRegisteredDialects() {
        assertThat(registry.forDbType(DbType.POSTGRESQL)).isSameAs(postgres);
        assertThat(registry.forDbType(DbType.MYSQL)).isSameAs(mysql);
        assertThat(registry.forDbType(DbType.MARIADB)).isSameAs(mysql);
    }

    @Test
    void unregisteredDialectResolvesToNull() {
        assertThat(registry.forDbType(DbType.CUSTOM)).isNull();
        assertThat(registry.forDbType(DbType.ORACLE)).isNull();
    }

    private static DryRunPlanner stub(Set<DbType> types) {
        return new DryRunPlanner() {
            @Override
            public Set<DbType> supportedTypes() {
                return types;
            }

            @Override
            public QueryDryRunResult plan(DryRunPlanRequest request) {
                return QueryDryRunResult.unsupported("stub");
            }
        };
    }
}
