package com.bablsoft.accessflow.lifecycle.api;

/**
 * How a deletion request's subject identifier is interpreted. Mirrors the PostgreSQL
 * {@code lifecycle_subject_type} enum.
 */
public enum LifecycleSubjectType {
    USER_ID,
    EMAIL,
    CUSTOM
}
