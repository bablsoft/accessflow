package com.bablsoft.accessflow.workflow.internal.persistence.entity;

import com.bablsoft.accessflow.workflow.api.QueryTemplateVisibility;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
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

@Entity
@Table(name = "query_templates")
@Getter
@Setter
@NoArgsConstructor
public class QueryTemplateEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

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

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
