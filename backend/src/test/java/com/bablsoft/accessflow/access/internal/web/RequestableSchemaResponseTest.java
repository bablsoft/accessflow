package com.bablsoft.accessflow.access.internal.web;

import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestableSchemaResponseTest {

    @Test
    void fromMapsSchemaNamesAndTableNames() {
        var view = new DatabaseSchemaView(List.of(
                new DatabaseSchemaView.Schema("public", List.of(
                        new DatabaseSchemaView.Table("orders", List.of(), List.of()),
                        new DatabaseSchemaView.Table("customers", List.of(), List.of()))),
                new DatabaseSchemaView.Schema("analytics", List.of(
                        new DatabaseSchemaView.Table("events", List.of(), List.of())))));

        var response = RequestableSchemaResponse.from(view);

        assertThat(response.schemas()).hasSize(2);
        assertThat(response.schemas().get(0).name()).isEqualTo("public");
        assertThat(response.schemas().get(0).tables()).containsExactly("orders", "customers");
        assertThat(response.schemas().get(1).name()).isEqualTo("analytics");
        assertThat(response.schemas().get(1).tables()).containsExactly("events");
    }

    @Test
    void fromHandlesEmptySchemas() {
        var response = RequestableSchemaResponse.from(new DatabaseSchemaView(List.of()));
        assertThat(response.schemas()).isEmpty();
    }

    @Test
    void fromHandlesSchemaWithNoTables() {
        var view = new DatabaseSchemaView(List.of(
                new DatabaseSchemaView.Schema("empty", List.of())));

        var response = RequestableSchemaResponse.from(view);

        assertThat(response.schemas()).singleElement()
                .satisfies(s -> {
                    assertThat(s.name()).isEqualTo("empty");
                    assertThat(s.tables()).isEmpty();
                });
    }

    @Test
    void fromIgnoresColumnAndForeignKeyDetail() {
        var view = new DatabaseSchemaView(List.of(
                new DatabaseSchemaView.Schema("public", List.of(
                        new DatabaseSchemaView.Table("orders",
                                List.of(new DatabaseSchemaView.Column("id", "uuid", false, true)),
                                List.of(new DatabaseSchemaView.ForeignKey("customer_id",
                                        "customers", "id")))))));

        var response = RequestableSchemaResponse.from(view);

        // The projection exposes only the table name — no column/FK structure leaks through.
        assertThat(response.schemas().get(0).tables()).containsExactly("orders");
    }
}
