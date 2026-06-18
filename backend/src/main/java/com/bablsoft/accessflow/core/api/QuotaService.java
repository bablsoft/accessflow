package com.bablsoft.accessflow.core.api;

import java.util.UUID;

/**
 * Enforces per-org quotas (AF-456). Each {@code check*} method throws {@link QuotaExceededException}
 * when adding one more resource would exceed the organization's configured limit. A null or 0 limit
 * means "unlimited" and the check is a no-op. Callers invoke the check immediately before creating
 * the resource. Lives in {@code core.api} so the workflow/security modules can call it without
 * reaching into {@code core.internal}.
 */
public interface QuotaService {

    void checkDatasourceQuota(UUID organizationId);

    void checkUserQuota(UUID organizationId);

    void checkQueryQuota(UUID organizationId);
}
