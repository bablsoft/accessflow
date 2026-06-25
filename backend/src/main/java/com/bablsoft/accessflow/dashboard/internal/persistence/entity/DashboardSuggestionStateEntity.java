package com.bablsoft.accessflow.dashboard.internal.persistence.entity;

import com.bablsoft.accessflow.dashboard.api.DashboardSuggestionStatus;
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
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-item lifecycle override for a dashboard AI optimization suggestion (AF-498). Only rows whose
 * state diverges from the implicit {@code OPEN} default are persisted — i.e. a row exists exactly when
 * the user has DISMISSED (or APPLIED) the {@code (ai_analysis_id, suggestion_index)} item. Scoped by
 * {@code (organization_id, user_id)}.
 */
@Entity
@Table(name = "dashboard_suggestion_state")
@Getter
@Setter
@NoArgsConstructor
public class DashboardSuggestionStateEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "ai_analysis_id", nullable = false)
    private UUID aiAnalysisId;

    @Column(name = "suggestion_index", nullable = false)
    private int suggestionIndex;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "dashboard_suggestion_status")
    private DashboardSuggestionStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;
}
