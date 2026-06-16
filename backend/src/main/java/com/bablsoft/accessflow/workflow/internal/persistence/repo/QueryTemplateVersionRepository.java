package com.bablsoft.accessflow.workflow.internal.persistence.repo;

import com.bablsoft.accessflow.workflow.internal.persistence.entity.QueryTemplateVersionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface QueryTemplateVersionRepository
        extends JpaRepository<QueryTemplateVersionEntity, UUID> {

    Page<QueryTemplateVersionEntity> findByTemplateIdOrderByVersionNumberDesc(UUID templateId,
                                                                              Pageable pageable);

    Optional<QueryTemplateVersionEntity> findByTemplateIdAndId(UUID templateId, UUID id);

    Optional<QueryTemplateVersionEntity> findTopByTemplateIdOrderByVersionNumberDesc(UUID templateId);
}
