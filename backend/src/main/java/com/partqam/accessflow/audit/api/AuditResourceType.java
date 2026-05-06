package com.partqam.accessflow.audit.api;

/**
 * Resource categories referenced by audit rows. {@link #dbValue()} is the snake_case form persisted
 * in {@code audit_log.resource_type} so the column matches the convention used elsewhere in the
 * schema.
 */
public enum AuditResourceType {
    QUERY_REQUEST("query_request"),
    DATASOURCE("datasource"),
    USER("user"),
    PERMISSION("permission"),
    NOTIFICATION_CHANNEL("notification_channel");

    private final String dbValue;

    AuditResourceType(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static AuditResourceType fromDbValue(String value) {
        for (var type : values()) {
            if (type.dbValue.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown audit resource type: " + value);
    }
}
