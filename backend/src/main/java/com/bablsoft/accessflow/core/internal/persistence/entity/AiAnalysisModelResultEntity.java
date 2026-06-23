package com.bablsoft.accessflow.core.internal.persistence.entity;

import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.RiskLevel;
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
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-model contribution to a single {@link AiAnalysisEntity} (AF-450). One row per participating
 * model — single-model analyses get exactly one. {@code riskScore}/{@code riskLevel} are null for a
 * member that failed.
 */
@Entity
@Table(name = "ai_analysis_model_result")
@Getter
@Setter
@NoArgsConstructor
public class AiAnalysisModelResultEntity {

    @Id
    private UUID id;

    @Column(name = "ai_analysis_id", nullable = false)
    private UUID aiAnalysisId;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "ai_provider", nullable = false, columnDefinition = "ai_provider")
    private AiProviderType aiProvider;

    @Column(name = "ai_model", nullable = false, length = 100)
    private String aiModel;

    @Column(name = "risk_score")
    private Integer riskScore;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "risk_level", columnDefinition = "risk_level")
    private RiskLevel riskLevel;

    @Column(nullable = false)
    private double weight = 1.0;

    @Column(name = "prompt_tokens", nullable = false)
    private int promptTokens = 0;

    @Column(name = "completion_tokens", nullable = false)
    private int completionTokens = 0;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs = 0;

    @Column(nullable = false)
    private boolean failed = false;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
