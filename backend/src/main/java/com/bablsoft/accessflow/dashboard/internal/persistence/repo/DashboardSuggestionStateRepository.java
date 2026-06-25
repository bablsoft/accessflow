package com.bablsoft.accessflow.dashboard.internal.persistence.repo;

import com.bablsoft.accessflow.dashboard.internal.persistence.entity.DashboardSuggestionStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DashboardSuggestionStateRepository
        extends JpaRepository<DashboardSuggestionStateEntity, UUID> {

    List<DashboardSuggestionStateEntity> findByOrganizationIdAndUserIdAndAiAnalysisIdIn(
            UUID organizationId, UUID userId, Collection<UUID> aiAnalysisIds);

    Optional<DashboardSuggestionStateEntity>
            findByOrganizationIdAndUserIdAndAiAnalysisIdAndSuggestionIndex(
                    UUID organizationId, UUID userId, UUID aiAnalysisId, int suggestionIndex);
}
