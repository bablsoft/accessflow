package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.DbType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplaySchemaIncompatibleExceptionTest {

    @Test
    void dbTypeMismatchCarriesBothTypes() {
        var target = UUID.randomUUID();
        var ex = ReplaySchemaIncompatibleException.dbTypeMismatch(
                target, DbType.POSTGRESQL, DbType.MYSQL);

        assertThat(ex.reason()).isEqualTo(ReplaySchemaIncompatibleException.Reason.DB_TYPE_MISMATCH);
        assertThat(ex.targetDatasourceId()).isEqualTo(target);
        assertThat(ex.expectedDbType()).isEqualTo(DbType.POSTGRESQL);
        assertThat(ex.actualDbType()).isEqualTo(DbType.MYSQL);
        assertThat(ex.missingTables()).isEmpty();
    }

    @Test
    void missingTablesCarriesImmutableList() {
        var target = UUID.randomUUID();
        var ex = ReplaySchemaIncompatibleException.missingTables(target, List.of("public.users"));

        assertThat(ex.reason()).isEqualTo(ReplaySchemaIncompatibleException.Reason.MISSING_TABLES);
        assertThat(ex.missingTables()).containsExactly("public.users");
        assertThatThrownBy(() -> ex.missingTables().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void targetSchemaUnavailableHasNoDetails() {
        var target = UUID.randomUUID();
        var ex = ReplaySchemaIncompatibleException.targetSchemaUnavailable(target);

        assertThat(ex.reason())
                .isEqualTo(ReplaySchemaIncompatibleException.Reason.TARGET_SCHEMA_UNAVAILABLE);
        assertThat(ex.missingTables()).isEmpty();
        assertThat(ex.expectedDbType()).isNull();
        assertThat(ex.actualDbType()).isNull();
    }
}
