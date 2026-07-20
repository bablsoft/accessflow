package com.bablsoft.accessflow.apigov.internal.persistence.entity;

import com.bablsoft.accessflow.apigov.api.ApiBodyType;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "api_requests")
@Getter
@Setter
@NoArgsConstructor
public class ApiRequestEntity {

    @Id
    private UUID id;

    @Column(name = "connector_id", nullable = false)
    private UUID connectorId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "submitted_by", nullable = false)
    private UUID submittedBy;

    @Column(name = "operation_id", columnDefinition = "text")
    private String operationId;

    @Column(nullable = false, length = 16)
    private String verb;

    @Column(name = "request_path", nullable = false, columnDefinition = "text")
    private String requestPath;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_headers", nullable = false, columnDefinition = "jsonb")
    private String requestHeaders = "{}";

    @Column(name = "request_body", columnDefinition = "text")
    private String requestBody;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "body_type", nullable = false, columnDefinition = "api_body_type")
    private ApiBodyType bodyType = ApiBodyType.RAW;

    @Column(name = "request_content_type", columnDefinition = "text")
    private String requestContentType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "query_params", nullable = false, columnDefinition = "jsonb")
    private String queryParams = "{}";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "form_fields", nullable = false, columnDefinition = "jsonb")
    private String formFields = "[]";

    @Column(name = "binary_filename", columnDefinition = "text")
    private String binaryFilename;

    // AF-613: submitter-supplied values for connector variables marked overridable. Persisted rather
    // than transient because a reviewer approves what is stored, and submit -> review -> execute is
    // asynchronous — an unstored override would let the effective request change after approval.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variable_overrides", nullable = false, columnDefinition = "jsonb")
    private String variableOverrides = "{}";

    @Column(name = "is_write", nullable = false)
    private boolean write = false;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "query_status")
    private QueryStatus status = QueryStatus.PENDING_AI;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "submission_reason", nullable = false, columnDefinition = "submission_reason")
    private SubmissionReason submissionReason = SubmissionReason.USER_SUBMITTED;

    @Column(columnDefinition = "text")
    private String justification;

    @Column(name = "ai_analysis_id")
    private UUID aiAnalysisId;

    @Column(name = "scheduled_for")
    private Instant scheduledFor;

    @Column(name = "required_approvals", nullable = false)
    private int requiredApprovals = 1;

    @Column(name = "response_status_code")
    private Integer responseStatusCode;

    @Column(name = "response_duration_ms")
    private Integer responseDurationMs;

    @Column(name = "response_bytes")
    private Long responseBytes;

    @Column(name = "response_truncated", nullable = false)
    private boolean responseTruncated = false;

    @Column(name = "response_snapshot", columnDefinition = "text")
    private String responseSnapshot;

    @Column(name = "response_content_type", columnDefinition = "text")
    private String responseContentType;

    @Column(name = "trace_id", columnDefinition = "text")
    private String traceId;

    @Column(name = "span_id", columnDefinition = "text")
    private String spanId;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "submitted_ip", length = 45)
    private String submittedIp;

    @Column(name = "submitted_user_agent", columnDefinition = "text")
    private String submittedUserAgent;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    // AF-518: connector masking policy ids applied to the stored response snapshot. Transient — not
    // persisted on api_requests; surfaced from the executor to the API_REQUEST_EXECUTED audit row.
    @Transient
    private List<UUID> appliedMaskingPolicyIds = List.of();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
