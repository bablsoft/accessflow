package com.bablsoft.accessflow.audit.api;

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
    REVIEW_PLAN("review_plan"),
    NOTIFICATION_CHANNEL("notification_channel"),
    AI_CONFIG("ai_config"),
    CUSTOM_JDBC_DRIVER("custom_jdbc_driver"),
    SYSTEM_SMTP("system_smtp"),
    USER_INVITATION("user_invitation"),
    ORGANIZATION("organization"),
    OAUTH2_CONFIG("oauth2_config"),
    SAML_CONFIG("saml_config"),
    AUDIT_LOG("audit_log"),
    USER_GROUP("user_group"),
    DATASOURCE_REVIEWER("datasource_reviewer");

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
