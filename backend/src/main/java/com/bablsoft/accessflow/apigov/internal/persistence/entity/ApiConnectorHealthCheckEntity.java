package com.bablsoft.accessflow.apigov.internal.persistence.entity;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "api_connector_health_checks")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class ApiConnectorHealthCheckEntity {

    @Id
    private UUID id;

    @Column(name = "connector_id", nullable = false)
    private UUID connectorId;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "message", columnDefinition = "text")
    private String message;

    @Column(name = "duration_ms", nullable = false)
    private int durationMs = 0;
}
