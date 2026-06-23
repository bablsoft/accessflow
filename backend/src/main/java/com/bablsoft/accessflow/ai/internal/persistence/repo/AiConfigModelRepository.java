package com.bablsoft.accessflow.ai.internal.persistence.repo;

import com.bablsoft.accessflow.ai.internal.persistence.entity.AiConfigModelEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AiConfigModelRepository extends JpaRepository<AiConfigModelEntity, UUID> {

    List<AiConfigModelEntity> findByAiConfigIdOrderBySortOrderAsc(UUID aiConfigId);

    List<AiConfigModelEntity> findByAiConfigIdAndEnabledTrueOrderBySortOrderAsc(UUID aiConfigId);

    void deleteByAiConfigId(UUID aiConfigId);
}
