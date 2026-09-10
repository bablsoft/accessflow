package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.UUID;

/**
 * The reverse index behind every access surface (issue AF-859): every other lookup answers "what can
 * this user reach", and this one answers the question auditors and incident responders actually ask
 * — <em>who can write to this table</em>.
 *
 * <p>Answers <em>who may submit</em>, not <em>what would happen</em>. For the latter the caller
 * follows up with one {@link AccessSimulationService} call per user of interest; keeping the two
 * apart is what stops this from turning into an N-user simulation loop.
 *
 * <p>Table matching delegates to the enforcement gate's own normalization and coverage rules. A
 * second implementation would drift from them, and a report that confidently disagrees with the gate
 * is the whole failure mode of this feature.
 */
public interface EffectiveAccessService {

    /**
     * @throws com.bablsoft.accessflow.core.api.DatasourceNotFoundException when the datasource is
     *         missing or belongs to another organization
     * @throws InvalidEffectiveAccessQueryException when {@code table} normalizes away to nothing
     */
    PageResponse<EffectiveAccessRow> report(UUID organizationId, EffectiveAccessQuery query,
                                            PageRequest pageRequest);
}
