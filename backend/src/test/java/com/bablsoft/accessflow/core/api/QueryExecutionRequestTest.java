package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryExecutionRequestTest {

    private final UUID datasourceId = UUID.randomUUID();

    @Test
    void allFieldsSetIsAccepted() {
        var request = new QueryExecutionRequest(datasourceId, "SELECT 1",
                QueryType.SELECT, 100, Duration.ofSeconds(5));

        assertThat(request.datasourceId()).isEqualTo(datasourceId);
        assertThat(request.sql()).isEqualTo("SELECT 1");
        assertThat(request.queryType()).isEqualTo(QueryType.SELECT);
        assertThat(request.maxRowsOverride()).isEqualTo(100);
        assertThat(request.statementTimeoutOverride()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void nullableOverridesAreAllowed() {
        var request = new QueryExecutionRequest(datasourceId, "SELECT 1",
                QueryType.SELECT, null, null);

        assertThat(request.maxRowsOverride()).isNull();
        assertThat(request.statementTimeoutOverride()).isNull();
    }

    @Test
    void columnMasksDefaultToEmptyAcrossConstructors() {
        var basic = new QueryExecutionRequest(datasourceId, "SELECT 1", QueryType.SELECT, null, null);
        assertThat(basic.columnMasks()).isEmpty();

        var withRestricted = new QueryExecutionRequest(datasourceId, "SELECT 1",
                QueryType.SELECT, null, null, java.util.List.of("public.t.c"));
        assertThat(withRestricted.columnMasks()).isEmpty();
        assertThat(withRestricted.restrictedColumns()).containsExactly("public.t.c");

        var transactional = new QueryExecutionRequest(datasourceId, "UPDATE t SET v=1",
                QueryType.UPDATE, null, null, java.util.List.of(), true,
                java.util.List.of("UPDATE t SET v=1"));
        assertThat(transactional.columnMasks()).isEmpty();
    }

    @Test
    void columnMasksAreRetainedAndCopied() {
        var directive = new ColumnMaskDirective("public.t.c",
                com.bablsoft.accessflow.core.api.MaskingStrategy.HASH, java.util.Map.of(),
                UUID.randomUUID());
        var request = new QueryExecutionRequest(datasourceId, "SELECT 1", QueryType.SELECT,
                null, null, java.util.List.of(), java.util.List.of(directive), java.util.List.of(),
                false, null);

        assertThat(request.columnMasks()).containsExactly(directive);
    }

    @Test
    void rowSecurityPredicatesDefaultEmptyAndAreRetained() {
        var basic = new QueryExecutionRequest(datasourceId, "SELECT 1", QueryType.SELECT, null, null);
        assertThat(basic.rowSecurityPredicates()).isEmpty();

        var directive = new RowSecurityDirective(UUID.randomUUID(), "public.t", "region",
                com.bablsoft.accessflow.core.api.RowSecurityOperator.EQUALS,
                java.util.List.of("EU"));
        var request = new QueryExecutionRequest(datasourceId, "SELECT 1", QueryType.SELECT,
                null, null, java.util.List.of(), java.util.List.of(), java.util.List.of(directive),
                false, null);
        assertThat(request.rowSecurityPredicates()).containsExactly(directive);
    }

    @Test
    void nullDatasourceIdIsRejected() {
        assertThatThrownBy(() -> new QueryExecutionRequest(null, "SELECT 1",
                QueryType.SELECT, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("datasourceId");
    }

    @Test
    void nullQueryTypeIsRejected() {
        assertThatThrownBy(() -> new QueryExecutionRequest(datasourceId, "SELECT 1",
                null, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("queryType");
    }

    @Test
    void nullSqlIsRejected() {
        assertThatThrownBy(() -> new QueryExecutionRequest(datasourceId, null,
                QueryType.SELECT, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sql must not be blank");
    }

    @Test
    void blankSqlIsRejected() {
        assertThatThrownBy(() -> new QueryExecutionRequest(datasourceId, "   ",
                QueryType.SELECT, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sql must not be blank");
    }

    @Test
    void zeroMaxRowsOverrideIsRejected() {
        assertThatThrownBy(() -> new QueryExecutionRequest(datasourceId, "SELECT 1",
                QueryType.SELECT, 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxRowsOverride must be positive");
    }

    @Test
    void negativeMaxRowsOverrideIsRejected() {
        assertThatThrownBy(() -> new QueryExecutionRequest(datasourceId, "SELECT 1",
                QueryType.SELECT, -1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxRowsOverride must be positive");
    }

    @Test
    void zeroTimeoutOverrideIsRejected() {
        assertThatThrownBy(() -> new QueryExecutionRequest(datasourceId, "SELECT 1",
                QueryType.SELECT, null, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("statementTimeoutOverride must be positive");
    }

    @Test
    void negativeTimeoutOverrideIsRejected() {
        assertThatThrownBy(() -> new QueryExecutionRequest(datasourceId, "SELECT 1",
                QueryType.SELECT, null, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("statementTimeoutOverride must be positive");
    }
}
