package com.bablsoft.accessflow.core.internal.persistence.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.SslMode;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "datasources")
@Getter
@Setter
@NoArgsConstructor
public class DatasourceEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private OrganizationEntity organization;

    @Column(nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "db_type", nullable = false, columnDefinition = "db_type")
    private DbType dbType;

    @Column(length = 255)
    private String host;

    @Column
    private Integer port;

    @Column(name = "database_name", length = 255)
    private String databaseName;

    @Column(nullable = false, length = 255)
    private String username;

    @JsonIgnore
    @Column(name = "password_encrypted", nullable = false)
    private String passwordEncrypted;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "ssl_mode", nullable = false, columnDefinition = "ssl_mode")
    private SslMode sslMode = SslMode.DISABLE;

    @Column(name = "connection_pool_size", nullable = false)
    private int connectionPoolSize = 10;

    @Column(name = "max_rows_per_query", nullable = false)
    private int maxRowsPerQuery = 1000;

    @Column(name = "require_review_reads", nullable = false)
    private boolean requireReviewReads = false;

    @Column(name = "require_review_writes", nullable = false)
    private boolean requireReviewWrites = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_plan_id")
    private ReviewPlanEntity reviewPlan;

    @Column(name = "ai_analysis_enabled", nullable = false)
    private boolean aiAnalysisEnabled = true;

    @Column(name = "ai_config_id")
    private UUID aiConfigId;

    @Column(name = "text_to_sql_enabled", nullable = false)
    private boolean textToSqlEnabled = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "custom_driver_id")
    private CustomJdbcDriverEntity customDriver;

    @Column(name = "jdbc_url_override", columnDefinition = "TEXT")
    private String jdbcUrlOverride;

    @Column(name = "connector_id", length = 64)
    private String connectorId;

    @OneToMany(mappedBy = "datasource", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<DatasourceReadReplicaEntity> readReplicas = new ArrayList<>();

    @Column(name = "result_cache_enabled", nullable = false)
    private boolean resultCacheEnabled = false;

    @Column(name = "result_cache_ttl_seconds")
    private Integer resultCacheTtlSeconds;

    @Column(name = "local_datacenter", length = 255)
    private String localDatacenter;

    @JsonIgnore
    @Column(name = "api_key_encrypted", columnDefinition = "TEXT")
    private String apiKeyEncrypted;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
