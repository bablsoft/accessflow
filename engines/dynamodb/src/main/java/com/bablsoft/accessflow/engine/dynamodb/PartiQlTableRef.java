package com.bablsoft.accessflow.engine.dynamodb;

/**
 * A table referenced by a PartiQL statement. DynamoDB has no schema qualifier; a {@code FROM} target
 * is either a bare table ({@code "Music"}) or a table-with-index ({@code "Music"."GenreIndex"}),
 * the latter resolving to its base table for the host's allow-list (an index access governs the same
 * table). DynamoDB names are case-sensitive, so the name is stored verbatim (quotes stripped).
 *
 * @param table the base table name (never {@code null}); an index access resolves here
 */
record PartiQlTableRef(String table) {

    String normalized() {
        return table;
    }
}
