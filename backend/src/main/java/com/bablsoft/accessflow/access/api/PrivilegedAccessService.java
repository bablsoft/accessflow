package com.bablsoft.accessflow.access.api;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.UUID;

/**
 * The standing privileged-access report (#968): every active user in an organization who can
 * reach data <em>without</em> appearing in any permission table — {@code QUERY_ADMIN} holders
 * (system or custom role) and break-glass grantees — one row per identity, with the evidence of
 * them having used it.
 *
 * <p>Read-only and advisory. Nothing revokes anything on the strength of this report, and no
 * decision path reads it.
 */
public interface PrivilegedAccessService {

    /** Rows sorted by email; a user whose only access is an ordinary grant never appears. */
    PageResponse<PrivilegedAccessRow> report(UUID organizationId, PrivilegedAccessQuery query,
                                             PageRequest pageRequest);
}
