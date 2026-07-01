package com.bablsoft.accessflow.apigov.api;

import java.util.List;
import java.util.UUID;

/**
 * Resolves the connector masking policies that <em>apply</em> to one API-call execution (AF-518). A
 * policy applies when the requester is <em>not</em> revealed by it — i.e. the requester's role, none
 * of their group ids, and their user id are absent from the policy's reveal lists. Disabled policies
 * are ignored. Returns an empty list when no enabled policy covers the connector or when every
 * covering policy reveals the requester.
 */
public interface ApiConnectorMaskingResolutionService {

    List<ResolvedApiMask> resolveApplicable(UUID organizationId, UUID connectorId,
                                            UUID requesterUserId);
}
