package com.bablsoft.accessflow.workflow.internal.persistence.entity;

import com.bablsoft.accessflow.workflow.api.QueryTemplateChangeType;
import com.bablsoft.accessflow.workflow.api.QueryTemplateVisibility;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
 * Immutable point-in-time snapshot of a {@link QueryTemplateEntity}, written on every content-changing
 * save and on restore (AF-442). INSERT-only — never updated, so there is no {@code @Version} column.
 */
@Entity
@Table(name = "query_template_versions")
@Getter
@Setter
@NoArgsConstructor
public class QueryTemplateVersionEntity {

    @Id
    private UUID id;

    @Column(name = "template_id", nullable = false)
    private UUID templateId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "datasource_id")
    private UUID datasourceId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(length = 1000)
    private String description;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "tags", nullable = false, columnDefinition = "text[]")
    private String[] tags = new String[0];

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "visibility", nullable = false, columnDefinition = "query_template_visibility")
    private QueryTemplateVisibility visibility;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "change_type", nullable = false, columnDefinition = "query_template_change_type")
    private QueryTemplateChangeType changeType;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
