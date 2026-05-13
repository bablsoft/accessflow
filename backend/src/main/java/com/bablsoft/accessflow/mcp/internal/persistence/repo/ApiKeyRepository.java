package com.bablsoft.accessflow.mcp.internal.persistence.repo;

import com.bablsoft.accessflow.mcp.internal.persistence.entity.ApiKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, UUID> {

    List<ApiKeyEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<ApiKeyEntity> findByKeyHash(String keyHash);

    boolean existsByUserIdAndName(UUID userId, String name);

    @Modifying
    @Query("update ApiKeyEntity k set k.lastUsedAt = :now where k.id = :id")
    void touchLastUsedAt(@Param("id") UUID id, @Param("now") Instant now);
}
