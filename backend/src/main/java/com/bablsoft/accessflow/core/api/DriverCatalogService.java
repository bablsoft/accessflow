package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.UUID;

public interface DriverCatalogService {

    /** Bundled drivers only — backwards-compatible with pre-#94 callers. */
    List<DriverTypeInfo> list();

    /**
     * Bundled drivers plus the organization's admin-uploaded drivers. Bundled rows precede
     * uploaded rows; uploaded rows are sorted by upload time (newest first).
     */
    List<DriverTypeInfo> list(UUID organizationId, List<CustomDriverDescriptor> uploaded);

    /**
     * Resolve a bundled driver. Used by datasources whose {@code custom_driver_id} is null and
     * whose {@code db_type} is one of the bundled five.
     */
    ResolvedDriver resolve(DbType dbType);

    /**
     * Resolve an admin-uploaded driver. The JAR is loaded into a {@link ClassLoader} keyed by
     * {@link CustomDriverDescriptor#id()} so two datasources pointing at different uploaded
     * drivers (even with identical {@link DbType}) get isolated classloaders.
     */
    ResolvedDriver resolveCustom(CustomDriverDescriptor descriptor);

    /** Drops any cached classloader for the given uploaded driver. Idempotent. */
    void evictCustom(UUID customDriverId);
}
