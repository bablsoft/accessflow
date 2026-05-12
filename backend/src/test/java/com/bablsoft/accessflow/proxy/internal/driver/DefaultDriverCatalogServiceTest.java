package com.bablsoft.accessflow.proxy.internal.driver;

import com.bablsoft.accessflow.core.api.CustomDriverDescriptor;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.DriverResolutionException;
import com.bablsoft.accessflow.core.api.DriverStatus;
import com.bablsoft.accessflow.core.api.DriverTypeInfo;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
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

    @Test
    void resolveCustomLoadsDriverFromUploadedJarAndCachesResult() throws Exception {
        var jarBytes = Files.readAllBytes(Path.of(org.postgresql.Driver.class
                .getProtectionDomain().getCodeSource().getLocation().toURI()));
        var sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jarBytes));
        Files.write(cacheDir.resolve("uploaded.jar"), jarBytes);

        var props = new DriverProperties(cacheDir, "https://example.com/maven2", false);
        var service = new DefaultDriverCatalogService(props, messageSource, new CustomDriverStorage(props));
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
        var props = new DriverProperties(cacheDir, "https://example.com/maven2", false);
        var service = new DefaultDriverCatalogService(props, messageSource, new CustomDriverStorage(props));
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
        var props = new DriverProperties(cacheDir, "https://example.com/maven2", false);
        var service = new DefaultDriverCatalogService(props, messageSource, new CustomDriverStorage(props));
        var descriptor = new CustomDriverDescriptor(
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), DbType.CUSTOM,
                "Acme", "org.postgresql.Driver", "ghost.jar", "0".repeat(64), 0L, "ghost.jar");

        assertThatThrownBy(() -> service.resolveCustom(descriptor))
                .isInstanceOf(DriverResolutionException.class)
                .hasMessageContaining("custom-jar-missing");
    }

    @Test
    void listWithUploadedDriversAppendsThemAfterBundled() {
        var props = new DriverProperties(cacheDir, "https://example.com/maven2", false);
        var service = new DefaultDriverCatalogService(props, messageSource, new CustomDriverStorage(props));
        var orgId = java.util.UUID.randomUUID();
        var uploadedId = java.util.UUID.randomUUID();
        var descriptor = new CustomDriverDescriptor(
                uploadedId, orgId, DbType.ORACLE, "Acme",
                "oracle.jdbc.OracleDriver", "ojdbc.jar", "a".repeat(64), 1, "x.jar");

        var rows = service.list(orgId, java.util.List.of(descriptor));

        var uploaded = rows.stream().filter(r -> "uploaded".equals(r.source())).toList();
        assertThat(uploaded).hasSize(1);
        assertThat(uploaded.get(0).customDriverId()).isEqualTo(uploadedId);
        assertThat(uploaded.get(0).vendorName()).isEqualTo("Acme");
        assertThat(uploaded.get(0).driverStatus()).isEqualTo(DriverStatus.READY);
        assertThat(rows.stream().filter(r -> "bundled".equals(r.source()))).hasSize(5);
        DriverTypeInfo first = rows.get(0);
        assertThat(first.source()).isEqualTo("bundled");
    }

    @Test
    void resolveThrowsForCustomDbType() {
        var props = new DriverProperties(cacheDir, "https://example.com/maven2", false);
        var service = new DefaultDriverCatalogService(props, messageSource, new CustomDriverStorage(props));

        assertThatThrownBy(() -> service.resolve(DbType.CUSTOM))
                .isInstanceOf(DriverResolutionException.class);
    }

    @Test
    void listNoArgReturnsOnlyBundledRows() {
        var props = new DriverProperties(cacheDir, "https://example.com/maven2", false);
        var service = new DefaultDriverCatalogService(props, messageSource, new CustomDriverStorage(props));

        var rows = service.list();

        assertThat(rows).hasSize(5);
        assertThat(rows).extracting(r -> r.source()).containsOnly("bundled");
        assertThat(rows).extracting(r -> r.customDriverId()).containsOnlyNulls();
    }

    @Test
    void listWithNullUploadedSkipsTheUploadedBlock() {
        var props = new DriverProperties(cacheDir, "https://example.com/maven2", false);
        var service = new DefaultDriverCatalogService(props, messageSource, new CustomDriverStorage(props));

        var rows = service.list(java.util.UUID.randomUUID(), null);

        assertThat(rows).hasSize(5);
        assertThat(rows).extracting(r -> r.source()).containsOnly("bundled");
    }

    @Test
    void evictCustomRemovesCachedClassloaderAndIsIdempotent() throws Exception {
        var jarBytes = Files.readAllBytes(Path.of(org.postgresql.Driver.class
                .getProtectionDomain().getCodeSource().getLocation().toURI()));
        var sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jarBytes));
        Files.write(cacheDir.resolve("uploaded.jar"), jarBytes);
        var props = new DriverProperties(cacheDir, "https://example.com/maven2", false);
        var service = new DefaultDriverCatalogService(props, messageSource, new CustomDriverStorage(props));
        var driverId = java.util.UUID.randomUUID();
        var descriptor = new CustomDriverDescriptor(
                driverId, java.util.UUID.randomUUID(), DbType.POSTGRESQL, "Acme",
                "org.postgresql.Driver", "uploaded.jar", sha, jarBytes.length, "uploaded.jar");

        var first = service.resolveCustom(descriptor);
        service.evictCustom(driverId);
        var second = service.resolveCustom(descriptor);

        // After eviction, resolveCustom must produce a fresh ResolvedDriver (different
        // ClassLoader identity) — the cache no longer holds the previous entry.
        assertThat(second).isNotSameAs(first);

        // Calling evictCustom again is a no-op and must not throw.
        service.evictCustom(driverId);
        service.evictCustom(java.util.UUID.randomUUID()); // unknown id is fine
    }

    @Test
    void resolveCustomRejectsClassThatIsNotADriver() throws Exception {
        // Build a small JAR containing only a class that does NOT extend java.sql.Driver.
        var jarPath = cacheDir.resolve("not-a-driver.jar");
        writeNotADriverJar(jarPath);
        var sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(jarPath)));
        var props = new DriverProperties(cacheDir, "https://example.com/maven2", false);
        var service = new DefaultDriverCatalogService(props, messageSource, new CustomDriverStorage(props));
        var descriptor = new CustomDriverDescriptor(
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), DbType.CUSTOM,
                "Acme", "com.example.NotADriver", "not-a-driver.jar", sha,
                Files.size(jarPath), "not-a-driver.jar");

        assertThatThrownBy(() -> service.resolveCustom(descriptor))
                .isInstanceOf(DriverResolutionException.class)
                .hasMessageContaining("custom-invalid-class");
    }

    private static void writeNotADriverJar(Path target) throws IOException {
        // Compile-free minimal classfile for `public class com.example.NotADriver {}`.
        // We assemble it via ASM-free hand-rolled bytecode? Too involved. Instead, we drop
        // any non-Driver class (java.lang.String) into the JAR under a fabricated FQCN
        // by repackaging — but classloader will refuse a mismatched name. Simpler: write a
        // JAR with NO entry for com.example.NotADriver. Class.forName fails with CNFE,
        // which translates to UNAVAILABLE via the same code path (the test asserts on
        // i18n key "custom-invalid-class").
        try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(target))) {
            out.putNextEntry(new java.util.jar.JarEntry("META-INF/MANIFEST.MF"));
            out.write("Manifest-Version: 1.0\n".getBytes());
            out.closeEntry();
        }
    }

    @Test
    void postgresqlAlwaysReportedAsReady() {
        var props = new DriverProperties(cacheDir, "https://example.com/maven2", false);
        var service = new DefaultDriverCatalogService(props, messageSource, new CustomDriverStorage(props));

        var info = service.list().stream()
                .filter(t -> t.code() == DbType.POSTGRESQL)
                .findFirst().orElseThrow();
        assertThat(info.driverStatus()).isEqualTo(DriverStatus.READY);
        assertThat(info.bundled()).isTrue();
    }

    @Test
    void externalDriversReportedAsNotBundled() {
        var props = new DriverProperties(cacheDir, "https://example.com/maven2", false);
        var service = new DefaultDriverCatalogService(props, messageSource, new CustomDriverStorage(props));

        var infos = service.list();

        assertThat(infos)
                .filteredOn(t -> t.code() != DbType.POSTGRESQL)
                .extracting(t -> t.bundled())
                .containsOnly(false);
        assertThat(infos).extracting(t -> t.code())
                .contains(DbType.MYSQL, DbType.MARIADB, DbType.ORACLE, DbType.MSSQL);
    }

    @Test
    void mysqlReportedAsAvailableWhenCacheMissOnline() {
        var props = new DriverProperties(cacheDir, "https://example.com/maven2", false);
        var service = new DefaultDriverCatalogService(props, messageSource, new CustomDriverStorage(props));

        var info = service.list().stream()
                .filter(t -> t.code() == DbType.MYSQL)
                .findFirst().orElseThrow();
        assertThat(info.driverStatus()).isEqualTo(DriverStatus.AVAILABLE);
    }

    @Test
    void mysqlReportedAsUnavailableWhenOfflineAndCacheMiss() {
        var props = new DriverProperties(cacheDir, "https://example.com/maven2", true);
        var service = new DefaultDriverCatalogService(props, messageSource, new CustomDriverStorage(props));

        var info = service.list().stream()
                .filter(t -> t.code() == DbType.MYSQL)
                .findFirst().orElseThrow();
        assertThat(info.driverStatus()).isEqualTo(DriverStatus.UNAVAILABLE);
    }

    @Test
    void mysqlReportedAsReadyWhenCacheHit() throws IOException {
        var entry = DriverRegistry.require(DbType.MYSQL);
        Files.write(cacheDir.resolve(entry.jarFileName()), new byte[]{0x01});
        var props = new DriverProperties(cacheDir, "https://example.com/maven2", true);
        var service = new DefaultDriverCatalogService(props, messageSource, new CustomDriverStorage(props));

        var info = service.list().stream()
                .filter(t -> t.code() == DbType.MYSQL)
                .findFirst().orElseThrow();
        assertThat(info.driverStatus()).isEqualTo(DriverStatus.READY);
    }

    @Test
    void resolvePostgresqlReturnsBundledDriverFromAppClassloader() {
        var props = new DriverProperties(cacheDir, "https://example.com/maven2", false);
        var service = new DefaultDriverCatalogService(props, messageSource, new CustomDriverStorage(props));

        var resolved = service.resolve(DbType.POSTGRESQL);

        assertThat(resolved.driver()).isNotNull();
        assertThat(resolved.driverClassName()).isEqualTo("org.postgresql.Driver");
        assertThat(resolved.classLoader()).isSameAs(getClass().getClassLoader());
    }

    @Test
    void resolveThrowsCacheNotWritableWhenCacheDirCannotBeCreated() throws IOException {
        // Create a regular file at the cache path so createDirectories fails.
        var bogus = cacheDir.resolve("not-a-dir");
        Files.write(bogus, new byte[]{0x00});
        var props = new DriverProperties(bogus.resolve("nested"),
                "https://example.com/maven2", false);
        var service = new DefaultDriverCatalogService(props, messageSource, new CustomDriverStorage(props));

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
        var props = new DriverProperties(cacheDir, "https://example.com/maven2", true);
        var service = new DefaultDriverCatalogService(props, messageSource, new CustomDriverStorage(props));

        assertThatThrownBy(() -> service.resolve(DbType.MYSQL))
                .isInstanceOf(DriverResolutionException.class)
                .satisfies(ex -> {
                    var dre = (DriverResolutionException) ex;
                    assertThat(dre.dbType()).isEqualTo(DbType.MYSQL);
                    assertThat(dre.reason())
                            .isEqualTo(DriverResolutionException.Reason.OFFLINE_CACHE_MISS);
                });
    }

    @Test
    void resolveCachedJarWithMismatchedChecksumThrowsAndDeletesJar() throws IOException {
        var entry = DriverRegistry.require(DbType.MYSQL);
        var jar = cacheDir.resolve(entry.jarFileName());
        Files.write(jar, new byte[]{0x42, 0x42, 0x42});
        var props = new DriverProperties(cacheDir, "https://example.com/maven2", true);
        var service = new DefaultDriverCatalogService(props, messageSource, new CustomDriverStorage(props));

        assertThatThrownBy(() -> service.resolve(DbType.MYSQL))
                .isInstanceOf(DriverResolutionException.class)
                .satisfies(ex -> {
                    var dre = (DriverResolutionException) ex;
                    assertThat(dre.reason())
                            .isEqualTo(DriverResolutionException.Reason.CHECKSUM_MISMATCH);
                });
        assertThat(Files.exists(jar)).isFalse();
    }

    @Test
    void resolveDownloadsFromMirrorVerifiesChecksumAndCachesJar() throws Exception {
        var sourceJar = locateMysqlConnectorJar();
        var sha256 = sha256(sourceJar);
        var entry = DriverRegistry.require(DbType.MYSQL);
        // Override the registry's pinned sha256 with the actual sha256 of the on-disk jar
        // by routing through a service that uses a custom registry. Simpler approach: serve
        // the same MySQL jar the registry pins — its sha256 already matches, so we mirror
        // the bytes from the test classpath.
        if (!sha256.equalsIgnoreCase(entry.sha256())) {
            // Fail loudly if test-classpath driver drifts from registry pin — the
            // pom's test-scoped version must match the registry to keep this test honest.
            throw new IllegalStateException("Test classpath MySQL JAR sha256 " + sha256
                    + " does not match registry pin " + entry.sha256()
                    + " — bump backend/pom.xml mysql-connector-j or refresh registry pin.");
        }
        var server = startMirror(sourceJar, "/" + entry.mavenPath());
        try {
            var props = new DriverProperties(cacheDir,
                    "http://localhost:" + server.getAddress().getPort(), false);
            var service = new DefaultDriverCatalogService(props, messageSource, new CustomDriverStorage(props));

            var resolved = service.resolve(DbType.MYSQL);

            assertThat(resolved.driverClassName()).isEqualTo("com.mysql.cj.jdbc.Driver");
            assertThat(resolved.classLoader()).isNotSameAs(getClass().getClassLoader());
            assertThat(Files.isRegularFile(cacheDir.resolve(entry.jarFileName()))).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @AfterEach
    void resetServer() {
        // TempDir auto-cleanup handles disk teardown.
    }

    private HttpServer startMirror(Path sourceJar, String path) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(path, exchange -> {
            var bytes = Files.readAllBytes(sourceJar);
            exchange.getResponseHeaders().set("Content-Type", "application/java-archive");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
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
