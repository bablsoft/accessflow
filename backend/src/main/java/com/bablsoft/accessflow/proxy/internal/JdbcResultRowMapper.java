package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.proxy.api.ResultColumn;
import com.bablsoft.accessflow.proxy.api.SelectExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Component
class JdbcResultRowMapper {

    static final String MASKED_VALUE = "***";

    private static final Logger log = LoggerFactory.getLogger(JdbcResultRowMapper.class);
    private static final String BASE64_PREFIX = "base64:";

    SelectExecutionResult materialize(ResultSet rs, int maxRows, DbType dbType, Duration duration)
            throws SQLException {
        return materialize(rs, maxRows, dbType, duration, List.of());
    }

    SelectExecutionResult materialize(ResultSet rs, int maxRows, DbType dbType, Duration duration,
                                      List<String> restrictedColumns)
            throws SQLException {
        var metadata = rs.getMetaData();
        var matcher = RestrictedColumnMatcher.build(metadata, restrictedColumns);
        var columns = describeColumns(metadata, matcher);
        var rows = new ArrayList<List<Object>>();
        boolean truncated = false;
        while (rs.next()) {
            if (rows.size() >= maxRows) {
                truncated = true;
                break;
            }
            rows.add(readRow(rs, metadata, dbType, matcher));
        }
        return new SelectExecutionResult(columns, rows, rows.size(), truncated, duration);
    }

    private List<ResultColumn> describeColumns(ResultSetMetaData metadata,
                                               RestrictedColumnMatcher matcher) throws SQLException {
        var count = metadata.getColumnCount();
        var columns = new ArrayList<ResultColumn>(count);
        for (int i = 1; i <= count; i++) {
            var label = metadata.getColumnLabel(i);
            if (label == null || label.isBlank()) {
                label = metadata.getColumnName(i);
            }
            columns.add(new ResultColumn(label, metadata.getColumnType(i),
                    metadata.getColumnTypeName(i), matcher.isRestricted(i)));
        }
        return columns;
    }

    private List<Object> readRow(ResultSet rs, ResultSetMetaData metadata, DbType dbType,
                                 RestrictedColumnMatcher matcher) throws SQLException {
        var count = metadata.getColumnCount();
        var values = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            if (matcher.isRestricted(i)) {
                values.add(rs.getObject(i) == null && rs.wasNull() ? null : MASKED_VALUE);
            } else {
                values.add(readValue(rs, i, metadata.getColumnType(i),
                        metadata.getColumnTypeName(i), dbType));
            }
        }
        return values;
    }

    private Object readValue(ResultSet rs, int index, int jdbcType, String typeName, DbType dbType)
            throws SQLException {
        var raw = rs.getObject(index);
        if (raw == null || rs.wasNull()) {
            return null;
        }
        return switch (jdbcType) {
            case Types.BIT, Types.BOOLEAN -> rs.getBoolean(index);
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> rs.getInt(index);
            case Types.BIGINT -> rs.getLong(index);
            case Types.FLOAT, Types.REAL -> rs.getFloat(index);
            case Types.DOUBLE -> rs.getDouble(index);
            case Types.NUMERIC, Types.DECIMAL -> rs.getBigDecimal(index);
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
                    Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR -> rs.getString(index);
            case Types.DATE -> toOffsetDateTime(rs.getDate(index));
            case Types.TIME, Types.TIME_WITH_TIMEZONE -> toOffsetDateTime(rs.getTime(index));
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> toOffsetDateTime(rs.getTimestamp(index));
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB ->
                    encodeBytes(rs.getBytes(index));
            case Types.ARRAY -> readArray(rs.getArray(index));
            case Types.OTHER -> readOther(rs, index, typeName, dbType);
            default -> readDefault(raw, typeName);
        };
    }

    private Object readOther(ResultSet rs, int index, String typeName, DbType dbType)
            throws SQLException {
        if (typeName == null) {
            return String.valueOf(rs.getObject(index));
        }
        var lower = typeName.toLowerCase();
        if (dbType == DbType.POSTGRESQL && lower.equals("uuid")) {
            var uuid = rs.getObject(index, UUID.class);
            return uuid == null ? null : uuid.toString();
        }
        if (lower.equals("json") || lower.equals("jsonb")) {
            return rs.getString(index);
        }
        return String.valueOf(rs.getObject(index));
    }

    private Object readDefault(Object raw, String typeName) {
        if (raw instanceof String s) {
            return s;
        }
        if (raw instanceof Number || raw instanceof Boolean) {
            return raw;
        }
        if (raw instanceof byte[] bytes) {
            return encodeBytes(bytes);
        }
        if (raw instanceof UUID uuid) {
            return uuid.toString();
        }
        log.warn("Falling back to toString for column type {} ({})", typeName, raw.getClass());
        return raw.toString();
    }

    private static OffsetDateTime toOffsetDateTime(Date date) {
        return date.toLocalDate().atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    private static OffsetDateTime toOffsetDateTime(Time time) {
        return time.toLocalTime().atDate(java.time.LocalDate.EPOCH).atOffset(ZoneOffset.UTC);
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp ts) {
        return ts.toInstant().atOffset(ZoneOffset.UTC);
    }

    private static String encodeBytes(byte[] bytes) {
        return BASE64_PREFIX + Base64.getEncoder().encodeToString(bytes);
    }

    private Object readArray(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        try {
            var raw = array.getArray();
            if (!(raw instanceof Object[] elements)) {
                return String.valueOf(raw);
            }
            var converted = new ArrayList<>(elements.length);
            for (var element : elements) {
                if (element == null) {
                    converted.add(null);
                } else if (element instanceof byte[] b) {
                    converted.add(encodeBytes(b));
                } else if (element instanceof UUID uuid) {
                    converted.add(uuid.toString());
                } else {
                    converted.add(element);
                }
            }
            return converted;
        } finally {
            array.free();
        }
    }
}
