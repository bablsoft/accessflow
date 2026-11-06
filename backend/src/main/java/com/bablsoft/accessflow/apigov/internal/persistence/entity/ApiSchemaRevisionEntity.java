package com.bablsoft.accessflow.apigov.internal.persistence.entity;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "api_schema_revisions")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class ApiSchemaRevisionEntity {

    @Id
    private UUID id;

    @Column(name = "schema_id", nullable = false)
    private UUID schemaId;

    @Column(name = "connector_id", nullable = false)
    private UUID connectorId;

    /** SHA-256 hex over the canonical parsed-catalog JSON — a fixed-width {@code char(64)} in DDL. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "content_hash", nullable = false, length = 64, columnDefinition = "char(64)")
    private String contentHash;

    @Column(name = "raw_content", columnDefinition = "text")
    private String rawContent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parsed_operations", nullable = false, columnDefinition = "jsonb")
    private String parsedOperations = "[]";

    @Column(name = "operation_count", nullable = false)
    private int operationCount = 0;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "accepted_by")
    private UUID acceptedBy;

    // A refresh bumps last_seen_at while an admin accept stamps accepted_* — skip a stale save.
    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
