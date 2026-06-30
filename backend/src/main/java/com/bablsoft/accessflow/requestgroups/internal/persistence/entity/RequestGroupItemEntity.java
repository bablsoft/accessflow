package com.bablsoft.accessflow.requestgroups.internal.persistence.entity;

import com.bablsoft.accessflow.apigov.api.ApiBodyType;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupItemStatus;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupTargetKind;
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
@Table(name = "request_group_items")
@Getter
@Setter
@NoArgsConstructor
public class RequestGroupItemEntity {

    @Id
    private UUID id;

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Column(name = "sequence_order", nullable = false)
    private int sequenceOrder;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "target_kind", nullable = false, columnDefinition = "request_group_target_kind")
    private RequestGroupTargetKind targetKind;

    // QUERY members
    @Column(name = "datasource_id")
    private UUID datasourceId;

    @Column(name = "sql_text", columnDefinition = "text")
    private String sqlText;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "query_type", columnDefinition = "query_type")
    private QueryType queryType;

    @Column(nullable = false)
    private boolean transactional = false;

    // API_CALL members
    @Column(name = "api_connector_id")
    private UUID apiConnectorId;

    @Column(name = "operation_id", length = 255)
    private String operationId;

    @Column(length = 16)
    private String verb;

    @Column(name = "request_path", columnDefinition = "text")
    private String requestPath;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_headers", nullable = false, columnDefinition = "jsonb")
    private String requestHeaders = "{}";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "query_params", nullable = false, columnDefinition = "jsonb")
    private String queryParams = "{}";

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "body_type", columnDefinition = "api_body_type")
    private ApiBodyType bodyType;

    @Column(name = "request_content_type", length = 255)
    private String requestContentType;

    @Column(name = "request_body", columnDefinition = "text")
    private String requestBody;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "form_fields", nullable = false, columnDefinition = "jsonb")
    private String formFields = "[]";

    @Column(name = "binary_filename", length = 255)
    private String binaryFilename;

    // AI risk (per member)
    @Column(name = "ai_analysis_id")
    private UUID aiAnalysisId;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "ai_risk_level", columnDefinition = "risk_level")
    private RiskLevel aiRiskLevel;

    @Column(name = "ai_risk_score")
    private Integer aiRiskScore;

    // Execution outcome (per member)
    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "request_group_item_status")
    private RequestGroupItemStatus status = RequestGroupItemStatus.PENDING;

    @Column(name = "result_snapshot", columnDefinition = "text")
    private String resultSnapshot;

    @Column(name = "response_status_code")
    private Integer responseStatusCode;

    @Column(name = "rows_affected")
    private Long rowsAffected;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "executed_at")
    private Instant executedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
