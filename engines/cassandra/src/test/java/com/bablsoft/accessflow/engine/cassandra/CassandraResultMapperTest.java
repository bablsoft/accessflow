package com.bablsoft.accessflow.engine.cassandra;

import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.ColumnMasker;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.engine.cassandra.CassandraResultMapper.CqlColumn;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CassandraResultMapperTest {

    private final CassandraResultMapper mapper = new CassandraResultMapper();

    private static final List<CqlColumn> COLUMNS = List.of(
            new CqlColumn("id", "int"), new CqlColumn("email", "text"));

    @Test
    void materializesColumnsAndRows() {
        var result = mapper.materialize(COLUMNS,
                List.of(List.of(1, "a@x.com"), List.of(2, "b@x.com")),
                10, Duration.ZERO, List.of(), List.of());
        assertThat(result.columns()).extracting("name").containsExactly("id", "email");
        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.truncated()).isFalse();
        assertThat(result.rows().get(0)).containsExactly(1, "a@x.com");
    }

    @Test
    void flagsTruncationAndTrimsToMaxRows() {
        var result = mapper.materialize(COLUMNS,
                List.of(List.of(1, "a"), List.of(2, "b"), List.of(3, "c")),
                2, Duration.ZERO, List.of(), List.of());
        assertThat(result.truncated()).isTrue();
        assertThat(result.rowCount()).isEqualTo(2);
    }

    @Test
    void appliesRestrictedColumnAsFullMask() {
        var result = mapper.materialize(COLUMNS, List.of(List.of(1, "a@x.com")),
                10, Duration.ZERO, List.of("email"), List.of());
        assertThat(result.columns().get(1).restricted()).isTrue();
        assertThat(result.rows().get(0).get(1)).isEqualTo(ColumnMasker.FULL_MASK);
    }

    @Test
    void appliesExplicitMaskDirectiveWithTableColumnPrecedence() {
        var policy = UUID.randomUUID();
        var directive = new ColumnMaskDirective("users.email", MaskingStrategy.EMAIL, Map.of(), policy);
        var result = mapper.materialize(COLUMNS, List.of(List.of(1, "alice@x.com")),
                10, Duration.ZERO, List.of(), List.of(directive));
        assertThat(result.rows().get(0).get(1)).isEqualTo("a***@x.com");
        assertThat(result.appliedMaskingPolicyIds()).containsExactly(policy);
    }

    @Test
    void keepsNullCellsNull() {
        var result = mapper.materialize(COLUMNS, List.of(java.util.Arrays.asList(1, null)),
                10, Duration.ZERO, List.of("email"), List.of());
        assertThat(result.rows().get(0).get(1)).isNull();
    }
}
