package com.bablsoft.accessflow.schemachange.internal.persistence.entity;

import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatus;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * An authored schema change set (#878). Organization, pipeline and author are bare ids — the
 * deploygov cross-module convention, no FK — so the row survives deletion of what it names.
 * {@code statementsChecksum} is null until the authoring service (#879) computes it.
 */
@Entity
@Table(name = "schema_change_sets")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class SchemaChangeSetEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "pipeline_id", nullable = false)
    private UUID pipelineId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "schema_change_set_status")
    private SchemaChangeSetStatus status = SchemaChangeSetStatus.DRAFT;

    /** SHA-256 hex over the ordered statement text — a fixed-width {@code char(64)} in DDL. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "statements_checksum", length = 64, columnDefinition = "char(64)")
    private String statementsChecksum;

    @Column(name = "created_by")
    private UUID createdBy;

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
