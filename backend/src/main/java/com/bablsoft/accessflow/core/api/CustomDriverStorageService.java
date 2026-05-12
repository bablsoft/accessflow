package com.bablsoft.accessflow.core.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Persistence-layer abstraction for admin-uploaded JDBC driver JARs. Storing the file is a
 * separate concern from persisting the {@code custom_jdbc_driver} row: the service implementation
 * verifies SHA-256 while streaming, returns the on-disk relative path to write into the entity,
 * and exposes lookup / deletion for the proxy engine and the delete flow.
 */
public interface CustomDriverStorageService {

    /**
     * Streams {@code content} to disk, computing SHA-256 as bytes flow through. Rejects uploads
     * whose checksum mismatches {@code expectedSha256} or whose size exceeds {@code maxBytes}.
     *
     * @return the stored JAR metadata; {@link StoredCustomDriverJar#relativePath()} is suitable
     *         for persistence in {@code custom_jdbc_driver.storage_path}.
     */
    StoredCustomDriverJar store(UUID organizationId, UUID driverId, String expectedSha256,
                                long maxBytes, InputStream content) throws IOException;

    /** Absolute on-disk path for a stored driver, computed from the persisted relative path. */
    Path resolve(String storagePath);

    /** Deletes the JAR file. Idempotent. */
    void delete(String storagePath);

    /** Whether the JAR file exists on disk. */
    boolean exists(String storagePath);

    record StoredCustomDriverJar(String relativePath, Path absolutePath, String sha256,
                                 long sizeBytes) {
    }
}
