package com.bablsoft.accessflow.serviceaccounts.api;

/** A bootstrap-declared field (display name, role) would change on a BOOTSTRAP-managed account (#871) — 409. The YAML owns it; edit the bootstrap source instead. */
public final class ServiceAccountBootstrapManagedException extends RuntimeException {

    private final String field;

    public ServiceAccountBootstrapManagedException(String field) {
        super("Field is managed by bootstrap: " + field);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
