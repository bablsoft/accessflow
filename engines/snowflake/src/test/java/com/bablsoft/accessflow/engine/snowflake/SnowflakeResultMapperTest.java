package com.bablsoft.accessflow.engine.snowflake;

import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.ColumnMasker;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.ResultColumn;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SnowflakeResultMapperTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),
            ZoneOffset.UTC);

    private final SnowflakeResultMapper mapper = new SnowflakeResultMapper();

    /** Mocked two-column result set with the given rows. */
    private static ResultSet resultSet(List<List<Object>> rows) throws SQLException {
        var metaData = mock(ResultSetMetaData.class);
        when(metaData.getColumnCount()).thenReturn(2);
        when(metaData.getColumnLabel(1)).thenReturn("ID");
        when(metaData.getColumnLabel(2)).thenReturn("EMAIL");
        when(metaData.getColumnType(1)).thenReturn(Types.NUMERIC);
        when(metaData.getColumnType(2)).thenReturn(Types.VARCHAR);
        when(metaData.getColumnTypeName(1)).thenReturn("NUMBER");
        when(metaData.getColumnTypeName(2)).thenReturn("VARCHAR");

        var resultSet = mock(ResultSet.class);
        when(resultSet.getMetaData()).thenReturn(metaData);
        var cursor = new int[]{-1};
        when(resultSet.next()).thenAnswer(inv -> ++cursor[0] < rows.size());
        when(resultSet.getObject(1)).thenAnswer(inv -> rows.get(cursor[0]).get(0));
        when(resultSet.getObject(2)).thenAnswer(inv -> rows.get(cursor[0]).get(1));
        return resultSet;
    }

    @Test
    void mapsColumnsAndRowsFromMetadata() throws SQLException {
        var result = mapper.materialize(
                resultSet(List.of(List.of(1, "a@x.io"), List.of(2, "b@x.io"))),
                10, CLOCK.instant(), CLOCK, List.of(), List.of());
        assertThat(result.columns()).containsExactly(
                new ResultColumn("ID", Types.NUMERIC, "NUMBER", false),
                new ResultColumn("EMAIL", Types.VARCHAR, "VARCHAR", false));
        assertThat(result.rows()).containsExactly(List.of(1, "a@x.io"), List.of(2, "b@x.io"));
        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.truncated()).isFalse();
        assertThat(result.appliedMaskingPolicyIds()).isEmpty();
    }

    @Test
    void truncatesAtMaxRowsWhenASentinelRowExists() throws SQLException {
        var result = mapper.materialize(
                resultSet(List.of(List.of(1, "a"), List.of(2, "b"), List.of(3, "c"))),
                2, CLOCK.instant(), CLOCK, List.of(), List.of());
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void masksColumnWithDirectiveAndFlagsIt() throws SQLException {
        var policyId = UUID.randomUUID();
        var result = mapper.materialize(
                resultSet(List.of(List.of(1, "alice@example.com"))),
                10, CLOCK.instant(), CLOCK, List.of(),
                List.of(new ColumnMaskDirective("email", MaskingStrategy.EMAIL, Map.of(), policyId)));
        assertThat(result.rows().get(0).get(1)).isEqualTo("a***@example.com");
        assertThat(result.columns().get(1).restricted()).isTrue();
        assertThat(result.appliedMaskingPolicyIds()).containsExactly(policyId);
    }

    @Test
    void appliesPartialMaskParams() throws SQLException {
        var result = mapper.materialize(
                resultSet(List.of(List.of(1, "123456789"))),
                10, CLOCK.instant(), CLOCK, List.of(),
                List.of(new ColumnMaskDirective("email", MaskingStrategy.PARTIAL,
                        Map.of("visible_suffix", "2"), UUID.randomUUID())));
        assertThat(result.rows().get(0).get(1)).isEqualTo("*******89");
    }

    @Test
    void restrictedColumnWithoutDirectiveIsFullyMasked() throws SQLException {
        var result = mapper.materialize(
                resultSet(List.of(List.of(1, "secret"))),
                10, CLOCK.instant(), CLOCK, List.of("email"), List.of());
        assertThat(result.rows().get(0).get(1)).isEqualTo(ColumnMasker.FULL_MASK);
        assertThat(result.columns().get(1).restricted()).isTrue();
        assertThat(result.appliedMaskingPolicyIds()).isEmpty();
    }

    @Test
    void directiveWinsOverRestrictedEntryForTheSameColumn() throws SQLException {
        var result = mapper.materialize(
                resultSet(List.of(List.of(1, "alice@example.com"))),
                10, CLOCK.instant(), CLOCK, List.of("email"),
                List.of(new ColumnMaskDirective("email", MaskingStrategy.EMAIL, Map.of(),
                        UUID.randomUUID())));
        assertThat(result.rows().get(0).get(1)).isEqualTo("a***@example.com");
    }

    @Test
    void matchesMaskRefsByLastSegmentCaseInsensitively() throws SQLException {
        var result = mapper.materialize(
                resultSet(List.of(List.of(1, "secret"))),
                10, CLOCK.instant(), CLOCK, List.of("public.users.EMAIL"), List.of());
        assertThat(result.rows().get(0).get(1)).isEqualTo(ColumnMasker.FULL_MASK);
    }

    @Test
    void nullCellsPassThroughUnmasked() throws SQLException {
        var rows = new java.util.ArrayList<List<Object>>();
        rows.add(java.util.Arrays.asList(1, null));
        var result = mapper.materialize(resultSet(rows),
                10, CLOCK.instant(), CLOCK, List.of("email"), List.of());
        assertThat(result.rows().get(0).get(1)).isNull();
    }

    @Test
    void unmatchedRefsMaskNothing() throws SQLException {
        var result = mapper.materialize(
                resultSet(List.of(List.of(1, "plain"))),
                10, CLOCK.instant(), CLOCK, List.of("other_col"),
                List.of(new ColumnMaskDirective("also_other", MaskingStrategy.FULL, Map.of(),
                        UUID.randomUUID())));
        assertThat(result.rows().get(0)).containsExactly(1, "plain");
        assertThat(result.columns()).allSatisfy(c -> assertThat(c.restricted()).isFalse());
    }
}
