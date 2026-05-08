package com.partqam.accessflow.proxy.internal;

import org.junit.jupiter.api.Test;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RestrictedColumnMatcherTest {

    @Test
    void emptyListProducesNoMaskedColumns() throws SQLException {
        var metadata = mock(ResultSetMetaData.class);
        when(metadata.getColumnCount()).thenReturn(2);

        var matcher = RestrictedColumnMatcher.build(metadata, List.of());

        assertThat(matcher.isRestricted(1)).isFalse();
        assertThat(matcher.isRestricted(2)).isFalse();
        assertThat(matcher.restrictedIndices()).isEmpty();
    }

    @Test
    void nullListIsTreatedAsEmpty() throws SQLException {
        var metadata = mock(ResultSetMetaData.class);
        when(metadata.getColumnCount()).thenReturn(1);

        var matcher = RestrictedColumnMatcher.build(metadata, null);

        assertThat(matcher.isRestricted(1)).isFalse();
    }

    @Test
    void fullyQualifiedMatchMasksColumn() throws SQLException {
        var metadata = mock(ResultSetMetaData.class);
        when(metadata.getColumnCount()).thenReturn(2);
        when(metadata.getColumnLabel(1)).thenReturn("id");
        when(metadata.getColumnLabel(2)).thenReturn("ssn");
        when(metadata.getSchemaName(1)).thenReturn("public");
        when(metadata.getSchemaName(2)).thenReturn("public");
        when(metadata.getTableName(1)).thenReturn("users");
        when(metadata.getTableName(2)).thenReturn("users");

        var matcher = RestrictedColumnMatcher.build(metadata, List.of("public.users.ssn"));

        assertThat(matcher.isRestricted(1)).isFalse();
        assertThat(matcher.isRestricted(2)).isTrue();
        assertThat(matcher.restrictedIndices()).containsExactly(2);
    }

    @Test
    void tableQualifiedMatchHonoredWhenSchemaMissing() throws SQLException {
        var metadata = mock(ResultSetMetaData.class);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn("amount");
        when(metadata.getSchemaName(1)).thenReturn(null);
        when(metadata.getTableName(1)).thenReturn("orders");

        var matcher = RestrictedColumnMatcher.build(metadata, List.of("orders.amount"));

        assertThat(matcher.isRestricted(1)).isTrue();
    }

    @Test
    void bareColumnMatchFallbackOverMasksWhenTableUnknown() throws SQLException {
        var metadata = mock(ResultSetMetaData.class);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn("password");
        when(metadata.getSchemaName(1)).thenReturn(null);
        when(metadata.getTableName(1)).thenReturn(null);

        var matcher = RestrictedColumnMatcher.build(metadata, List.of("public.users.password"));

        assertThat(matcher.isRestricted(1)).isTrue();
    }

    @Test
    void caseInsensitiveMatching() throws SQLException {
        var metadata = mock(ResultSetMetaData.class);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn("Email");
        when(metadata.getSchemaName(1)).thenReturn("PUBLIC");
        when(metadata.getTableName(1)).thenReturn("Users");

        var matcher = RestrictedColumnMatcher.build(metadata, List.of("public.users.email"));

        assertThat(matcher.isRestricted(1)).isTrue();
    }

    @Test
    void columnLabelFallsBackToColumnName() throws SQLException {
        var metadata = mock(ResultSetMetaData.class);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn(null);
        when(metadata.getColumnName(1)).thenReturn("ssn");
        when(metadata.getSchemaName(1)).thenReturn(null);
        when(metadata.getTableName(1)).thenReturn(null);

        var matcher = RestrictedColumnMatcher.build(metadata, List.of("ssn"));

        assertThat(matcher.isRestricted(1)).isTrue();
    }

    @Test
    void blankAndNullEntriesAreSkipped() throws SQLException {
        var metadata = mock(ResultSetMetaData.class);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn("name");

        var matcher = RestrictedColumnMatcher.build(metadata,
                java.util.Arrays.asList("", "   ", null));

        assertThat(matcher.isRestricted(1)).isFalse();
    }

    @Test
    void blankColumnLabelDoesNotMatch() throws SQLException {
        var metadata = mock(ResultSetMetaData.class);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn("");
        when(metadata.getColumnName(1)).thenReturn("");
        when(metadata.getSchemaName(1)).thenReturn(null);
        when(metadata.getTableName(1)).thenReturn(null);

        var matcher = RestrictedColumnMatcher.build(metadata, List.of("ssn"));

        assertThat(matcher.isRestricted(1)).isFalse();
    }
}
