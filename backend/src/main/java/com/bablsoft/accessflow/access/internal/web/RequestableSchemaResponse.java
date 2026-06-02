package com.bablsoft.accessflow.access.internal.web;

import com.bablsoft.accessflow.core.api.DatabaseSchemaView;

import java.util.List;

/**
 * Lightweight schema projection for the JIT access-request form: schema names and the table names
 * they contain only. Deliberately omits columns and foreign keys — a requester scoping an access
 * grant needs schema/table names, not column-level detail, so we keep exposure to the minimum.
 */
public record RequestableSchemaResponse(List<Schema> schemas) {

    public record Schema(String name, List<String> tables) {
    }

    public static RequestableSchemaResponse from(DatabaseSchemaView view) {
        return new RequestableSchemaResponse(view.schemas().stream()
                .map(schema -> new Schema(
                        schema.name(),
                        schema.tables().stream().map(DatabaseSchemaView.Table::name).toList()))
                .toList());
    }
}
