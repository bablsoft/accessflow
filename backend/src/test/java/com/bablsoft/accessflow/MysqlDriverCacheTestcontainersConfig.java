package com.bablsoft.accessflow;

import com.bablsoft.accessflow.proxy.internal.driver.DriverCacheTestSupport;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

/**
 * Points {@code accessflow.drivers.cache-dir} at a cache pre-populated with the MySQL JDBC driver,
 * for the tests that resolve a MySQL datasource without reaching Maven Central.
 *
 * <p>Declares no container — {@code @ImportTestcontainers} on a class with no container fields is
 * legal and still imports the dynamic properties. The point is the cache key: four test classes
 * each carried an identical per-class {@code @DynamicPropertySource}, which bought each of them a
 * private Spring context. Importing this holder instead
 * ({@code @ImportTestcontainers({TestcontainersConfig.class, MysqlDriverCacheTestcontainersConfig.class})})
 * gives all four the same key, so they share one context and one cache directory.
 *
 * <p>See {@link TestcontainersConfig#accessFlowTestKeys} for why a holder costs nothing and a
 * per-class method costs a full context.
 */
public final class MysqlDriverCacheTestcontainersConfig {

    private static final Path CACHE_DIR = DriverCacheTestSupport.prepareCacheWithMysql();

    private MysqlDriverCacheTestcontainersConfig() {}

    @DynamicPropertySource
    static void driverCache(DynamicPropertyRegistry registry) {
        registry.add("accessflow.drivers.cache-dir", CACHE_DIR::toString);
    }
}
