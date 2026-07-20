package com.bablsoft.accessflow.apigov.internal.persistence.entity;

import com.bablsoft.accessflow.apigov.api.ApiVariableAlgorithm;
import com.bablsoft.accessflow.apigov.api.ApiVariableEncoding;
import com.bablsoft.accessflow.apigov.api.ApiVariableKind;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;

import java.time.Instant;
import java.util.UUID;

/**
 * A named per-connector expression evaluated at execution time and substituted into the outbound
 * request via {@code {{name}}} placeholders (AF-613).
 */
@Entity
@Table(name = "api_connector_variables")
@Getter
@Setter
@NoArgsConstructor
public class ApiConnectorVariableEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "connector_id", nullable = false)
    private UUID connectorId;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "api_variable_kind")
    private ApiVariableKind kind;

    // The input template for the value; itself placeholder-expanded. Required or forbidden depending
    // on the kind (validated in the admin service, not the DB).
    @Column(columnDefinition = "text")
    private String expression;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(columnDefinition = "api_variable_algorithm")
    private ApiVariableAlgorithm algorithm;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(columnDefinition = "api_variable_encoding")
    private ApiVariableEncoding encoding;

    // AES-256-GCM shared secret for kind=HMAC. Same rules as auth_credentials_encrypted: never
    // serialized, never returned in a GET, never logged.
    @JsonIgnore
    @Column(name = "secret_encrypted", columnDefinition = "text")
    private String secretEncrypted;

    // Optional auto-injection site — 'header:<Name>' or 'query:<name>' — applied after substitution.
    @Column(columnDefinition = "text")
    private String target;

    @Column(nullable = false)
    private boolean overridable = false;

    @Column(columnDefinition = "text")
    private String description;

    // Explicit rather than ordering by created_at: evaluation order is observable, and
    // CURRENT_TIMESTAMP is transaction-constant so same-transaction inserts would tie.
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
