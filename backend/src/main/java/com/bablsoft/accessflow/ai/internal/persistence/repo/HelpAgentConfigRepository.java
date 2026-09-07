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

    /**
     * Every organization whose agent is bound to this {@code ai_config}. Used when the configuration's
     * RAG settings change, since the stored help vectors were produced by its embedding model and
     * live in its store. Backed by {@code help_agent_config_ai_config_id_idx}.
     */
    List<HelpAgentConfigEntity> findAllByAiConfigId(UUID aiConfigId);
}
