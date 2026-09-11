package com.bablsoft.accessflow.sqlreview.internal.rules;

import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.StatementVisitor;
import org.junit.jupiter.api.Test;

import static com.bablsoft.accessflow.sqlreview.internal.rules.RuleTestSupport.parse;
import static org.assertj.core.api.Assertions.assertThat;

class TableNamesTest {

    @Test
    void normalizeStripsQuotesBracketsAndCase() {
        assertThat(TableNames.normalize("\"Payroll\".`Salaries`")).isEqualTo("payroll.salaries");
        assertThat(TableNames.normalize("[dbo].[Users]")).isEqualTo("dbo.users");
        assertThat(TableNames.normalize((String) null)).isEmpty();
        assertThat(TableNames.normalize((Table) null)).isEmpty();
        assertThat(TableNames.normalize(new Table("HR", "Emp"))).isEqualTo("hr.emp");
    }

    @Test
    void bareNameDropsTheSchema() {
        assertThat(TableNames.bareName("payroll.salaries")).isEqualTo("salaries");
        assertThat(TableNames.bareName("salaries")).isEqualTo("salaries");
    }

    @Test
    void referencedTablesAreNormalisedSortedAndExcludeCtes() {
        assertThat(TableNames.referencedTables(parse(
                "WITH c AS (SELECT * FROM \"X\") SELECT * FROM c, b JOIN a ON a.id = b.id")))
                .containsExactly("a", "b", "x");
        assertThat(TableNames.referencedTables(parse("SELECT 1"))).isEmpty();
    }

    @Test
    void referencedTablesSwallowUnsupportedStatementShapes() {
        Statement exotic = new Statement() {
            @Override
            public <T, S> T accept(StatementVisitor<T> visitor, S context) {
                throw new UnsupportedOperationException("exotic");
            }
        };
        assertThat(TableNames.referencedTables(exotic)).isEmpty();
    }
}
