package com.bablsoft.accessflow.attestation.internal.persistence.entity;

import com.bablsoft.accessflow.attestation.api.AttestationItemCloseReason;
import com.bablsoft.accessflow.attestation.api.AttestationItemDecision;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "attestation_item")
@Getter
@Setter
@NoArgsConstructor
public class AttestationItemEntity {

    @Id
    private UUID id;

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "permission_id", nullable = false)
    private UUID permissionId;

    @Column(name = "datasource_id", nullable = false)
    private UUID datasourceId;

    @Column(name = "datasource_name", nullable = false, columnDefinition = "text")
    private String datasourceName;

    @Column(name = "subject_user_id", nullable = false)
    private UUID subjectUserId;

    @Column(name = "subject_user_email", nullable = false, columnDefinition = "text")
    private String subjectUserEmail;

    @Column(name = "subject_user_display_name", columnDefinition = "text")
    private String subjectUserDisplayName;

    @Column(name = "can_read", nullable = false)
    private boolean canRead = false;

    @Column(name = "can_write", nullable = false)
    private boolean canWrite = false;

    @Column(name = "can_ddl", nullable = false)
    private boolean canDdl = false;

    @Column(name = "can_break_glass", nullable = false)
    private boolean canBreakGlass = false;

    @Column(name = "permission_expires_at")
    private Instant permissionExpiresAt;

    @Column(name = "permission_created_at")
    private Instant permissionCreatedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "permission_snapshot", nullable = false, columnDefinition = "jsonb")
    private String permissionSnapshot;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "attestation_item_decision")
    private AttestationItemDecision decision = AttestationItemDecision.PENDING;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "close_reason", columnDefinition = "attestation_item_close_reason")
    private AttestationItemCloseReason closeReason;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_comment", columnDefinition = "text")
    private String decisionComment;

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
