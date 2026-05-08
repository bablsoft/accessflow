package com.partqam.accessflow.core.internal.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "localization_config")
@Getter
@Setter
@NoArgsConstructor
public class LocalizationConfigEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, unique = true)
    private UUID organizationId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "available_languages", nullable = false, columnDefinition = "text[]")
    private List<String> availableLanguages = new ArrayList<>();

    @Column(name = "default_language", nullable = false, length = 20)
    private String defaultLanguage;

    @Column(name = "ai_review_language", nullable = false, length = 20)
    private String aiReviewLanguage;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
