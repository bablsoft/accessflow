package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AllowedTablesTest {

    @Test
    void normalizeStripsQuotesLowercasesAndDropsBlanks() {
        var input = new ArrayList<String>();
        input.add(" \"Public\".[Customer] ");
        input.add("`Orders`");
        input.add("  ");
        input.add(null);

        assertThat(AllowedTables.normalize(input)).containsExactly("public.customer", "orders");
        assertThat(AllowedTables.normalize(null)).isEmpty();
        assertThat(AllowedTables.normalize(List.of())).isEmpty();
    }

    @Test
    void normalizeEntryReturnsNullForBlank() {
        assertThat(AllowedTables.normalizeEntry(null)).isNull();
        assertThat(AllowedTables.normalizeEntry(" \"\" ")).isNull();
        assertThat(AllowedTables.normalizeEntry("[Sales]")).isEqualTo("sales");
    }

    @Test
    void coveringEntryNamesTheTableOrTheSchemaThatCoversIt() {
        var schemas = List.of("sales");
        var tables = List.of("public.customer", "orders");

        assertThat(AllowedTables.coveringEntry(schemas, tables, "public.customer"))
                .isEqualTo("public.customer");
        assertThat(AllowedTables.coveringEntry(schemas, tables, "orders")).isEqualTo("orders");
        assertThat(AllowedTables.coveringEntry(schemas, tables, "sales.invoice")).isEqualTo("sales");
        assertThat(AllowedTables.coveringEntry(schemas, tables, "invoice")).isNull();
        assertThat(AllowedTables.coveringEntry(schemas, tables, ".invoice")).isNull();
        assertThat(AllowedTables.coveringEntry(schemas, tables, "hr.salary")).isNull();
    }
}
