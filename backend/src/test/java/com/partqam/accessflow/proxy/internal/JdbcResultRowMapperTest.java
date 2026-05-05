package com.partqam.accessflow.proxy.internal;

import com.partqam.accessflow.core.api.DbType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcResultRowMapperTest {

    private final JdbcResultRowMapper mapper = new JdbcResultRowMapper();

    @Test
    void emptyResultProducesEmptyRowsWithDescribedColumns() throws SQLException {
        var metadata = mock(ResultSetMetaData.class);
        when(metadata.getColumnCount()).thenReturn(2);
        when(metadata.getColumnLabel(1)).thenReturn("id");
        when(metadata.getColumnLabel(2)).thenReturn("name");
        when(metadata.getColumnType(1)).thenReturn(Types.INTEGER);
        when(metadata.getColumnType(2)).thenReturn(Types.VARCHAR);
        when(metadata.getColumnTypeName(1)).thenReturn("int4");
        when(metadata.getColumnTypeName(2)).thenReturn("varchar");

        var rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.next()).thenReturn(false);

        var result = mapper.materialize(rs, 100, DbType.POSTGRESQL, Duration.ofMillis(1));

        assertThat(result.columns()).extracting("name").containsExactly("id", "name");
        assertThat(result.rows()).isEmpty();
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void nullValuesAreMappedToNull() throws SQLException {
        var metadata = singleColumnMeta("v", Types.INTEGER, "int4");
        var rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(null);

        var result = mapper.materialize(rs, 10, DbType.POSTGRESQL, Duration.ZERO);

        assertThat(result.rows()).containsExactly(java.util.Collections.singletonList(null));
    }

    @Test
    void numericValueMappedToBigDecimal() throws SQLException {
        var metadata = singleColumnMeta("v", Types.NUMERIC, "numeric");
        var rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(new BigDecimal("12345.6789"));
        when(rs.getBigDecimal(1)).thenReturn(new BigDecimal("12345.6789"));
        when(rs.wasNull()).thenReturn(false);

        var result = mapper.materialize(rs, 10, DbType.POSTGRESQL, Duration.ZERO);

        assertThat(result.rows().getFirst().getFirst()).isEqualTo(new BigDecimal("12345.6789"));
    }

    @Test
    void timestampMappedToOffsetDateTime() throws SQLException {
        var metadata = singleColumnMeta("v", Types.TIMESTAMP, "timestamp");
        var rs = mock(ResultSet.class);
        var ts = Timestamp.from(OffsetDateTime.parse("2026-05-05T12:00:00Z").toInstant());
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(ts);
        when(rs.getTimestamp(1)).thenReturn(ts);
        when(rs.wasNull()).thenReturn(false);

        var result = mapper.materialize(rs, 10, DbType.POSTGRESQL, Duration.ZERO);

        assertThat(result.rows().getFirst().getFirst())
                .isEqualTo(OffsetDateTime.parse("2026-05-05T12:00:00Z").withOffsetSameInstant(ZoneOffset.UTC));
    }

    @Test
    void byteArrayMappedToBase64String() throws SQLException {
        var metadata = singleColumnMeta("v", Types.VARBINARY, "bytea");
        var rs = mock(ResultSet.class);
        var bytes = new byte[] {1, 2, 3};
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(bytes);
        when(rs.getBytes(1)).thenReturn(bytes);
        when(rs.wasNull()).thenReturn(false);

        var result = mapper.materialize(rs, 10, DbType.POSTGRESQL, Duration.ZERO);

        assertThat(result.rows().getFirst().getFirst()).isEqualTo("base64:AQID");
    }

    @Test
    void postgresUuidMappedToString() throws SQLException {
        var metadata = singleColumnMeta("v", Types.OTHER, "uuid");
        var rs = mock(ResultSet.class);
        var uuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(uuid);
        when(rs.getObject(1, UUID.class)).thenReturn(uuid);
        when(rs.wasNull()).thenReturn(false);

        var result = mapper.materialize(rs, 10, DbType.POSTGRESQL, Duration.ZERO);

        assertThat(result.rows().getFirst().getFirst()).isEqualTo(uuid.toString());
    }

    @Test
    void jsonbColumnPassesThroughAsString() throws SQLException {
        var metadata = singleColumnMeta("v", Types.OTHER, "jsonb");
        var rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn("{\"a\":1}");
        when(rs.getString(1)).thenReturn("{\"a\":1}");
        when(rs.wasNull()).thenReturn(false);

        var result = mapper.materialize(rs, 10, DbType.POSTGRESQL, Duration.ZERO);

        assertThat(result.rows().getFirst().getFirst()).isEqualTo("{\"a\":1}");
    }

    @Test
    void columnLabelFallsBackToColumnNameWhenBlank() throws SQLException {
        var metadata = mock(ResultSetMetaData.class);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn(" ");
        when(metadata.getColumnName(1)).thenReturn("real_name");
        when(metadata.getColumnType(1)).thenReturn(Types.INTEGER);
        when(metadata.getColumnTypeName(1)).thenReturn("int4");
        var rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.next()).thenReturn(false);

        var result = mapper.materialize(rs, 10, DbType.POSTGRESQL, Duration.ZERO);

        assertThat(result.columns().getFirst().name()).isEqualTo("real_name");
    }

    private static ResultSetMetaData singleColumnMeta(String name, int type, String typeName)
            throws SQLException {
        var metadata = mock(ResultSetMetaData.class);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn(name);
        when(metadata.getColumnType(1)).thenReturn(type);
        when(metadata.getColumnTypeName(1)).thenReturn(typeName);
        return metadata;
    }
}
