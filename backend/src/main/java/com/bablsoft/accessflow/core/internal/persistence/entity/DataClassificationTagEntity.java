package com.bablsoft.accessflow.core.internal.persistence.entity;

import com.bablsoft.accessflow.core.api.DataClassification;
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

@Entity
@Table(name = "data_classification_tag")
@Getter
@Setter
@NoArgsConstructor
public class DataClassificationTagEntity {

    @Id
    private UUID id;

    // Bare UUID references — data_classification_tag is configuration scoped to a datasource; the
    // admin/query services validate the datasource-in-organization invariant via DatasourceRepository.
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "datasource_id", nullable = false)
    private UUID datasourceId;

    @Column(name = "table_name", nullable = false, columnDefinition = "text")
    private String tableName;

    // null = a table-level tag (no derived masking).
    @Column(name = "column_name", columnDefinition = "text")
    private String columnName;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "data_classification")
    private DataClassification classification;

    @Column(name = "note", columnDefinition = "text")
    private String note;

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
