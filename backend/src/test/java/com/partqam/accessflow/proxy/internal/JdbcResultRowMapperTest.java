package com.partqam.accessflow.proxy.internal;

import com.partqam.accessflow.core.api.DbType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
    void columnLabelFallsBackToColumnNameWhenNull() throws SQLException {
        var metadata = mock(ResultSetMetaData.class);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn(null);
        when(metadata.getColumnName(1)).thenReturn("named");
        when(metadata.getColumnType(1)).thenReturn(Types.INTEGER);
        when(metadata.getColumnTypeName(1)).thenReturn("int4");
        var rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.next()).thenReturn(false);

        var result = mapper.materialize(rs, 10, DbType.POSTGRESQL, Duration.ZERO);

        assertThat(result.columns().getFirst().name()).isEqualTo("named");
    }

    @Test
    void jsonColumnPassesThroughAsString() throws SQLException {
        var metadata = singleColumnMeta("v", Types.OTHER, "json");
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
    void unknownJdbcTypeWithBooleanRawFallsThrough() throws SQLException {
        var metadata = singleColumnMeta("v", Types.SQLXML, "xml");
        var rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(Boolean.TRUE);
        when(rs.wasNull()).thenReturn(false);

        var result = mapper.materialize(rs, 10, DbType.POSTGRESQL, Duration.ZERO);

        assertThat(result.rows().getFirst().getFirst()).isEqualTo(Boolean.TRUE);
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

    @Test
    void booleanColumnMappedToBoolean() throws SQLException {
        var metadata = singleColumnMeta("v", Types.BOOLEAN, "bool");
        var rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(Boolean.TRUE);
        when(rs.getBoolean(1)).thenReturn(true);
        when(rs.wasNull()).thenReturn(false);

        var result = mapper.materialize(rs, 10, DbType.POSTGRESQL, Duration.ZERO);

        assertThat(result.rows().getFirst().getFirst()).isEqualTo(true);
    }

    @Test
    void bitColumnMappedToBoolean() throws SQLException {
        var metadata = singleColumnMeta("v", Types.BIT, "bit");
        var rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(Boolean.FALSE);
        when(rs.getBoolean(1)).thenReturn(false);
        when(rs.wasNull()).thenReturn(false);

        var result = mapper.materialize(rs, 10, DbType.POSTGRESQL, Duration.ZERO);

        assertThat(result.rows().getFirst().getFirst()).isEqualTo(false);
    }

    @Test
    void bigintColumnMappedToLong() throws SQLException {
        var metadata = singleColumnMeta("v", Types.BIGINT, "int8");
        var rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(42L);
        when(rs.getLong(1)).thenReturn(42L);
        when(rs.wasNull()).thenReturn(false);

        var result = mapper.materialize(rs, 10, DbType.POSTGRESQL, Duration.ZERO);

        assertThat(result.rows().getFirst().getFirst()).isEqualTo(42L);
    }

    @Test
    void floatColumnMappedToFloat() throws SQLException {
        var metadata = singleColumnMeta("v", Types.REAL, "float4");
        var rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(1.5f);
        when(rs.getFloat(1)).thenReturn(1.5f);
        when(rs.wasNull()).thenReturn(false);

        var result = mapper.materialize(rs, 10, DbType.POSTGRESQL, Duration.ZERO);

        assertThat(result.rows().getFirst().getFirst()).isEqualTo(1.5f);
    }

    @Test
    void doubleColumnMappedToDouble() throws SQLException {
        var metadata = singleColumnMeta("v", Types.DOUBLE, "float8");
        var rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(2.5d);
        when(rs.getDouble(1)).thenReturn(2.5d);
        when(rs.wasNull()).thenReturn(false);

        var result = mapper.materialize(rs, 10, DbType.POSTGRESQL, Duration.ZERO);

        assertThat(result.rows().getFirst().getFirst()).isEqualTo(2.5d);
    }

    @Test
    void dateColumnMappedToOffsetDateTimeAtStartOfDayUtc() throws SQLException {
        var metadata = singleColumnMeta("v", Types.DATE, "date");
        var rs = mock(ResultSet.class);
        var date = Date.valueOf(LocalDate.of(2026, 5, 5));
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(date);
        when(rs.getDate(1)).thenReturn(date);
        when(rs.wasNull()).thenReturn(false);

        var result = mapper.materialize(rs, 10, DbType.POSTGRESQL, Duration.ZERO);

        assertThat(result.rows().getFirst().getFirst())
                .isEqualTo(LocalDate.of(2026, 5, 5).atStartOfDay().atOffset(ZoneOffset.UTC));
    }

    @Test
    void timeColumnMappedToOffsetDateTime() throws SQLException {
        var metadata = singleColumnMeta("v", Types.TIME, "time");
        var rs = mock(ResultSet.class);
        var time = Time.valueOf("12:34:56");
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(time);
        when(rs.getTime(1)).thenReturn(time);
        when(rs.wasNull()).thenReturn(false);

        var result = mapper.materialize(rs, 10, DbType.POSTGRESQL, Duration.ZERO);

        var expected = time.toLocalTime().atDate(LocalDate.EPOCH).atOffset(ZoneOffset.UTC);
        assertThat(result.rows().getFirst().getFirst()).isEqualTo(expected);
    }

    @Test
    void varcharColumnMappedToString() throws SQLException {
        var metadata = singleColumnMeta("v", Types.VARCHAR, "varchar");
        var rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn("hello");
        when(rs.getString(1)).thenReturn("hello");
        when(rs.wasNull()).thenReturn(false);

        var result = mapper.materialize(rs, 10, DbType.POSTGRESQL, Duration.ZERO);

        assertThat(result.rows().getFirst().getFirst()).isEqualTo("hello");
    }

    @Test
    void otherWithNullTypeNameFallsBackToString() throws SQLException {
        var metadata = singleColumnMeta("v", Types.OTHER, null);
        var rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn("payload");
        when(rs.wasNull()).thenReturn(false);

        var result = mapper.materialize(rs, 10, DbType.POSTGRESQL, Duration.ZERO);

        assertThat(result.rows().getFirst().getFirst()).isEqualTo("payload");
    }

    @Test
    void otherWithUnknownTypeOnMysqlFallsBackToString() throws SQLException {
        var metadata = singleColumnMeta("v", Types.OTHER, "geometry");
        var rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn("POINT(1 2)");
        when(rs.wasNull()).thenReturn(false);

        var result = mapper.materialize(rs, 10, DbType.MYSQL, Duration.ZERO);

        assertThat(result.rows().getFirst().getFirst()).isEqualTo("POINT(1 2)");
    }

    @Test
    void otherUuidOnMysqlIsNotTreatedAsUuid() throws SQLException {
        var metadata = singleColumnMeta("v", Types.OTHER, "uuid");
        var rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn("opaque");
        when(rs.wasNull()).thenReturn(false);

        var result = mapper.materialize(rs, 10, DbType.MYSQL, Duration.ZERO);

        assertThat(result.rows().getFirst().getFirst()).isEqualTo("opaque");
    }

    @Test
    void postgresUuidNullValueReturnsNull() throws SQLException {
        var metadata = singleColumnMeta("v", Types.OTHER, "uuid");
        var rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(new Object());
        when(rs.getObject(1, UUID.class)).thenReturn(null);
        when(rs.wasNull()).thenReturn(false);

        var result = mapper.materialize(rs, 10, DbType.POSTGRESQL, Duration.ZERO);

        assertThat(result.rows().getFirst().getFirst()).isNull();
    }

    @Test
    void unknownJdbcTypeFallsBackToString() throws SQLException {
        var metadata = singleColumnMeta("v", Types.SQLXML, "xml");
        var rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn("<root/>");
        when(rs.wasNull()).thenReturn(false);

        var result = mapper.materialize(rs, 10, DbType.POSTGRESQL, Duration.ZERO);

        assertThat(result.rows().getFirst().getFirst()).isEqualTo("<root/>");
    }

    @Test
    void unknownJdbcTypeWithNumberFallsThrough() throws SQLException {
        var metadata = singleColumnMeta("v", Types.SQLXML, "xml");
        var rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(99);
        when(rs.wasNull()).thenReturn(false);

        var result = mapper.materialize(rs, 10, DbType.POSTGRESQL, Duration.ZERO);

        assertThat(result.rows().getFirst().getFirst()).isEqualTo(99);
    }

    @Test
    void unknownJdbcTypeWithBigDecimalFallsThrough() throws SQLException {
        var metadata = singleColumnMeta("v", Types.SQLXML, "xml");
        var rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(new BigDecimal("3.14"));
        when(rs.wasNull()).thenReturn(false);

        var result = mapper.materialize(rs, 10, DbType.POSTGRESQL, Duration.ZERO);

        assertThat(result.rows().getFirst().getFirst()).isEqualTo(new BigDecimal("3.14"));
    }

    @Test
    void unknownJdbcTypeWithRawBytesEncodedAsBase64() throws SQLException {
        var metadata = singleColumnMeta("v", Types.SQLXML, "xml");
        var rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(new byte[] {0x10, 0x20});
        when(rs.wasNull()).thenReturn(false);

        var result = mapper.materialize(rs, 10, DbType.POSTGRESQL, Duration.ZERO);

        assertThat(result.rows().getFirst().getFirst()).isEqualTo("base64:ECA=");
    }

    @Test
    void unknownJdbcTypeWithRawUuidStringified() throws SQLException {
        var metadata = singleColumnMeta("v", Types.SQLXML, "xml");
        var rs = mock(ResultSet.class);
        var uuid = UUID.fromString("00000000-0000-0000-0000-000000000123");
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(uuid);
        when(rs.wasNull()).thenReturn(false);

        var result = mapper.materialize(rs, 10, DbType.POSTGRESQL, Duration.ZERO);

        assertThat(result.rows().getFirst().getFirst()).isEqualTo(uuid.toString());
    }

    @Test
    void unknownJdbcTypeWithUnrecognizedObjectFallsBackToToString() throws SQLException {
        var metadata = singleColumnMeta("v", Types.SQLXML, "xml");
        var rs = mock(ResultSet.class);
        var custom = new java.awt.Point(3, 4);
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(custom);
        when(rs.wasNull()).thenReturn(false);

        var result = mapper.materialize(rs, 10, DbType.POSTGRESQL, Duration.ZERO);

        assertThat(result.rows().getFirst().getFirst()).isEqualTo(custom.toString());
    }

    @Test
    void wasNullAfterRawValueYieldsNull() throws SQLException {
        var metadata = singleColumnMeta("v", Types.INTEGER, "int4");
        var rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(0);
        when(rs.wasNull()).thenReturn(true);

        var result = mapper.materialize(rs, 10, DbType.POSTGRESQL, Duration.ZERO);

        assertThat(result.rows().getFirst().getFirst()).isNull();
    }

    @Test
    void arrayWithMixedElementsIsConvertedAndFreed() throws SQLException {
        var metadata = singleColumnMeta("v", Types.ARRAY, "_int4");
        var rs = mock(ResultSet.class);
        var array = mock(Array.class);
        var uuid = UUID.fromString("00000000-0000-0000-0000-000000000777");
        Object[] elements = {1, null, new byte[] {0x01}, uuid, "literal"};
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(array);
        when(rs.getArray(1)).thenReturn(array);
        when(array.getArray()).thenReturn(elements);
        when(rs.wasNull()).thenReturn(false);

        var result = mapper.materialize(rs, 10, DbType.POSTGRESQL, Duration.ZERO);

        var converted = (List<?>) result.rows().getFirst().getFirst();
        assertThat(converted).hasSize(5);
        assertThat(converted.get(0)).isEqualTo(1);
        assertThat(converted.get(1)).isNull();
        assertThat(converted.get(2)).isEqualTo("base64:AQ==");
        assertThat(converted.get(3)).isEqualTo(uuid.toString());
        assertThat(converted.get(4)).isEqualTo("literal");
        verify(array, times(1)).free();
    }

    @Test
    void arrayWithNullValueReturnsNull() throws SQLException {
        var metadata = singleColumnMeta("v", Types.ARRAY, "_int4");
        var rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(new Object());
        when(rs.getArray(1)).thenReturn(null);
        when(rs.wasNull()).thenReturn(false);

        var result = mapper.materialize(rs, 10, DbType.POSTGRESQL, Duration.ZERO);

        assertThat(result.rows().getFirst().getFirst()).isNull();
    }

    @Test
    void arrayWithNonObjectArrayRawIsStringified() throws SQLException {
        var metadata = singleColumnMeta("v", Types.ARRAY, "_int4");
        var rs = mock(ResultSet.class);
        var array = mock(Array.class);
        var primitive = new int[] {1, 2, 3};
        when(rs.getMetaData()).thenReturn(metadata);
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(array);
        when(rs.getArray(1)).thenReturn(array);
        when(array.getArray()).thenReturn(primitive);
        when(rs.wasNull()).thenReturn(false);

        var result = mapper.materialize(rs, 10, DbType.POSTGRESQL, Duration.ZERO);

        assertThat(result.rows().getFirst().getFirst()).isEqualTo(String.valueOf(primitive));
        verify(array, times(1)).free();
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
