package com.bablsoft.accessflow.proxy.internal.driver;

import com.bablsoft.accessflow.core.api.CustomDriverChecksumMismatchException;
import com.bablsoft.accessflow.core.api.CustomDriverStorageService;
import com.bablsoft.accessflow.core.api.CustomDriverTooLargeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Default {@link CustomDriverStorageService} backed by the local filesystem. Layout under the
 * configured driver cache directory:
 *
 * <pre>{@code
 * ${ACCESSFLOW_DRIVER_CACHE}/
 *   custom/
 *     {organizationId}/
 *       {driverId}.jar
 * }</pre>
 *
 * Writes are streamed: the SHA-256 is computed as bytes are written to a temp file, the
 * checksum is compared against the value declared by the admin, and the file is atomically
 * moved into place only on a successful match. This avoids buffering large JARs in memory and
 * ensures we never persist a corrupted upload.
 */
@Component
class CustomDriverStorage implements CustomDriverStorageService {

    private static final Logger log = LoggerFactory.getLogger(CustomDriverStorage.class);
    private static final String SUBDIR = "custom";
    private static final int COPY_BUFFER = 8192;

    private final DriverProperties properties;

    CustomDriverStorage(DriverProperties properties) {
        this.properties = properties;
    }

    @Override
    public StoredCustomDriverJar store(UUID organizationId, UUID driverId, String expectedSha256,
                                       long maxBytes, InputStream content) throws IOException {
        Path orgDir = orgDir(organizationId);
        Files.createDirectories(orgDir);
        Path finalPath = orgDir.resolve(driverId + ".jar");
        Path tempPath = finalPath.resolveSibling(driverId + ".jar.part");
        MessageDigest digest = newDigest();
        long total = 0;
        try (OutputStream out = Files.newOutputStream(tempPath,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[COPY_BUFFER];
            int read;
            while ((read = content.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    deleteQuietly(tempPath);
                    throw new CustomDriverTooLargeException(total, maxBytes);
                }
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        }
        String actualSha = HexFormat.of().formatHex(digest.digest());
        if (!actualSha.equalsIgnoreCase(expectedSha256)) {
            deleteQuietly(tempPath);
            throw new CustomDriverChecksumMismatchException(expectedSha256, actualSha);
        }
        Files.move(tempPath, finalPath, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        var relative = properties.cacheDir().relativize(finalPath).toString();
        return new StoredCustomDriverJar(relative, finalPath, actualSha, total);
    }

    @Override
    public Path resolve(String storagePath) {
        return properties.cacheDir().resolve(storagePath);
    }

    @Override
    public void delete(String storagePath) {
        try {
            Files.deleteIfExists(resolve(storagePath));
        } catch (IOException e) {
            log.warn("Failed to delete custom driver JAR at {}: {}", storagePath, e.getMessage());
        }
    }

    @Override
    public boolean exists(String storagePath) {
        return Files.isRegularFile(resolve(storagePath));
    }

    private Path orgDir(UUID organizationId) {
        return properties.cacheDir().resolve(SUBDIR).resolve(organizationId.toString());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed on every JRE.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }
}
