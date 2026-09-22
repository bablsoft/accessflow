package com.bablsoft.accessflow.schemachange.api;

/**
 * One statement as submitted by an author (#878, epic #870). The authoring service (#879) parses
 * and classifies it through the engine-aware parser before it is stored; ordering is the list
 * position of the enclosing command.
 */
public record SchemaChangeSetStatementInput(String sqlText) {
}
