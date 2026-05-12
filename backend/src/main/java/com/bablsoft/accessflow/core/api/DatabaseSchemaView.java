package com.bablsoft.accessflow.core.api;

import java.util.List;

public record DatabaseSchemaView(List<Schema> schemas) {

    public record Schema(String name, List<Table> tables) {}

    public record Table(String name, List<Column> columns) {}

    public record Column(String name, String type, boolean nullable, boolean primaryKey) {}
}
