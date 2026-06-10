package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import org.junit.jupiter.api.Test;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ColumnMaskResolverTest {

    private record Col(String schema, String table, String label) {}

    private static ResultSetMetaData metadata(Col... cols) throws SQLException {
        var md = mock(ResultSetMetaData.class);
        when(md.getColumnCount()).thenReturn(cols.length);
        for (int i = 0; i < cols.length; i++) {
            int idx = i + 1;
            when(md.getSchemaName(idx)).thenReturn(cols[i].schema());
            when(md.getTableName(idx)).thenReturn(cols[i].table());
            when(md.getColumnLabel(idx)).thenReturn(cols[i].label());
        }
        return md;
    }

    @Test
    void restrictedColumnDefaultsToFullMaskWithNoPolicyId() throws SQLException {
        var md = metadata(new Col("public", "users", "ssn"));
        var resolver = ColumnMaskResolver.build(md, List.of("public.users.ssn"), List.of());

        assertThat(resolver.isMasked(1)).isTrue();
        assertThat(resolver.maskFor(1).strategy()).isEqualTo(MaskingStrategy.FULL);
        assertThat(resolver.maskFor(1).policyId()).isNull();
        assertThat(resolver.appliedPolicyIds()).isEmpty();
    }

    @Test
    void unmatchedColumnHasNoMask() throws SQLException {
        var md = metadata(new Col("public", "users", "name"));
        var resolver = ColumnMaskResolver.build(md, List.of("public.users.ssn"), List.of());

        assertThat(resolver.isMasked(1)).isFalse();
        assertThat(resolver.maskFor(1)).isNull();
    }

    @Test
    void directiveAppliesStrategyAndTracksPolicyId() throws SQLException {
        var md = metadata(new Col("public", "users", "email"));
        var policyId = UUID.randomUUID();
        var directive = new ColumnMaskDirective("public.users.email", MaskingStrategy.EMAIL,
                Map.of(), policyId);

        var resolver = ColumnMaskResolver.build(md, List.of(), List.of(directive));

        assertThat(resolver.maskFor(1).strategy()).isEqualTo(MaskingStrategy.EMAIL);
        assertThat(resolver.appliedPolicyIds()).containsExactly(policyId);
    }

    @Test
    void explicitDirectiveWinsOverRestrictedFullDefault() throws SQLException {
        var md = metadata(new Col("public", "users", "email"));
        var policyId = UUID.randomUUID();
        var directive = new ColumnMaskDirective("email", MaskingStrategy.EMAIL, Map.of(), policyId);

        var resolver = ColumnMaskResolver.build(md, List.of("public.users.email"),
                List.of(directive));

        assertThat(resolver.maskFor(1).strategy()).isEqualTo(MaskingStrategy.EMAIL);
        assertThat(resolver.maskFor(1).policyId()).isEqualTo(policyId);
    }

    @Test
    void mostSpecificDirectiveWinsAmongMatches() throws SQLException {
        var md = metadata(new Col("public", "users", "email"));
        var bareId = UUID.randomUUID();
        var fullId = UUID.randomUUID();
        var bare = new ColumnMaskDirective("email", MaskingStrategy.HASH, Map.of(), bareId);
        var full = new ColumnMaskDirective("public.users.email", MaskingStrategy.EMAIL, Map.of(),
                fullId);

        var resolver = ColumnMaskResolver.build(md, List.of(), List.of(bare, full));

        assertThat(resolver.maskFor(1).policyId()).isEqualTo(fullId);
        assertThat(resolver.maskFor(1).strategy()).isEqualTo(MaskingStrategy.EMAIL);
    }

    @Test
    void bareColumnMatchesWhenDriverOmitsTableMetadata() throws SQLException {
        var md = metadata(new Col(null, null, "email"));
        var directive = new ColumnMaskDirective("public.users.email", MaskingStrategy.HASH,
                Map.of(), UUID.randomUUID());

        var resolver = ColumnMaskResolver.build(md, List.of(), List.of(directive));

        assertThat(resolver.maskFor(1).strategy()).isEqualTo(MaskingStrategy.HASH);
    }

    @Test
    void blankColumnLabelIsSkipped() throws SQLException {
        var md = mock(ResultSetMetaData.class);
        when(md.getColumnCount()).thenReturn(1);
        when(md.getSchemaName(1)).thenReturn(null);
        when(md.getTableName(1)).thenReturn(null);
        when(md.getColumnLabel(1)).thenReturn("");
        when(md.getColumnName(1)).thenReturn("");

        var resolver = ColumnMaskResolver.build(md, List.of("anything"), List.of());

        assertThat(resolver.isMasked(1)).isFalse();
    }

    @Test
    void ignoresBlankAndNullEntries() throws SQLException {
        var md = metadata(new Col("public", "users", "email"));
        var directive = new ColumnMaskDirective("  ", MaskingStrategy.HASH, Map.of(),
                UUID.randomUUID());

        var resolver = ColumnMaskResolver.build(md, List.of("", "  "), List.of(directive));

        assertThat(resolver.isMasked(1)).isFalse();
    }
}
