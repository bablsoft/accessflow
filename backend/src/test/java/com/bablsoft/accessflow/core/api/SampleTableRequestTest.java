package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SampleTableRequestTest {

    private final UUID datasourceId = UUID.randomUUID();

    @Test
    void allFieldsSetIsAccepted() {
        var request = new SampleTableRequest(datasourceId, "public", "users",
                50, Duration.ofSeconds(5));

        assertThat(request.datasourceId()).isEqualTo(datasourceId);
        assertThat(request.schema()).isEqualTo("public");
        assertThat(request.table()).isEqualTo("users");
        assertThat(request.maxRowsOverride()).isEqualTo(50);
        assertThat(request.statementTimeoutOverride()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void nullSchemaAndNullableOverridesAreAllowed() {
        var request = new SampleTableRequest(datasourceId, null, "users", null, null);

        assertThat(request.schema()).isNull();
        assertThat(request.maxRowsOverride()).isNull();
        assertThat(request.statementTimeoutOverride()).isNull();
    }

    @Test
    void directiveCollectionsDefaultToEmptyAndAreRetained() {
        var basic = new SampleTableRequest(datasourceId, "public", "users", null, null);
        assertThat(basic.restrictedColumns()).isEmpty();
        assertThat(basic.columnMasks()).isEmpty();
        assertThat(basic.rowSecurityPredicates()).isEmpty();

        var mask = new ColumnMaskDirective("public.users.email", MaskingStrategy.EMAIL,
                Map.of(), UUID.randomUUID());
        var predicate = new RowSecurityDirective(UUID.randomUUID(), "public.users", "region",
                RowSecurityOperator.EQUALS, List.of("EU"));
        var full = new SampleTableRequest(datasourceId, "public", "users",
                List.of("public.users.ssn"), List.of(mask), List.of(predicate), 10, null);

        assertThat(full.restrictedColumns()).containsExactly("public.users.ssn");
        assertThat(full.columnMasks()).containsExactly(mask);
        assertThat(full.rowSecurityPredicates()).containsExactly(predicate);
    }

    @Test
    void nullDatasourceIdIsRejected() {
        assertThatThrownBy(() -> new SampleTableRequest(null, "public", "users", null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("datasourceId");
    }

    @Test
    void nullTableIsRejected() {
        assertThatThrownBy(() -> new SampleTableRequest(datasourceId, "public", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("table must not be blank");
    }

    @Test
    void blankTableIsRejected() {
        assertThatThrownBy(() -> new SampleTableRequest(datasourceId, "public", "   ", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("table must not be blank");
    }

    @Test
    void zeroMaxRowsOverrideIsRejected() {
        assertThatThrownBy(() -> new SampleTableRequest(datasourceId, "public", "users", 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxRowsOverride must be positive");
    }

    @Test
    void negativeMaxRowsOverrideIsRejected() {
        assertThatThrownBy(() -> new SampleTableRequest(datasourceId, "public", "users", -1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxRowsOverride must be positive");
    }

    @Test
    void zeroTimeoutOverrideIsRejected() {
        assertThatThrownBy(() -> new SampleTableRequest(datasourceId, "public", "users",
                null, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("statementTimeoutOverride must be positive");
    }

    @Test
    void negativeTimeoutOverrideIsRejected() {
        assertThatThrownBy(() -> new SampleTableRequest(datasourceId, "public", "users",
                null, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("statementTimeoutOverride must be positive");
    }
}
