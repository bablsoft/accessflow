package com.bablsoft.accessflow.sqlreview.internal.rules;

import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterOperation;
import net.sf.jsqlparser.statement.create.table.ColDataType;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ColumnDefinitionsTest {

    private static Alter alter(String sql) {
        return (Alter) RuleTestSupport.parse(sql);
    }

    @Test
    void collectsOnlyTheRequestedOperations() {
        var alter = alter("ALTER TABLE t ADD COLUMN a INT, ALTER COLUMN b TYPE TEXT, DROP COLUMN c");
        assertThat(ColumnDefinitions.of(alter, Set.of(AlterOperation.ADD)))
                .extracting(target -> target.column().getColumnName()).containsExactly("a");
        assertThat(ColumnDefinitions.of(alter, Set.of(AlterOperation.ADD, AlterOperation.ALTER))).hasSize(2);
        assertThat(ColumnDefinitions.of(new Alter(), Set.of(AlterOperation.ADD))).isEmpty();
    }

    @Test
    void readsNullabilityDefaultsAndGeneratedMarkers() {
        var column = new ColumnDefinition("c", new ColDataType("INT"), List.of("not", "null", "default", "0"));
        assertThat(ColumnDefinitions.isNotNull(column)).isTrue();
        assertThat(ColumnDefinitions.hasDefault(column)).isTrue();
        assertThat(ColumnDefinitions.isGenerated(column)).isFalse();

        var bare = new ColumnDefinition("c", new ColDataType("serial"));
        assertThat(ColumnDefinitions.isNotNull(bare)).isFalse();
        assertThat(ColumnDefinitions.hasDefault(bare)).isFalse();
        assertThat(ColumnDefinitions.isGenerated(bare)).isTrue();

        var typeless = new ColumnDefinition("c", null, Arrays.asList("NOT", null, "NULL"));
        assertThat(ColumnDefinitions.isGenerated(typeless)).isFalse();
        assertThat(ColumnDefinitions.isNotNull(new ColumnDefinition("c", new ColDataType("INT"),
                List.of("NULL", "NOT")))).isFalse();
    }

    @Test
    void qualifiesColumnsWithTheNormalisedTable() {
        assertThat(ColumnDefinitions.qualified(alter("ALTER TABLE \"S\".T ADD COLUMN x INT"), "\"Col\""))
                .isEqualTo("s.t.col");
    }
}
