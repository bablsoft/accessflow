package com.bablsoft.accessflow.serviceaccounts.api;

/** The service account already has a key with that name (#871) — 409. */
public final class ServiceAccountKeyNameConflictException extends RuntimeException {

    private final String name;

    public ServiceAccountKeyNameConflictException(String name) {
        super("Service account already has an API key named " + name);
        this.name = name;
    }

    public String name() {
        return name;
    }
}
