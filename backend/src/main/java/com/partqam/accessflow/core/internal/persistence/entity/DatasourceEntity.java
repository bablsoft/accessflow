package com.partqam.accessflow.core.internal.persistence.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.partqam.accessflow.core.api.DbType;
import com.partqam.accessflow.core.api.SslMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
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
    @Column(name = "db_type", nullable = false, columnDefinition = "db_type_enum")
    private DbType dbType;

    @Column(nullable = false, length = 255)
    private String host;

    @Column(nullable = false)
    private int port;

    @Column(name = "database_name", nullable = false, length = 255)
    private String databaseName;

    @Column(nullable = false, length = 255)
    private String username;

    @JsonIgnore
    @Column(name = "password_encrypted", nullable = false)
    private String passwordEncrypted;

    @Enumerated(EnumType.STRING)
    @Column(name = "ssl_mode", nullable = false, columnDefinition = "ssl_mode_enum")
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

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
