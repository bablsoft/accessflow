package com.bablsoft.accessflow.apigov.internal.persistence.entity;

import com.bablsoft.accessflow.apigov.api.ApiSchemaType;
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

@Entity
@Table(name = "api_schemas")
@Getter
@Setter
@NoArgsConstructor
public class ApiSchemaEntity {

    @Id
    private UUID id;

    @Column(name = "connector_id", nullable = false)
    private UUID connectorId;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "schema_type", nullable = false, columnDefinition = "api_schema_type")
    private ApiSchemaType schemaType;

    @Column(name = "raw_content", columnDefinition = "text")
    private String rawContent;

    @Column(name = "source_url", columnDefinition = "text")
    private String sourceUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parsed_operations", nullable = false, columnDefinition = "jsonb")
    private String parsedOperations = "[]";

    @Column(name = "operation_count", nullable = false)
    private int operationCount = 0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "operation_filter", columnDefinition = "jsonb")
    private String operationFilter;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
