package com.bablsoft.accessflow.deploygov.internal.persistence.entity;

import com.bablsoft.accessflow.deploygov.api.VerificationCheckKind;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
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

/** One post-deployment verification sample — one check, one instant (#1023). */
@Entity
@Table(name = "deployment_verifications")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class DeploymentVerificationEntity {

    @Id
    private UUID id;

    @Column(name = "deployment_request_id", nullable = false)
    private UUID deploymentRequestId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "check_name", nullable = false, length = 64)
    private String checkName;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "check_kind", nullable = false, columnDefinition = "verification_check_kind")
    private VerificationCheckKind checkKind;

    @Column(name = "sampled_at", nullable = false)
    private Instant sampledAt;

    @Column(name = "passed", nullable = false)
    private boolean passed;

    @Column(name = "observed_value", columnDefinition = "text")
    private String observedValue;

    @Column(name = "detail", columnDefinition = "text")
    private String detail;
}
