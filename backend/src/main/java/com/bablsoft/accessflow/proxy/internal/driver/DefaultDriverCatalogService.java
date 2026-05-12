package com.bablsoft.accessflow.proxy.internal.driver;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.DriverCatalogService;
import com.bablsoft.accessflow.core.api.DriverResolutionException;
import com.bablsoft.accessflow.core.api.DriverStatus;
import com.bablsoft.accessflow.core.api.DriverTypeInfo;
import com.bablsoft.accessflow.core.api.ResolvedDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Driver;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Default {@link DriverCatalogService} that resolves customer-database JDBC drivers on demand
 * from a static, allowlisted Maven registry. Each driver is downloaded over HTTPS, verified
 * against a pinned SHA-256, cached on disk, and loaded into a {@link DbType}-scoped child
 * {@link URLClassLoader} so HikariCP can instantiate it without polluting the application
 * classloader. The PostgreSQL entry is bundled (used for the AccessFlow internal database)
 * and resolves against the parent classloader without any download.
 */
@Service
class DefaultDriverCatalogService implements DriverCatalogService {

    private static final Logger log = LoggerFactory.getLogger(DefaultDriverCatalogService.class);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(60);

    private final DriverProperties properties;
    private final HttpClient httpClient;
    private final MessageSource messageSource;
    private final Map<DbType, ResolvedDriver> cache = new ConcurrentHashMap<>();

    DefaultDriverCatalogService(DriverProperties properties, MessageSource messageSource) {
        this.properties = properties;
        this.messageSource = messageSource;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public List<DriverTypeInfo> list() {
        return DriverRegistry.entries().values().stream()
                .map(this::toTypeInfo)
                .sorted(java.util.Comparator.comparing(t -> t.code().ordinal()))
                .collect(Collectors.toList());
    }

    @Override
    public ResolvedDriver resolve(DbType dbType) {
        var existing = cache.get(dbType);
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            var nowExisting = cache.get(dbType);
            if (nowExisting != null) {
                return nowExisting;
            }
            var entry = DriverRegistry.require(dbType);
            var resolved = entry.bundled() ? resolveBundled(entry) : resolveExternal(entry);
            cache.put(dbType, resolved);
            return resolved;
        }
    }

    private DriverTypeInfo toTypeInfo(DriverRegistryEntry entry) {
        DriverStatus status;
        if (entry.bundled() || cachedJar(entry).map(Files::isRegularFile).orElse(false)) {
            status = DriverStatus.READY;
        } else if (properties.offline()) {
            status = DriverStatus.UNAVAILABLE;
        } else if (cacheDirWritable()) {
            status = DriverStatus.AVAILABLE;
        } else {
            status = DriverStatus.UNAVAILABLE;
        }
        return new DriverTypeInfo(
                entry.dbType(),
                entry.displayName(),
                entry.iconUrl(),
                entry.defaultPort(),
                entry.defaultSslMode(),
                entry.jdbcUrlTemplate(),
                status,
                entry.bundled());
    }

    private ResolvedDriver resolveBundled(DriverRegistryEntry entry) {
        try {
            var loader = getClass().getClassLoader();
            var driverClass = Class.forName(entry.driverClassName(), true, loader);
            var driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
            return new ResolvedDriver(driver, loader, entry.driverClassName());
        } catch (ReflectiveOperationException e) {
            throw new DriverResolutionException(
                    entry.dbType(),
                    DriverResolutionException.Reason.UNAVAILABLE,
                    msg("error.datasource_driver_unavailable.unavailable", entry.dbType().name()),
                    e);
        }
    }

    private ResolvedDriver resolveExternal(DriverRegistryEntry entry) {
        var jarPath = ensureCachedJar(entry);
        try {
            var url = jarPath.toUri().toURL();
            var loader = new URLClassLoader(
                    "accessflow-jdbc-" + entry.dbType().name().toLowerCase(),
                    new URL[]{url},
                    getClass().getClassLoader());
            var driverClass = Class.forName(entry.driverClassName(), true, loader);
            var driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
            return new ResolvedDriver(driver, loader, entry.driverClassName());
        } catch (ReflectiveOperationException | IOException e) {
            throw new DriverResolutionException(
                    entry.dbType(),
                    DriverResolutionException.Reason.UNAVAILABLE,
                    msg("error.datasource_driver_unavailable.unavailable", entry.dbType().name()),
                    e);
        }
    }

    private Path ensureCachedJar(DriverRegistryEntry entry) {
        var jarPath = properties.cacheDir().resolve(entry.jarFileName());
        if (Files.isRegularFile(jarPath)) {
            verifyChecksum(entry, jarPath);
            return jarPath;
        }
        if (properties.offline()) {
            throw new DriverResolutionException(
                    entry.dbType(),
                    DriverResolutionException.Reason.OFFLINE_CACHE_MISS,
                    msg("error.datasource_driver_unavailable.offline_cache_miss",
                            entry.dbType().name(), entry.jarFileName()));
        }
        ensureCacheDir(entry);
        downloadJar(entry, jarPath);
        verifyChecksum(entry, jarPath);
        return jarPath;
    }

    private void ensureCacheDir(DriverRegistryEntry entry) {
        try {
            Files.createDirectories(properties.cacheDir());
        } catch (IOException e) {
            throw new DriverResolutionException(
                    entry.dbType(),
                    DriverResolutionException.Reason.CACHE_NOT_WRITABLE,
                    msg("error.datasource_driver_unavailable.cache_not_writable",
                            entry.dbType().name(), properties.cacheDir().toString()),
                    e);
        }
    }

    private void downloadJar(DriverRegistryEntry entry, Path jarPath) {
        var sourceUrl = properties.repositoryUrl() + "/" + entry.mavenPath();
        var tempPath = jarPath.resolveSibling(entry.jarFileName() + ".part");
        try {
            log.info("Resolving JDBC driver {} from {}", entry.dbType(), sourceUrl);
            var request = HttpRequest.newBuilder(URI.create(sourceUrl))
                    .timeout(DOWNLOAD_TIMEOUT)
                    .GET()
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                throw new DriverResolutionException(
                        entry.dbType(),
                        DriverResolutionException.Reason.DOWNLOAD_FAILED,
                        msg("error.datasource_driver_unavailable.download_failed",
                                entry.dbType().name(), response.statusCode(), sourceUrl));
            }
            try (InputStream in = response.body()) {
                Files.copy(in, tempPath, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tempPath, jarPath, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | InterruptedException e) {
            try {
                Files.deleteIfExists(tempPath);
            } catch (IOException ignored) {
                // best-effort
            }
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new DriverResolutionException(
                    entry.dbType(),
                    DriverResolutionException.Reason.DOWNLOAD_FAILED,
                    msg("error.datasource_driver_unavailable.download_failed",
                            entry.dbType().name(), -1, sourceUrl),
                    e);
        }
    }

    private void verifyChecksum(DriverRegistryEntry entry, Path jarPath) {
        var actual = sha256(jarPath, entry);
        if (!actual.equalsIgnoreCase(entry.sha256())) {
            try {
                Files.deleteIfExists(jarPath);
            } catch (IOException ignored) {
                // best-effort
            }
            throw new DriverResolutionException(
                    entry.dbType(),
                    DriverResolutionException.Reason.CHECKSUM_MISMATCH,
                    msg("error.datasource_driver_unavailable.checksum_mismatch",
                            entry.dbType().name(), entry.sha256(), actual));
        }
    }

    private String sha256(Path jarPath, DriverRegistryEntry entry) {
        try (var in = Files.newInputStream(jarPath)) {
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new DriverResolutionException(
                    entry.dbType(),
                    DriverResolutionException.Reason.UNAVAILABLE,
                    msg("error.datasource_driver_unavailable.unavailable", entry.dbType().name()),
                    e);
        }
    }

    private java.util.Optional<Path> cachedJar(DriverRegistryEntry entry) {
        if (entry.bundled()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(properties.cacheDir().resolve(entry.jarFileName()));
    }

    private boolean cacheDirWritable() {
        var dir = properties.cacheDir();
        if (Files.isDirectory(dir)) {
            return Files.isWritable(dir);
        }
        var parent = dir.getParent();
        return parent != null && Files.isDirectory(parent) && Files.isWritable(parent);
    }

    private String msg(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }
}
