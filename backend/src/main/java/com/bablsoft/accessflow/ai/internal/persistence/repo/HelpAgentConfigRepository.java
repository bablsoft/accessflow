package com.bablsoft.accessflow.ai.internal.persistence.repo;

import com.bablsoft.accessflow.ai.internal.persistence.entity.HelpAgentConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HelpAgentConfigRepository extends JpaRepository<HelpAgentConfigEntity, UUID> {

    Optional<HelpAgentConfigEntity> findByOrganizationId(UUID organizationId);

    /** Every organization with the agent switched on — the indexer's work list. */
    List<HelpAgentConfigEntity> findAllByEnabledTrue();
}
