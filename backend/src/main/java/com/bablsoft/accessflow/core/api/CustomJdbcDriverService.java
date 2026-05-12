package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages admin-uploaded JDBC driver JARs for an organization. Lifecycle:
 * register → list / lookup → delete. The proxy module reads the stored descriptor via
 * {@link #findById(UUID, UUID)} when building a Hikari pool for a datasource that references
 * the driver.
 */
public interface CustomJdbcDriverService {

    /**
     * Persists a new uploaded driver. Validates metadata, computes and verifies SHA-256 of the
     * uploaded bytes, probe-loads the driver class to confirm it implements {@link java.sql.Driver},
     * and stores the JAR on disk under the configured driver cache directory.
     */
    CustomDriverView register(UploadCustomDriverCommand command);

    List<CustomDriverView> list(UUID organizationId);

    Optional<CustomDriverDescriptor> findById(UUID id, UUID organizationId);

    CustomDriverView get(UUID id, UUID organizationId);

    /**
     * Deletes the driver entity, removes the JAR file from disk, and evicts the cached
     * classloader. Throws {@link CustomDriverInUseException} if any datasource still references
     * the driver.
     */
    void delete(UUID id, UUID organizationId);
}
