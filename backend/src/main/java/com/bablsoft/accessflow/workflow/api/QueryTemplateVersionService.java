package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.UUID;

/**
 * Read access to the immutable version history of a saved query template (AF-442). Both operations
 * enforce visibility against the <em>current</em> parent template (same rules as
 * {@link QueryTemplateService#get}); a snapshot's own point-in-time visibility is never used for
 * access control, so a template flipped {@code TEAM → PRIVATE} does not leak its old TEAM snapshots.
 *
 * <p>Restoring a version is a template mutation and therefore lives on
 * {@link QueryTemplateService#restoreVersion}, not here.
 */
public interface QueryTemplateVersionService {

    PageResponse<QueryTemplateVersionView> listVersions(UUID templateId, UUID organizationId,
                                                        UUID callerUserId, PageRequest pageRequest);

    QueryTemplateVersionView getVersion(UUID templateId, UUID versionId, UUID organizationId,
                                        UUID callerUserId);
}
