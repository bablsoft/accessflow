package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.DatabaseSchemaView;

import java.util.List;

public record DatabaseSchemaResponse(List<Schema> schemas) {

    public record Schema(String name, List<Table> tables) {}

    public record Table(String name, List<Column> columns, List<ForeignKey> foreignKeys) {}

    public record Column(String name, String type, boolean nullable, boolean primaryKey) {}

    public record ForeignKey(String fromColumn, String toTable, String toColumn) {}

    public static DatabaseSchemaResponse from(DatabaseSchemaView view) {
        return new DatabaseSchemaResponse(view.schemas().stream()
                .map(s -> new Schema(s.name(),
                        s.tables().stream()
                                .map(t -> new Table(t.name(),
                                        t.columns().stream()
                                                .map(c -> new Column(c.name(), c.type(),
                                                        c.nullable(), c.primaryKey()))
                                                .toList(),
                                        t.foreignKeys().stream()
                                                .map(fk -> new ForeignKey(fk.fromColumn(),
                                                        fk.toTable(), fk.toColumn()))
                                                .toList()))
                                .toList()))
                .toList());
    }
}
