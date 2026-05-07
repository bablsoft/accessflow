package com.partqam.accessflow.proxy.internal.driver;

import com.partqam.accessflow.core.api.DbType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Shared helper for integration tests: pre-populates a temp driver cache with the
 * test-classpath JDBC drivers so {@link DefaultDriverCatalogService} resolves them
 * without hitting Maven Central. Tests apply the returned path via
 * {@code @DynamicPropertySource} → {@code accessflow.drivers.cache-dir}.
 */
public final class DriverCacheTestSupport {

    private DriverCacheTestSupport() {
    }

    /**
     * Create a temp cache dir, copy the {@code mysql-connector-j} JAR from the test
     * classpath into it under the registry-pinned filename, and return the cache path.
     */
    public static Path prepareCacheWithMysql() {
        try {
            var dir = Files.createTempDirectory("accessflow-driver-cache-");
            var entry = DriverRegistry.require(DbType.MYSQL);
            var source = locateClasspathJar("com.mysql.cj.jdbc.Driver");
            Files.copy(source, dir.resolve(entry.jarFileName()),
                    StandardCopyOption.REPLACE_EXISTING);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path locateClasspathJar(String driverClassName) {
        try {
            var url = Class.forName(driverClassName).getProtectionDomain()
                    .getCodeSource().getLocation();
            return Path.of(url.toURI());
        } catch (ReflectiveOperationException | java.net.URISyntaxException e) {
            throw new IllegalStateException(
                    "Test classpath does not include " + driverClassName, e);
        }
    }
}
