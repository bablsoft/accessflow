package com.bablsoft.accessflow.proxy.internal.driver;

import com.bablsoft.accessflow.core.api.ConnectorNotFoundException;
import com.bablsoft.accessflow.core.api.CustomDriverDescriptor;
import com.bablsoft.accessflow.core.api.CustomDriverStorageService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.DriverCatalogService;
import com.bablsoft.accessflow.core.api.DriverResolutionException;
import com.bablsoft.accessflow.core.api.DriverStatus;
import com.bablsoft.accessflow.core.api.DriverTypeInfo;
import com.bablsoft.accessflow.core.api.ResolvedDriver;
import com.bablsoft.accessflow.core.api.SslMode;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link DriverCatalogService} that resolves customer-database JDBC drivers on demand
 * from the declarative {@link ConnectorCatalog} (loaded from {@code connectors/<id>/connector.json}
 * manifests). Each non-bundled driver is downloaded over HTTPS, verified against the manifest's
 * pinned SHA-256, cached on disk, and loaded into a child {@link URLClassLoader} so HikariCP can
 * instantiate it without polluting the application classloader. The PostgreSQL connector is
 * {@code bundled} (used for the AccessFlow internal database) and resolves against the parent
 * classloader without any download.
 *
 * <p>Three resolution lanes share the on-disk cache:
 * <ul>
 *   <li>{@link #resolve(DbType)} — one of the five first-class dialects, keyed by {@link DbType}.</li>
 *   <li>{@link #resolveConnector(String)} — a catalog connector ({@link DbType#CUSTOM} engine such
 *       as ClickHouse), keyed by connector id.</li>
 *   <li>{@link #resolveCustom(CustomDriverDescriptor)} — an admin-uploaded JAR, keyed by the
 *       upload's UUID so two datasources pointing at different JARs stay isolated.</li>
 * </ul>
 */
@Service
class DefaultDriverCatalogService implements DriverCatalogService {

    private static final Logger log = LoggerFactory.getLogger(DefaultDriverCatalogService.class);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(60);
    private static final String CUSTOM_DRIVER_DISPLAY_NAME_FORMAT = "%s (custom %s)";
    private static final SslMode CUSTOM_DEFAULT_SSL = SslMode.DISABLE;
    private static final int CUSTOM_DEFAULT_PORT = 0;

    private final DriverProperties properties;
    private final MessageSource messageSource;
    private final CustomDriverStorageService customDriverStorage;
    private final ConnectorCatalog catalog;
    private final HttpClient httpClient;
    private final Map<DbType, ResolvedDriver> bundledCache = new ConcurrentHashMap<>();
    private final Map<String, ResolvedDriver> connectorCache = new ConcurrentHashMap<>();
    private final Map<UUID, ResolvedDriver> customCache = new ConcurrentHashMap<>();

    DefaultDriverCatalogService(DriverProperties properties, MessageSource messageSource,
                                CustomDriverStorageService customDriverStorage,
                                ConnectorCatalog catalog) {
        this.properties = properties;
        this.messageSource = messageSource;
        this.customDriverStorage = customDriverStorage;
        this.catalog = catalog;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public List<DriverTypeInfo> list() {
        return list(null, List.of());
    }

    @Override
    public List<DriverTypeInfo> list(UUID organizationId, List<CustomDriverDescriptor> uploaded) {
        List<DriverTypeInfo> rows = new ArrayList<>();
        for (var manifest : catalog.all()) {
            rows.add(manifest.dbType() == DbType.CUSTOM
                    ? toConnectorWizardTypeInfo(manifest)
                    : toBundledTypeInfo(manifest));
        }
        if (uploaded != null) {
            uploaded.stream().map(this::toUploadedTypeInfo).forEach(rows::add);
        }
        return rows;
    }

    @Override
    public List<DriverTypeInfo> listConnectors() {
        List<DriverTypeInfo> rows = new ArrayList<>();
        for (var manifest : catalog.all()) {
            rows.add(toConnectorMarketplaceTypeInfo(manifest));
        }
        return rows;
    }

    @Override
    public DriverTypeInfo install(String connectorId) {
        var manifest = catalog.byId(connectorId)
                .orElseThrow(() -> new ConnectorNotFoundException(connectorId));
        if (!manifest.bundled()) {
            ensureCachedJar(manifest);
        }
        return toConnectorMarketplaceTypeInfo(manifest);
    }

    @Override
    public ResolvedDriver resolveConnector(String connectorId) {
        var cached = connectorCache.get(connectorId);
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            var nowCached = connectorCache.get(connectorId);
            if (nowCached != null) {
                return nowCached;
            }
            var manifest = catalog.byId(connectorId)
                    .orElseThrow(() -> new ConnectorNotFoundException(connectorId));
            var resolved = manifest.bundled()
                    ? resolveBundled(manifest)
                    : resolveExternal(manifest, "accessflow-jdbc-connector-" + connectorId);
            connectorCache.put(connectorId, resolved);
            return resolved;
        }
    }

    @Override
    public String connectorJdbcUrl(String connectorId, String host, int port, String databaseName) {
        var manifest = catalog.byId(connectorId)
                .orElseThrow(() -> new ConnectorNotFoundException(connectorId));
        return manifest.jdbcUrlTemplate()
                .replace("{host}", host == null ? "" : host)
                .replace("{port}", Integer.toString(port))
                .replace("{database_name}", databaseName == null ? "" : databaseName);
    }

    @Override
    public ResolvedDriver resolve(DbType dbType) {
        if (dbType == DbType.CUSTOM) {
            // CUSTOM has no dialect registry entry — callers must resolve via resolveCustom /
            // resolveConnector.
            throw new DriverResolutionException(
                    dbType,
                    DriverResolutionException.Reason.UNAVAILABLE,
                    msg("error.datasource_driver_unavailable.unavailable", dbType.name()));
        }
        var existing = bundledCache.get(dbType);
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            var nowExisting = bundledCache.get(dbType);
            if (nowExisting != null) {
                return nowExisting;
            }
            var manifest = catalog.requireByDbType(dbType);
            var resolved = manifest.bundled()
                    ? resolveBundled(manifest)
                    : resolveExternal(manifest, "accessflow-jdbc-" + dbType.name().toLowerCase());
            bundledCache.put(dbType, resolved);
            return resolved;
        }
    }

    @Override
    public ResolvedDriver resolveCustom(CustomDriverDescriptor descriptor) {
        var cached = customCache.get(descriptor.id());
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            var nowCached = customCache.get(descriptor.id());
            if (nowCached != null) {
                return nowCached;
            }
            var jarPath = customDriverStorage.resolve(descriptor.storagePath());
            verifyCustomChecksum(descriptor, jarPath);
            try {
                var url = jarPath.toUri().toURL();
                var loader = new URLClassLoader(
                        "accessflow-jdbc-custom-" + descriptor.id(),
                        new URL[]{url},
                        getClass().getClassLoader());
                var driverClass = Class.forName(descriptor.driverClass(), true, loader);
                if (!Driver.class.isAssignableFrom(driverClass)) {
                    closeQuietly(loader);
                    throw new DriverResolutionException(
                            descriptor.targetDbType(),
                            DriverResolutionException.Reason.UNAVAILABLE,
                            msg("error.custom_driver.invalid_class", descriptor.driverClass()));
                }
                var driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
                var resolved = new ResolvedDriver(driver, loader, descriptor.driverClass());
                customCache.put(descriptor.id(), resolved);
                return resolved;
            } catch (ReflectiveOperationException | IOException e) {
                throw new DriverResolutionException(
                        descriptor.targetDbType(),
                        DriverResolutionException.Reason.UNAVAILABLE,
                        msg("error.custom_driver.invalid_class", descriptor.driverClass()),
                        e);
            }
        }
    }

    @Override
    public void evictCustom(UUID customDriverId) {
        var removed = customCache.remove(customDriverId);
        if (removed != null && removed.classLoader() instanceof URLClassLoader urlLoader) {
            closeQuietly(urlLoader);
        }
    }

    private DriverTypeInfo toBundledTypeInfo(ConnectorManifest manifest) {
        return DriverTypeInfo.bundled(
                manifest.dbType(),
                manifest.name(),
                manifest.iconUrl(),
                manifest.defaultPort(),
                manifest.defaultSslMode(),
                manifest.jdbcUrlTemplate(),
                status(manifest),
                manifest.bundled());
    }

    private DriverTypeInfo toConnectorWizardTypeInfo(ConnectorManifest manifest) {
        return DriverTypeInfo.connector(
                manifest.dbType(),
                manifest.id(),
                manifest.name(),
                manifest.iconUrl(),
                manifest.defaultPort(),
                manifest.defaultSslMode(),
                manifest.jdbcUrlTemplate(),
                status(manifest),
                manifest.bundled(),
                manifest.vendor(),
                manifest.driverClassName(),
                manifest.description(),
                manifest.documentationUrl());
    }

    private DriverTypeInfo toConnectorMarketplaceTypeInfo(ConnectorManifest manifest) {
        return DriverTypeInfo.connector(
                manifest.dbType(),
                manifest.id(),
                manifest.name(),
                manifest.iconUrl(),
                manifest.defaultPort(),
                manifest.defaultSslMode(),
                manifest.jdbcUrlTemplate(),
                status(manifest),
                manifest.bundled(),
                manifest.vendor(),
                manifest.driverClassName(),
                manifest.description(),
                manifest.documentationUrl());
    }

    private DriverTypeInfo toUploadedTypeInfo(CustomDriverDescriptor descriptor) {
        DbType target = descriptor.targetDbType();
        // For a CUSTOM-typed upload there is no dialect template; the wizard renders a free-form
        // JDBC URL field instead. We surface the upload's metadata as display hints.
        if (target == DbType.CUSTOM) {
            return DriverTypeInfo.uploaded(
                    DbType.CUSTOM,
                    String.format(CUSTOM_DRIVER_DISPLAY_NAME_FORMAT,
                            descriptor.vendorName(), descriptor.jarFilename()),
                    "/db-icons/custom.svg",
                    CUSTOM_DEFAULT_PORT,
                    CUSTOM_DEFAULT_SSL,
                    "",
                    descriptor.id(),
                    descriptor.vendorName(),
                    descriptor.driverClass());
        }
        // Uploaded drivers that override a dialect inherit that dialect's display defaults.
        var dialect = catalog.byDbType(target).orElse(null);
        String displayName = dialect != null
                ? String.format("%s (uploaded: %s)", dialect.name(), descriptor.vendorName())
                : descriptor.vendorName();
        String iconUrl = dialect != null ? dialect.iconUrl() : "/db-icons/custom.svg";
        int port = dialect != null ? dialect.defaultPort() : CUSTOM_DEFAULT_PORT;
        SslMode ssl = dialect != null ? dialect.defaultSslMode() : CUSTOM_DEFAULT_SSL;
        String urlTemplate = dialect != null ? dialect.jdbcUrlTemplate() : "";
        return DriverTypeInfo.uploaded(target, displayName, iconUrl, port, ssl, urlTemplate,
                descriptor.id(), descriptor.vendorName(), descriptor.driverClass());
    }

    private DriverStatus status(ConnectorManifest manifest) {
        if (manifest.bundled() || cachedJar(manifest).map(Files::isRegularFile).orElse(false)) {
            return DriverStatus.READY;
        }
        if (properties.offline()) {
            return DriverStatus.UNAVAILABLE;
        }
        return cacheDirWritable() ? DriverStatus.AVAILABLE : DriverStatus.UNAVAILABLE;
    }

    private void verifyCustomChecksum(CustomDriverDescriptor descriptor, Path jarPath) {
        if (!Files.isRegularFile(jarPath)) {
            throw new DriverResolutionException(
                    descriptor.targetDbType(),
                    DriverResolutionException.Reason.UNAVAILABLE,
                    msg("error.custom_driver.jar_missing", descriptor.jarFilename()));
        }
        var actual = sha256(jarPath);
        if (!actual.equalsIgnoreCase(descriptor.jarSha256())) {
            throw new DriverResolutionException(
                    descriptor.targetDbType(),
                    DriverResolutionException.Reason.CHECKSUM_MISMATCH,
                    msg("error.datasource_driver_unavailable.checksum_mismatch",
                            descriptor.targetDbType().name(), descriptor.jarSha256(), actual));
        }
    }

    private ResolvedDriver resolveBundled(ConnectorManifest manifest) {
        try {
            var loader = getClass().getClassLoader();
            var driverClass = Class.forName(manifest.driverClassName(), true, loader);
            var driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
            return new ResolvedDriver(driver, loader, manifest.driverClassName());
        } catch (ReflectiveOperationException e) {
            throw new DriverResolutionException(
                    manifest.dbType(),
                    DriverResolutionException.Reason.UNAVAILABLE,
                    msg("error.datasource_driver_unavailable.unavailable", manifest.dbType().name()),
                    e);
        }
    }

    private ResolvedDriver resolveExternal(ConnectorManifest manifest, String loaderName) {
        var jarPath = ensureCachedJar(manifest);
        try {
            var url = jarPath.toUri().toURL();
            var loader = new URLClassLoader(loaderName, new URL[]{url}, getClass().getClassLoader());
            var driverClass = Class.forName(manifest.driverClassName(), true, loader);
            var driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
            return new ResolvedDriver(driver, loader, manifest.driverClassName());
        } catch (ReflectiveOperationException | IOException e) {
            throw new DriverResolutionException(
                    manifest.dbType(),
                    DriverResolutionException.Reason.UNAVAILABLE,
                    msg("error.datasource_driver_unavailable.unavailable", manifest.dbType().name()),
                    e);
        }
    }

    private Path ensureCachedJar(ConnectorManifest manifest) {
        var jarPath = properties.cacheDir().resolve(manifest.jarFileName());
        if (Files.isRegularFile(jarPath)) {
            verifyChecksum(manifest, jarPath);
            return jarPath;
        }
        if (properties.offline()) {
            throw new DriverResolutionException(
                    manifest.dbType(),
                    DriverResolutionException.Reason.OFFLINE_CACHE_MISS,
                    msg("error.datasource_driver_unavailable.offline_cache_miss",
                            manifest.dbType().name(), manifest.jarFileName()));
        }
        ensureCacheDir(manifest);
        downloadJar(manifest, jarPath);
        verifyChecksum(manifest, jarPath);
        return jarPath;
    }

    private void ensureCacheDir(ConnectorManifest manifest) {
        try {
            Files.createDirectories(properties.cacheDir());
        } catch (IOException e) {
            throw new DriverResolutionException(
                    manifest.dbType(),
                    DriverResolutionException.Reason.CACHE_NOT_WRITABLE,
                    msg("error.datasource_driver_unavailable.cache_not_writable",
                            manifest.dbType().name(), properties.cacheDir().toString()),
                    e);
        }
    }

    private void downloadJar(ConnectorManifest manifest, Path jarPath) {
        var sourceUrl = manifest.sourceUrl(properties.repositoryUrl());
        var tempPath = jarPath.resolveSibling(manifest.jarFileName() + ".part");
        try {
            log.info("Resolving JDBC driver for connector {} from {}", manifest.id(), sourceUrl);
            var request = HttpRequest.newBuilder(URI.create(sourceUrl))
                    .timeout(DOWNLOAD_TIMEOUT)
                    .GET()
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                throw new DriverResolutionException(
                        manifest.dbType(),
                        DriverResolutionException.Reason.DOWNLOAD_FAILED,
                        msg("error.datasource_driver_unavailable.download_failed",
                                manifest.dbType().name(), response.statusCode(), sourceUrl));
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
                    manifest.dbType(),
                    DriverResolutionException.Reason.DOWNLOAD_FAILED,
                    msg("error.datasource_driver_unavailable.download_failed",
                            manifest.dbType().name(), -1, sourceUrl),
                    e);
        }
    }

    private void verifyChecksum(ConnectorManifest manifest, Path jarPath) {
        var actual = sha256(jarPath, manifest);
        if (!actual.equalsIgnoreCase(manifest.sha256())) {
            try {
                Files.deleteIfExists(jarPath);
            } catch (IOException ignored) {
                // best-effort
            }
            throw new DriverResolutionException(
                    manifest.dbType(),
                    DriverResolutionException.Reason.CHECKSUM_MISMATCH,
                    msg("error.datasource_driver_unavailable.checksum_mismatch",
                            manifest.dbType().name(), manifest.sha256(), actual));
        }
    }

    private String sha256(Path jarPath, ConnectorManifest manifest) {
        try {
            return sha256(jarPath);
        } catch (RuntimeException e) {
            throw new DriverResolutionException(
                    manifest.dbType(),
                    DriverResolutionException.Reason.UNAVAILABLE,
                    msg("error.datasource_driver_unavailable.unavailable", manifest.dbType().name()),
                    e);
        }
    }

    private static String sha256(Path jarPath) {
        try (var in = Files.newInputStream(jarPath)) {
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException("Failed to compute SHA-256 of " + jarPath, e);
        }
    }

    private Optional<Path> cachedJar(ConnectorManifest manifest) {
        if (manifest.bundled()) {
            return Optional.empty();
        }
        return Optional.of(properties.cacheDir().resolve(manifest.jarFileName()));
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

    private static void closeQuietly(URLClassLoader loader) {
        try {
            loader.close();
        } catch (IOException e) {
            log.warn("Failed to close URLClassLoader {}: {}", loader.getName(), e.getMessage());
        }
    }
}
