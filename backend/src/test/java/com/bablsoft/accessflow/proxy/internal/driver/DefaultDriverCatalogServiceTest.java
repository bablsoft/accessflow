package com.bablsoft.accessflow.proxy.internal.driver;

import com.bablsoft.accessflow.core.api.ConnectorNotFoundException;
import com.bablsoft.accessflow.core.api.CustomDriverDescriptor;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.DriverResolutionException;
import com.bablsoft.accessflow.core.api.DriverStatus;
import com.bablsoft.accessflow.core.api.DriverTypeInfo;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.StaticMessageSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultDriverCatalogServiceTest {

    @TempDir
    Path cacheDir;

    private StaticMessageSource messageSource;
    private final ConnectorCatalog catalog = new ConnectorCatalog();

    @BeforeEach
    void setUp() {
        messageSource = new StaticMessageSource();
        messageSource.addMessage("error.datasource_driver_unavailable.offline_cache_miss",
                Locale.getDefault(), "offline-miss-{0}-{1}");
        messageSource.addMessage("error.datasource_driver_unavailable.download_failed",
                Locale.getDefault(), "download-failed-{0}-{1}-{2}");
        messageSource.addMessage("error.datasource_driver_unavailable.checksum_mismatch",
                Locale.getDefault(), "checksum-mismatch-{0}-{1}-{2}");
        messageSource.addMessage("error.datasource_driver_unavailable.cache_not_writable",
                Locale.getDefault(), "cache-not-writable-{0}-{1}");
        messageSource.addMessage("error.datasource_driver_unavailable.unavailable",
                Locale.getDefault(), "unavailable-{0}");
        messageSource.addMessage("error.custom_driver.jar_missing",
                Locale.getDefault(), "custom-jar-missing-{0}");
        messageSource.addMessage("error.custom_driver.invalid_class",
                Locale.getDefault(), "custom-invalid-class-{0}");
    }

    private DefaultDriverCatalogService service(boolean offline) {
        var props = new DriverProperties(cacheDir, "https://example.com/maven2", offline);
        return new DefaultDriverCatalogService(messageSource,
                new CustomDriverStorage(props), catalog, new DriverJarCache(props, messageSource));
    }

    private DefaultDriverCatalogService serviceWithRepo(String repositoryUrl) {
        var props = new DriverProperties(cacheDir, repositoryUrl, false);
        return new DefaultDriverCatalogService(messageSource,
                new CustomDriverStorage(props), catalog, new DriverJarCache(props, messageSource));
    }

    // ── custom uploaded-driver resolution ────────────────────────────────────

    @Test
    void resolveCustomLoadsDriverFromUploadedJarAndCachesResult() throws Exception {
        var jarBytes = Files.readAllBytes(Path.of(org.postgresql.Driver.class
                .getProtectionDomain().getCodeSource().getLocation().toURI()));
        var sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jarBytes));
        Files.write(cacheDir.resolve("uploaded.jar"), jarBytes);

        var service = service(false);
        var driverId = java.util.UUID.randomUUID();
        var descriptor = new CustomDriverDescriptor(
                driverId, java.util.UUID.randomUUID(), DbType.POSTGRESQL, "Acme",
                "org.postgresql.Driver", "uploaded.jar", sha, jarBytes.length, "uploaded.jar");

        var resolved = service.resolveCustom(descriptor);
        var resolvedAgain = service.resolveCustom(descriptor);

        assertThat(resolved.driverClassName()).isEqualTo("org.postgresql.Driver");
        assertThat(resolved.driver()).isNotNull();
        assertThat(resolvedAgain).isSameAs(resolved);
    }

    @Test
    void resolveCustomThrowsOnChecksumMismatch() throws Exception {
        Files.write(cacheDir.resolve("uploaded.jar"), new byte[]{0x01, 0x02});
        var service = service(false);
        var descriptor = new CustomDriverDescriptor(
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), DbType.POSTGRESQL,
                "Acme", "org.postgresql.Driver", "uploaded.jar", "0".repeat(64), 2L, "uploaded.jar");

        assertThatThrownBy(() -> service.resolveCustom(descriptor))
                .isInstanceOf(DriverResolutionException.class)
                .satisfies(ex -> assertThat(((DriverResolutionException) ex).reason())
                        .isEqualTo(DriverResolutionException.Reason.CHECKSUM_MISMATCH));
    }

    @Test
    void resolveCustomThrowsWhenJarFileMissing() {
        var service = service(false);
        var descriptor = new CustomDriverDescriptor(
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), DbType.CUSTOM,
                "Acme", "org.postgresql.Driver", "ghost.jar", "0".repeat(64), 0L, "ghost.jar");

        assertThatThrownBy(() -> service.resolveCustom(descriptor))
                .isInstanceOf(DriverResolutionException.class)
                .hasMessageContaining("custom-jar-missing");
    }

    @Test
    void resolveCustomRejectsClassThatIsNotADriver() throws Exception {
        var jarPath = cacheDir.resolve("not-a-driver.jar");
        writeManifestOnlyJar(jarPath);
        var sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(jarPath)));
        var service = service(false);
        var descriptor = new CustomDriverDescriptor(
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), DbType.CUSTOM,
                "Acme", "com.example.NotADriver", "not-a-driver.jar", sha,
                Files.size(jarPath), "not-a-driver.jar");

        assertThatThrownBy(() -> service.resolveCustom(descriptor))
                .isInstanceOf(DriverResolutionException.class)
                .hasMessageContaining("custom-invalid-class");
    }

    @Test
    void evictCustomRemovesCachedClassloaderAndIsIdempotent() throws Exception {
        var jarBytes = Files.readAllBytes(Path.of(org.postgresql.Driver.class
                .getProtectionDomain().getCodeSource().getLocation().toURI()));
        var sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jarBytes));
        Files.write(cacheDir.resolve("uploaded.jar"), jarBytes);
        var service = service(false);
        var driverId = java.util.UUID.randomUUID();
        var descriptor = new CustomDriverDescriptor(
                driverId, java.util.UUID.randomUUID(), DbType.POSTGRESQL, "Acme",
                "org.postgresql.Driver", "uploaded.jar", sha, jarBytes.length, "uploaded.jar");

        var first = service.resolveCustom(descriptor);
        service.evictCustom(driverId);
        var second = service.resolveCustom(descriptor);

        assertThat(second).isNotSameAs(first);
        service.evictCustom(driverId);
        service.evictCustom(java.util.UUID.randomUUID());
    }

    // ── dialect (DbType) resolution + catalog listing ────────────────────────

    @Test
    void resolveThrowsForCustomDbType() {
        assertThatThrownBy(() -> service(false).resolve(DbType.CUSTOM))
                .isInstanceOf(DriverResolutionException.class);
    }

    @Test
    void listReturnsDialectsAsBundledAndCustomConnectorsAsConnectorSource() {
        var rows = service(false).list();

        // 5 SQL dialects + MongoDB, Couchbase, Redis, Cassandra, and ScyllaDB (on-demand native
        // engines, AF-414/AF-412/AF-419/AF-421) are surfaced with source "bundled" (= first-class
        // catalog rows, as opposed to connector/uploaded rows).
        assertThat(rows.stream().filter(r -> "bundled".equals(r.source()))).hasSize(10);
        var connectorRows = rows.stream().filter(r -> "connector".equals(r.source())).toList();
        assertThat(connectorRows).hasSize(1);
        assertThat(connectorRows.get(0).code()).isEqualTo(DbType.CUSTOM);
        assertThat(connectorRows.get(0).connectorId()).isEqualTo("clickhouse");
        assertThat(rows.get(0).source()).isEqualTo("bundled");
    }

    @Test
    void listWithUploadedDriversAppendsThemAfterCatalog() {
        var orgId = java.util.UUID.randomUUID();
        var uploadedId = java.util.UUID.randomUUID();
        var descriptor = new CustomDriverDescriptor(
                uploadedId, orgId, DbType.ORACLE, "Acme",
                "oracle.jdbc.OracleDriver", "ojdbc.jar", "a".repeat(64), 1, "x.jar");

        var rows = service(false).list(orgId, java.util.List.of(descriptor));

        var uploaded = rows.stream().filter(r -> "uploaded".equals(r.source())).toList();
        assertThat(uploaded).hasSize(1);
        assertThat(uploaded.get(0).customDriverId()).isEqualTo(uploadedId);
        assertThat(uploaded.get(0).vendorName()).isEqualTo("Acme");
        assertThat(uploaded.get(0).driverStatus()).isEqualTo(DriverStatus.READY);
        assertThat(rows.get(0).source()).isEqualTo("bundled");
    }

    @Test
    void listWithNullUploadedSkipsTheUploadedBlock() {
        var rows = service(false).list(java.util.UUID.randomUUID(), null);

        assertThat(rows.stream().filter(r -> "uploaded".equals(r.source()))).isEmpty();
        assertThat(rows).hasSize(11); // 5 SQL dialects + mongodb + couchbase + redis + cassandra + scylladb + clickhouse
    }

    @Test
    void postgresqlAlwaysReportedAsReady() {
        var info = service(false).list().stream()
                .filter(t -> t.code() == DbType.POSTGRESQL)
                .findFirst().orElseThrow();
        assertThat(info.driverStatus()).isEqualTo(DriverStatus.READY);
        assertThat(info.bundled()).isTrue();
    }

    @Test
    void externalDriversReportedAsNotBundled() {
        var infos = service(false).list();

        // PostgreSQL is the only bundled engine; every other dialect's driver — and the MongoDB /
        // Couchbase engine plugins (AF-414 / AF-412) — is resolved on demand.
        assertThat(infos)
                .filteredOn(t -> t.code() != DbType.POSTGRESQL)
                .extracting(DriverTypeInfo::bundled)
                .containsOnly(false);
        assertThat(infos).extracting(DriverTypeInfo::code)
                .contains(DbType.MYSQL, DbType.MARIADB, DbType.ORACLE, DbType.MSSQL,
                        DbType.MONGODB, DbType.COUCHBASE);
    }

    @Test
    void mongodbEnginePluginReportedAsAvailableWhenCacheMissOnline() {
        var info = service(false).list().stream()
                .filter(t -> t.code() == DbType.MONGODB).findFirst().orElseThrow();
        assertThat(info.bundled()).isFalse();
        assertThat(info.driverStatus()).isEqualTo(DriverStatus.AVAILABLE);
    }

    @Test
    void mongodbEnginePluginReportedAsUnavailableWhenOfflineAndCacheMiss() {
        var info = service(true).list().stream()
                .filter(t -> t.code() == DbType.MONGODB).findFirst().orElseThrow();
        assertThat(info.driverStatus()).isEqualTo(DriverStatus.UNAVAILABLE);
    }

    @Test
    void mysqlReportedAsAvailableWhenCacheMissOnline() {
        var info = service(false).list().stream()
                .filter(t -> t.code() == DbType.MYSQL).findFirst().orElseThrow();
        assertThat(info.driverStatus()).isEqualTo(DriverStatus.AVAILABLE);
    }

    @Test
    void mysqlReportedAsUnavailableWhenOfflineAndCacheMiss() {
        var info = service(true).list().stream()
                .filter(t -> t.code() == DbType.MYSQL).findFirst().orElseThrow();
        assertThat(info.driverStatus()).isEqualTo(DriverStatus.UNAVAILABLE);
    }

    @Test
    void mysqlReportedAsReadyWhenCacheHit() throws IOException {
        var manifest = catalog.requireByDbType(DbType.MYSQL);
        Files.write(cacheDir.resolve(manifest.jarFileName()), new byte[]{0x01});
        var info = service(true).list().stream()
                .filter(t -> t.code() == DbType.MYSQL).findFirst().orElseThrow();
        assertThat(info.driverStatus()).isEqualTo(DriverStatus.READY);
    }

    @Test
    void resolvePostgresqlReturnsBundledDriverFromAppClassloader() {
        var resolved = service(false).resolve(DbType.POSTGRESQL);

        assertThat(resolved.driver()).isNotNull();
        assertThat(resolved.driverClassName()).isEqualTo("org.postgresql.Driver");
        assertThat(resolved.classLoader()).isSameAs(getClass().getClassLoader());
    }

    @Test
    void resolveThrowsCacheNotWritableWhenCacheDirCannotBeCreated() throws IOException {
        var bogus = cacheDir.resolve("not-a-dir");
        Files.write(bogus, new byte[]{0x00});
        var props = new DriverProperties(bogus.resolve("nested"), "https://example.com/maven2", false);
        var service = new DefaultDriverCatalogService(messageSource,
                new CustomDriverStorage(props), catalog, new DriverJarCache(props, messageSource));

        assertThatThrownBy(() -> service.resolve(DbType.MYSQL))
                .isInstanceOf(DriverResolutionException.class)
                .satisfies(ex -> {
                    var dre = (DriverResolutionException) ex;
                    assertThat(dre.dbType()).isEqualTo(DbType.MYSQL);
                    assertThat(dre.reason())
                            .isEqualTo(DriverResolutionException.Reason.CACHE_NOT_WRITABLE);
                    assertThat(dre.getMessage()).contains("cache-not-writable");
                });
    }

    @Test
    void resolveOfflineWithoutCacheThrowsOfflineCacheMiss() {
        assertThatThrownBy(() -> service(true).resolve(DbType.MYSQL))
                .isInstanceOf(DriverResolutionException.class)
                .satisfies(ex -> assertThat(((DriverResolutionException) ex).reason())
                        .isEqualTo(DriverResolutionException.Reason.OFFLINE_CACHE_MISS));
    }

    @Test
    void resolveCachedJarWithMismatchedChecksumThrowsAndDeletesJar() throws IOException {
        var manifest = catalog.requireByDbType(DbType.MYSQL);
        var jar = cacheDir.resolve(manifest.jarFileName());
        Files.write(jar, new byte[]{0x42, 0x42, 0x42});

        assertThatThrownBy(() -> service(true).resolve(DbType.MYSQL))
                .isInstanceOf(DriverResolutionException.class)
                .satisfies(ex -> assertThat(((DriverResolutionException) ex).reason())
                        .isEqualTo(DriverResolutionException.Reason.CHECKSUM_MISMATCH));
        assertThat(Files.exists(jar)).isFalse();
    }

    @Test
    void resolveDownloadsFromMirrorVerifiesChecksumAndCachesJar() throws Exception {
        var sourceJar = locateMysqlConnectorJar();
        var manifest = catalog.requireByDbType(DbType.MYSQL);
        var sha256 = sha256(sourceJar);
        if (!sha256.equalsIgnoreCase(manifest.sha256())) {
            throw new IllegalStateException("Test classpath MySQL JAR sha256 " + sha256
                    + " does not match catalog pin " + manifest.sha256()
                    + " — bump backend/pom.xml mysql-connector-j or refresh the connector manifest.");
        }
        var server = startMirror(sourceJar, manifest.sourceUrl(""));
        try {
            var service = serviceWithRepo("http://localhost:" + server.getAddress().getPort());

            var resolved = service.resolve(DbType.MYSQL);

            assertThat(resolved.driverClassName()).isEqualTo("com.mysql.cj.jdbc.Driver");
            assertThat(resolved.classLoader()).isNotSameAs(getClass().getClassLoader());
            assertThat(Files.isRegularFile(cacheDir.resolve(manifest.jarFileName()))).isTrue();
        } finally {
            server.stop(0);
        }
    }

    // ── connector marketplace: listConnectors / install / resolveConnector ────

    @Test
    void listConnectorsReturnsEveryManifestAsConnectorRow() {
        var rows = service(false).listConnectors();

        assertThat(rows).extracting(DriverTypeInfo::connectorId)
                .contains("postgresql", "mysql", "clickhouse");
        assertThat(rows).extracting(DriverTypeInfo::source).containsOnly("connector");
        var clickhouse = rows.stream()
                .filter(r -> "clickhouse".equals(r.connectorId())).findFirst().orElseThrow();
        assertThat(clickhouse.code()).isEqualTo(DbType.CUSTOM);
        assertThat(clickhouse.vendorName()).isEqualTo("ClickHouse, Inc.");
        assertThat(clickhouse.driverStatus()).isEqualTo(DriverStatus.AVAILABLE);
    }

    @Test
    void installBundledConnectorIsReadyWithoutDownload() {
        var info = service(false).install("postgresql");
        assertThat(info.driverStatus()).isEqualTo(DriverStatus.READY);
        assertThat(info.bundled()).isTrue();
    }

    @Test
    void installUnknownConnectorThrowsNotFound() {
        assertThatThrownBy(() -> service(false).install("does-not-exist"))
                .isInstanceOf(ConnectorNotFoundException.class);
    }

    @Test
    void installOfflineExternalConnectorThrowsOfflineCacheMiss() {
        assertThatThrownBy(() -> service(true).install("mysql"))
                .isInstanceOf(DriverResolutionException.class)
                .satisfies(ex -> assertThat(((DriverResolutionException) ex).reason())
                        .isEqualTo(DriverResolutionException.Reason.OFFLINE_CACHE_MISS));
    }

    @Test
    void installDownloadsFromMirrorAndReportsReady() throws Exception {
        var sourceJar = locateMysqlConnectorJar();
        var manifest = catalog.requireByDbType(DbType.MYSQL);
        var server = startMirror(sourceJar, manifest.sourceUrl(""));
        try {
            var service = serviceWithRepo("http://localhost:" + server.getAddress().getPort());

            var info = service.install("mysql");

            assertThat(info.driverStatus()).isEqualTo(DriverStatus.READY);
            assertThat(info.connectorId()).isEqualTo("mysql");
            assertThat(Files.isRegularFile(cacheDir.resolve(manifest.jarFileName()))).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resolveConnectorBundledLoadsFromAppClassloaderAndCaches() {
        var service = service(false);
        var first = service.resolveConnector("postgresql");
        var second = service.resolveConnector("postgresql");

        assertThat(first.driverClassName()).isEqualTo("org.postgresql.Driver");
        assertThat(second).isSameAs(first);
    }

    @Test
    void resolveConnectorUnknownThrowsNotFound() {
        assertThatThrownBy(() -> service(false).resolveConnector("nope"))
                .isInstanceOf(ConnectorNotFoundException.class);
    }

    @Test
    void connectorJdbcUrlSubstitutesTemplatePlaceholders() {
        var url = service(false).connectorJdbcUrl("clickhouse", "ch.example.com", 8123, "analytics");
        assertThat(url).isEqualTo("jdbc:ch://ch.example.com:8123/analytics");
    }

    @Test
    void connectorJdbcUrlUnknownThrowsNotFound() {
        assertThatThrownBy(() -> service(false).connectorJdbcUrl("nope", "h", 1, "d"))
                .isInstanceOf(ConnectorNotFoundException.class);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void writeManifestOnlyJar(Path target) throws IOException {
        try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(target))) {
            out.putNextEntry(new java.util.jar.JarEntry("META-INF/MANIFEST.MF"));
            out.write("Manifest-Version: 1.0\n".getBytes());
            out.closeEntry();
        }
    }

    private HttpServer startMirror(Path sourceJar, String path) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(path, exchange -> {
            var bytes = Files.readAllBytes(sourceJar);
            exchange.getResponseHeaders().set("Content-Type", "application/java-archive");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private Path locateMysqlConnectorJar() throws Exception {
        var url = Class.forName("com.mysql.cj.jdbc.Driver")
                .getProtectionDomain().getCodeSource().getLocation();
        return Path.of(url.toURI());
    }

    private String sha256(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            var buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
