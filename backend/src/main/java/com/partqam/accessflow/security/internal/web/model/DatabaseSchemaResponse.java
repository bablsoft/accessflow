package com.partqam.accessflow.security.internal.web.model;

import com.partqam.accessflow.core.api.DatabaseSchemaView;

import java.util.List;

public record DatabaseSchemaResponse(List<Schema> schemas) {

    public record Schema(String name, List<Table> tables) {}

    public record Table(String name, List<Column> columns) {}

    public record Column(String name, String type, boolean nullable, boolean primaryKey) {}

    public static DatabaseSchemaResponse from(DatabaseSchemaView view) {
        return new DatabaseSchemaResponse(view.schemas().stream()
                .map(s -> new Schema(s.name(),
                        s.tables().stream()
                                .map(t -> new Table(t.name(),
                                        t.columns().stream()
                                                .map(c -> new Column(c.name(), c.type(),
                                                        c.nullable(), c.primaryKey()))
                                                .toList()))
                                .toList()))
                .toList());
    }
}
