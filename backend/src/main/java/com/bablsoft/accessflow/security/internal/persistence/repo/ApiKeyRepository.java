package com.bablsoft.accessflow.security.internal.persistence.repo;

import com.bablsoft.accessflow.security.internal.persistence.entity.ApiKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, UUID> {

    List<ApiKeyEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<ApiKeyEntity> findByUserIdInOrderByCreatedAtDesc(Collection<UUID> userIds);

    Optional<ApiKeyEntity> findByKeyHash(String keyHash);

    Optional<ApiKeyEntity> findByUserIdAndName(UUID userId, String name);

    boolean existsByUserIdAndName(UUID userId, String name);

    @Modifying
    @Query("update ApiKeyEntity k set k.lastUsedAt = :now where k.id = :id")
    void touchLastUsedAt(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Demotes every other key of {@code userId} to an ordinary key (#871): a service account has
     * at most one bootstrap-declared key, so a re-import under a new name must un-flag the old row.
     */
    @Modifying
    @Query("update ApiKeyEntity k set k.bootstrapDeclared = false "
            + "where k.userId = :userId and k.id <> :keptKeyId and k.bootstrapDeclared = true")
    int clearBootstrapDeclaredForOtherKeys(@Param("userId") UUID userId, @Param("keptKeyId") UUID keptKeyId);
}
