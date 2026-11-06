package com.bablsoft.accessflow.apigov.internal.persistence.repo;

import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiSchemaRevisionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiSchemaRevisionRepository extends JpaRepository<ApiSchemaRevisionEntity, UUID> {

    List<ApiSchemaRevisionEntity> findBySchemaIdOrderByFetchedAtDesc(UUID schemaId);

    Optional<ApiSchemaRevisionEntity> findFirstBySchemaIdOrderByFetchedAtDesc(UUID schemaId);

    Optional<ApiSchemaRevisionEntity> findBySchemaIdAndContentHash(UUID schemaId, String contentHash);
}
