package com.bablsoft.accessflow.ai.internal.persistence.repo;

import com.bablsoft.accessflow.ai.internal.persistence.entity.HelpAgentConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HelpAgentConfigRepository extends JpaRepository<HelpAgentConfigEntity, UUID> {

    Optional<HelpAgentConfigEntity> findByOrganizationId(UUID organizationId);

    /**
     * Every organization with the agent switched on — the indexer's work list. May include a row
     * whose {@code aiConfigId} is null (the bound configuration was deleted); callers must skip
     * those, since an unbound agent has no model to embed or answer with.
     */
    List<HelpAgentConfigEntity> findAllByEnabledTrue();
}
