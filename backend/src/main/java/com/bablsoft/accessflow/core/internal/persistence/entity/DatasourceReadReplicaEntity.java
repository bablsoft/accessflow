package com.bablsoft.accessflow.core.internal.persistence.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "datasource_read_replicas")
@Getter
@Setter
@NoArgsConstructor
public class DatasourceReadReplicaEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "datasource_id", nullable = false)
    private DatasourceEntity datasource;

    @Column(name = "jdbc_url", nullable = false, columnDefinition = "TEXT")
    private String jdbcUrl;

    @Column(length = 255)
    private String username;

    @JsonIgnore
    @Column(name = "password_encrypted", columnDefinition = "TEXT")
    private String passwordEncrypted;

    @Column(nullable = false)
    private int position = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
