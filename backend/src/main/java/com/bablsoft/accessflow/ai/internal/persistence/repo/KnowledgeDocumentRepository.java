package com.bablsoft.accessflow.ai.internal.persistence.repo;

import com.bablsoft.accessflow.ai.internal.persistence.entity.KnowledgeDocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocumentEntity, UUID> {

    List<KnowledgeDocumentEntity> findAllByAiConfigIdOrderByCreatedAtDesc(UUID aiConfigId);

    Optional<KnowledgeDocumentEntity> findByIdAndAiConfigIdAndOrganizationId(UUID id, UUID aiConfigId,
                                                                             UUID organizationId);

    long countByAiConfigId(UUID aiConfigId);
}
