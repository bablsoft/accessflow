package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface DatasourceLookupService {

    Optional<DatasourceConnectionDescriptor> findById(UUID datasourceId);

    /** @return the id + name of a single datasource, or empty when it does not exist. */
    Optional<DatasourceRef> findRef(UUID datasourceId);

    /**
     * @return id + name references to every <em>active</em> datasource in the organization,
     * ordered by name. Unlike the per-user datasource list this is not scoped to existing
     * permissions — the access (JIT) module uses it to let any user pick a datasource to request
     * access to. Carries no connection details.
     */
    List<DatasourceRef> findActiveRefsByOrganization(UUID organizationId);

    /**
     * @return references to every datasource (any state) whose {@code ai_config_id} equals the
     * supplied value. Used by the AI module to surface "in use" details in delete errors.
     */
    List<DatasourceRef> findRefsByAiConfigId(UUID aiConfigId);

    /**
     * @return how many datasources currently reference each AI config id in the input.
     * Datasource state filter: any state — admins should see the full picture in the list view.
     */
    Map<UUID, Integer> countsByAiConfigIds(Set<UUID> aiConfigIds);

    /**
     * @return distinct {@code ai_config_id}s referenced by the organization's active datasources
     * with {@code ai_analysis_enabled = true}. Used by the AI module to compute the
     * "AI configured for this org" setup-progress signal.
     */
    Set<UUID> findActiveAiAnalysisAiConfigIdsByOrganization(UUID organizationId);
}
