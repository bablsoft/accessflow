package com.bablsoft.accessflow.core.internal.persistence.repo;

import com.bablsoft.accessflow.core.internal.persistence.entity.AiAnalysisModelResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AiAnalysisModelResultRepository
        extends JpaRepository<AiAnalysisModelResultEntity, UUID> {

    List<AiAnalysisModelResultEntity> findByAiAnalysisId(UUID aiAnalysisId);
}
