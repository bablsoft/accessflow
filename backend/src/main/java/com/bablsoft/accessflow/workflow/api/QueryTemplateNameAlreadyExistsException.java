package com.bablsoft.accessflow.workflow.api;

/**
 * Thrown when a create / update would violate the {@code (organization_id, owner_id, LOWER(name))}
 * uniqueness constraint. Mapped to HTTP 409. Owners may not have two templates with the same name,
 * but two different owners in the same org may.
 */
public final class QueryTemplateNameAlreadyExistsException extends RuntimeException {

    private final String name;

    public QueryTemplateNameAlreadyExistsException(String name) {
        super("Query template with this name already exists: " + name);
        this.name = name;
    }

    public String name() {
        return name;
    }
}
