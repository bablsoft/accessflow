package com.bablsoft.accessflow.core.internal.persistence.entity;

import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.RiskLevel;
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
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ai_analyses")
@Getter
@Setter
@NoArgsConstructor
public class AiAnalysisEntity {

    @Id
    private UUID id;

    // Nullable since AF-500 pipeline: an analysis keys off EITHER a query request OR an API request
    // (api_request_id), enforced exactly-one by a DB CHECK. apigov is a separate module, so the API
    // side is referenced by a bare UUID rather than a JPA relationship.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "query_request_id")
    private QueryRequestEntity queryRequest;

    @Column(name = "api_request_id")
    private UUID apiRequestId;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "ai_provider", nullable = false, columnDefinition = "ai_provider")
    private AiProviderType aiProvider;

    @Column(name = "ai_model", nullable = false, length = 100)
    private String aiModel;

    @Column(name = "risk_score", nullable = false)
    private int riskScore;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "risk_level", nullable = false, columnDefinition = "risk_level")
    private RiskLevel riskLevel;

    @Column(nullable = false, columnDefinition = "text")
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String issues = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String optimizations = "[]";

    @Column(name = "missing_indexes_detected", nullable = false)
    private boolean missingIndexesDetected = false;

    @Column(name = "affects_row_estimate")
    private Long affectsRowEstimate;

    @Column(name = "prompt_tokens", nullable = false)
    private int promptTokens = 0;

    @Column(name = "completion_tokens", nullable = false)
    private int completionTokens = 0;

    @Column(nullable = false)
    private boolean failed = false;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
