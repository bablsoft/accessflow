package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DeniedTablesTest {

    @Test
    void normalizeStripsQuotesLowercasesDropsBlanksAndDedupes() {
        var input = new ArrayList<String>();
        input.add(" \"CRM\".[Salary] ");
        input.add("crm.salary");
        input.add("  ");
        input.add(null);

        assertThat(DeniedTables.normalize(input)).containsExactly("crm.salary");
        assertThat(DeniedTables.normalize(null)).isEmpty();
    }

    @Test
    void qualifiedTableEntryDeniesItselfBareAndCatalogQualifiedReferences() {
        var tables = List.of("crm.salary");

        assertThat(DeniedTables.denyingEntry(List.of(), tables, "crm.salary"))
                .isEqualTo("crm.salary");
        // Unqualified: could resolve to crm.salary, so it fails closed.
        assertThat(DeniedTables.denyingEntry(List.of(), tables, "salary")).isEqualTo("crm.salary");
        assertThat(DeniedTables.denyingEntry(List.of(), tables, "db.crm.salary"))
                .isEqualTo("crm.salary");
        assertThat(DeniedTables.denyingEntry(List.of(), tables, "crm.customer")).isNull();
        assertThat(DeniedTables.denyingEntry(List.of(), tables, "hr.salary")).isNull();
        assertThat(DeniedTables.denyingEntry(List.of(), tables, "crm.salary_history")).isNull();
    }

    @Test
    void bareTableEntryDeniesTheNameInEverySchema() {
        var tables = List.of("salary");

        assertThat(DeniedTables.denyingEntry(List.of(), tables, "salary")).isEqualTo("salary");
        assertThat(DeniedTables.denyingEntry(List.of(), tables, "crm.salary")).isEqualTo("salary");
        assertThat(DeniedTables.denyingEntry(List.of(), tables, "hr.salary")).isEqualTo("salary");
        assertThat(DeniedTables.denyingEntry(List.of(), tables, "crm.salaryx")).isNull();
    }

    @Test
    void schemaEntryDeniesQualifiedReferencesAndEveryUnqualifiedOne() {
        var schemas = List.of("hr");

        assertThat(DeniedTables.denyingEntry(schemas, List.of(), "hr.employee")).isEqualTo("hr");
        assertThat(DeniedTables.denyingEntry(schemas, List.of(), "db.hr.employee"))
                .isEqualTo("hr");
        assertThat(DeniedTables.denyingEntry(schemas, List.of(), "crm.customer")).isNull();
        // A table named like the schema is not in it.
        assertThat(DeniedTables.denyingEntry(schemas, List.of(), "crm.hr")).isNull();
        // Unqualified: the gate cannot tell which schema it resolves to — fail closed.
        assertThat(DeniedTables.denyingEntry(schemas, List.of(), "employee")).isEqualTo("hr");
    }

    @Test
    void aSchemaWildcardInTheTableListDeniesTheSchema() {
        var tables = List.of("hr.*");

        assertThat(DeniedTables.denyingEntry(List.of(), tables, "hr.employee")).isEqualTo("hr");
        assertThat(DeniedTables.denyingEntry(List.of(), tables, "employee")).isEqualTo("hr");
        assertThat(DeniedTables.denyingEntry(List.of(), tables, "crm.customer")).isNull();
    }

    @Test
    void anEmptySchemaSegmentMatchesAnyDenial() {
        // SQL Server db..table resolves to the caller's default schema.
        assertThat(DeniedTables.denyingEntry(List.of(), List.of("dbo.salary"), "mydb..salary"))
                .isEqualTo("dbo.salary");
        assertThat(DeniedTables.denyingEntry(List.of("dbo"), List.of(), "mydb..salary"))
                .isEqualTo("dbo");
        assertThat(DeniedTables.denyingEntry(List.of(), List.of("dbo.salary"), "mydb..orders"))
                .isNull();
    }

    @Test
    void anOracleDatabaseLinkSuffixIsIgnored() {
        assertThat(DeniedTables.denyingEntry(List.of(), List.of("hr.salary"), "hr.salary@loop"))
                .isEqualTo("hr.salary");
        assertThat(DeniedTables.denyingEntry(List.of("hr"), List.of(), "hr.salary@loop"))
                .isEqualTo("hr");
    }

    @Test
    void aPatternReferenceIsDeniedByAnyEntry() {
        assertThat(DeniedTables.denyingEntry(List.of(), List.of("salary"), "sal*"))
                .isEqualTo("salary");
        assertThat(DeniedTables.denyingEntry(List.of("hr"), List.of(), "logs-?"))
                .isEqualTo("hr");
        assertThat(DeniedTables.denyingEntry(List.of(), List.of(), "sal*")).isNull();
    }

    @Test
    void denyingEntryIsNullForANullTableOrEmptyLists() {
        assertThat(DeniedTables.denyingEntry(List.of("hr"), List.of("x"), null)).isNull();
        assertThat(DeniedTables.denyingEntry(List.of(), List.of(), "hr.x")).isNull();
    }

    @Test
    void rejectedReturnsTheDeniedReferencesSortedInTheirOriginalSpelling() {
        var rejected = DeniedTables.rejected(List.of("HR"), List.of("\"crm\".\"salary\""),
                Set.of("crm.customer", "crm.salary", "hr.employee"));

        assertThat(rejected).containsExactly("crm.salary", "hr.employee");
    }

    @Test
    void rejectedIsEmptyWithoutDenialsOrReferences() {
        assertThat(DeniedTables.rejected(null, null, Set.of("crm.salary"))).isEmpty();
        assertThat(DeniedTables.rejected(List.of("hr"), List.of(), null)).isEmpty();
        assertThat(DeniedTables.rejected(List.of("hr"), List.of(), Set.of())).isEmpty();
    }

    @Test
    void deniesTableMatchesTheIntrospectedSchemaAndTable() {
        assertThat(DeniedTables.deniesTable(List.of(), List.of("crm.salary"), "CRM", "Salary"))
                .isTrue();
        assertThat(DeniedTables.deniesTable(List.of(), List.of("crm.salary"), "crm", "customer"))
                .isFalse();
        assertThat(DeniedTables.deniesTable(List.of("hr"), List.of(), "hr", "employee")).isTrue();
        assertThat(DeniedTables.deniesTable(List.of("hr"), List.of(), "crm", "employee")).isFalse();
        // No schema reads as unqualified, which a schema denial refuses.
        assertThat(DeniedTables.deniesTable(List.of("hr"), List.of(), null, "employee")).isTrue();
        assertThat(DeniedTables.deniesTable(List.of("hr"), List.of(), "hr", " ")).isFalse();
    }

    @Test
    void entryValidationRefusesNamesThatCouldNeverMatch() {
        assertThat(DeniedTables.isValidSchemaEntry("hr")).isTrue();
        assertThat(DeniedTables.isValidSchemaEntry("\"HR Data\"")).isTrue();
        assertThat(DeniedTables.isValidSchemaEntry("analytics.hr")).isFalse();
        assertThat(DeniedTables.isValidSchemaEntry("h*")).isFalse();
        assertThat(DeniedTables.isValidSchemaEntry(" ")).isFalse();
        assertThat(DeniedTables.isValidSchemaEntry(null)).isFalse();

        assertThat(DeniedTables.isValidTableEntry("salary")).isTrue();
        assertThat(DeniedTables.isValidTableEntry("crm.salary")).isTrue();
        assertThat(DeniedTables.isValidTableEntry("db.crm.salary")).isTrue();
        assertThat(DeniedTables.isValidTableEntry("crm.*")).isTrue();
        assertThat(DeniedTables.isValidTableEntry("*")).isFalse();
        assertThat(DeniedTables.isValidTableEntry("sal*")).isFalse();
        assertThat(DeniedTables.isValidTableEntry("crm..salary")).isFalse();
        assertThat(DeniedTables.isValidTableEntry("crm.")).isFalse();
        assertThat(DeniedTables.isValidTableEntry(null)).isFalse();
    }

    @Test
    void deniesSchemaMatchesNormalizedNames() {
        assertThat(DeniedTables.deniesSchema(List.of("\"HR\""), "hr")).isTrue();
        assertThat(DeniedTables.deniesSchema(List.of("hr"), "crm")).isFalse();
        assertThat(DeniedTables.deniesSchema(List.of("hr"), null)).isFalse();
    }
}
