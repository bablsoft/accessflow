package com.bablsoft.accessflow.schemachange.internal.persistence.entity;

import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionStatus;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
 * One attempt to promote a change set to one environment (#878). Environment, datasource,
 * request group and promoter are bare ids (no FK). At most one non-terminal row may exist per
 * change set and environment — the partial unique index {@code uq_schema_change_set_promotions_open}.
 */
@Entity
@Table(name = "schema_change_set_promotions")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class SchemaChangeSetPromotionEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "change_set_id", nullable = false)
    private SchemaChangeSetEntity changeSet;

    @Column(name = "environment_id", nullable = false)
    private UUID environmentId;

    @Column(name = "datasource_id", nullable = false)
    private UUID datasourceId;

    @Column(name = "request_group_id")
    private UUID requestGroupId;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "schema_change_promotion_status")
    private SchemaChangePromotionStatus status = SchemaChangePromotionStatus.PENDING;

    /** The change set's checksum at submission — evidence of exactly what was sent. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "statements_checksum", nullable = false, length = 64, columnDefinition = "char(64)")
    private String statementsChecksum;

    @Column(name = "promoted_by")
    private UUID promotedBy;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt = Instant.now();

    @Column(name = "applied_at")
    private Instant appliedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** The post-apply introspection as JSON — the {@code PROMOTION_SNAPSHOT} drift baseline. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "schema_snapshot", columnDefinition = "jsonb")
    private String schemaSnapshot;

    @Column(name = "snapshot_taken_at")
    private Instant snapshotTakenAt;

    @Version
    @Column(nullable = false)
    private long version;
}
