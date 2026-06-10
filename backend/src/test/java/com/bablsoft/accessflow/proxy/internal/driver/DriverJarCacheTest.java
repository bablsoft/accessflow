package com.bablsoft.accessflow.proxy.internal.driver;

import com.bablsoft.accessflow.core.api.ConnectorCategory;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.DriverResolutionException;
import com.bablsoft.accessflow.core.api.DriverStatus;
import com.bablsoft.accessflow.core.api.SslMode;
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

class DriverJarCacheTest {

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
    }

    private DriverJarCache cache(boolean offline) {
        return new DriverJarCache(
                new DriverProperties(cacheDir, "https://example.com/maven2", offline),
                messageSource);
    }

    private static ConnectorManifest manifest(String sha256) {
        return new ConnectorManifest(1, "fake-engine", "Fake Engine", DbType.MONGODB,
                ConnectorCategory.DOCUMENT, "Acme", "desc", null, "logo.svg", 1234,
                SslMode.DISABLE, null, null, false,
                new ConnectorManifest.DriverArtifact("url", null, null, null, null,
                        "https://example.invalid/fake-engine.jar", "fake-engine.jar", sha256));
    }

    @Test
    void ensureCachedJarReturnsVerifiedCacheHitWithoutNetwork() throws Exception {
        var bytes = new byte[]{1, 2, 3};
        Files.write(cacheDir.resolve("fake-engine.jar"), bytes);

        var path = cache(true).ensureCachedJar(manifest(sha256(bytes)));

        assertThat(path).isEqualTo(cacheDir.resolve("fake-engine.jar"));
    }

    @Test
    void ensureCachedJarOfflineWithoutCacheThrowsOfflineCacheMiss() {
        assertThatThrownBy(() -> cache(true).ensureCachedJar(manifest("a".repeat(64))))
                .isInstanceOf(DriverResolutionException.class)
                .satisfies(ex -> assertThat(((DriverResolutionException) ex).reason())
                        .isEqualTo(DriverResolutionException.Reason.OFFLINE_CACHE_MISS));
    }

    @Test
    void ensureCachedJarDeletesCachedJarOnChecksumMismatch() throws IOException {
        var jar = cacheDir.resolve("fake-engine.jar");
        Files.write(jar, new byte[]{9, 9, 9});

        assertThatThrownBy(() -> cache(true).ensureCachedJar(manifest("a".repeat(64))))
                .isInstanceOf(DriverResolutionException.class)
                .satisfies(ex -> assertThat(((DriverResolutionException) ex).reason())
                        .isEqualTo(DriverResolutionException.Reason.CHECKSUM_MISMATCH));
        assertThat(Files.exists(jar)).isFalse();
    }

    @Test
    void ensureCachedJarDownloadsVerifiesAndCaches() throws Exception {
        var bytes = "jar-bytes".getBytes();
        var server = serve("/fake-engine.jar", bytes, 200);
        try {
            var manifest = urlManifest(server, sha256(bytes));

            var path = cache(false).ensureCachedJar(manifest);

            assertThat(Files.readAllBytes(path)).isEqualTo(bytes);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ensureCachedJarNonOkResponseThrowsDownloadFailed() throws Exception {
        var server = serve("/fake-engine.jar", new byte[0], 404);
        try {
            assertThatThrownBy(() -> cache(false).ensureCachedJar(
                    urlManifest(server, "a".repeat(64))))
                    .isInstanceOf(DriverResolutionException.class)
                    .satisfies(ex -> assertThat(((DriverResolutionException) ex).reason())
                            .isEqualTo(DriverResolutionException.Reason.DOWNLOAD_FAILED));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ensureCachedJarUnreachableSourceThrowsDownloadFailed() {
        assertThatThrownBy(() -> cache(false).ensureCachedJar(manifest("a".repeat(64))))
                .isInstanceOf(DriverResolutionException.class)
                .satisfies(ex -> assertThat(((DriverResolutionException) ex).reason())
                        .isEqualTo(DriverResolutionException.Reason.DOWNLOAD_FAILED));
    }

    @Test
    void ensureCachedJarUnwritableCacheDirThrowsCacheNotWritable() throws IOException {
        var bogus = cacheDir.resolve("not-a-dir");
        Files.write(bogus, new byte[]{0});
        var cache = new DriverJarCache(
                new DriverProperties(bogus.resolve("nested"), "https://example.com", false),
                messageSource);

        assertThatThrownBy(() -> cache.ensureCachedJar(manifest("a".repeat(64))))
                .isInstanceOf(DriverResolutionException.class)
                .satisfies(ex -> assertThat(((DriverResolutionException) ex).reason())
                        .isEqualTo(DriverResolutionException.Reason.CACHE_NOT_WRITABLE));
    }

    @Test
    void statusIsReadyForBundledManifests() {
        var bundled = new ConnectorManifest(1, "pg", "PostgreSQL", DbType.POSTGRESQL,
                ConnectorCategory.RELATIONAL, null, null, null, "logo.svg", 5432,
                SslMode.DISABLE, "jdbc:postgresql://{host}:{port}/{database_name}",
                "org.postgresql.Driver", true, null);
        assertThat(cache(true).status(bundled)).isEqualTo(DriverStatus.READY);
    }

    @Test
    void statusIsReadyWhenJarCached() throws IOException {
        Files.write(cacheDir.resolve("fake-engine.jar"), new byte[]{1});
        assertThat(cache(true).status(manifest("a".repeat(64)))).isEqualTo(DriverStatus.READY);
    }

    @Test
    void statusIsUnavailableOfflineAndAvailableOnline() {
        assertThat(cache(true).status(manifest("a".repeat(64))))
                .isEqualTo(DriverStatus.UNAVAILABLE);
        assertThat(cache(false).status(manifest("a".repeat(64))))
                .isEqualTo(DriverStatus.AVAILABLE);
    }

    @Test
    void statusIsUnavailableWhenCacheDirNotWritable() throws IOException {
        var bogus = cacheDir.resolve("not-a-dir");
        Files.write(bogus, new byte[]{0});
        var cache = new DriverJarCache(
                new DriverProperties(bogus.resolve("nested"), "https://example.com", false),
                messageSource);
        assertThat(cache.status(manifest("a".repeat(64)))).isEqualTo(DriverStatus.UNAVAILABLE);
    }

    @Test
    void sha256HashesFileContent() throws Exception {
        var file = cacheDir.resolve("f.bin");
        Files.write(file, new byte[]{1, 2, 3});
        assertThat(DriverJarCache.sha256(file)).isEqualTo(sha256(new byte[]{1, 2, 3}));
    }

    @Test
    void sha256ThrowsForMissingFile() {
        assertThatThrownBy(() -> DriverJarCache.sha256(cacheDir.resolve("absent.bin")))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static ConnectorManifest urlManifest(HttpServer server, String sha256) {
        return new ConnectorManifest(1, "fake-engine", "Fake Engine", DbType.MONGODB,
                ConnectorCategory.DOCUMENT, "Acme", "desc", null, "logo.svg", 1234,
                SslMode.DISABLE, null, null, false,
                new ConnectorManifest.DriverArtifact("url", null, null, null, null,
                        "http://localhost:" + server.getAddress().getPort() + "/fake-engine.jar",
                        "fake-engine.jar", sha256));
    }

    private static HttpServer serve(String path, byte[] bytes, int status) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(path, exchange -> {
            exchange.sendResponseHeaders(status, status == 200 ? bytes.length : -1);
            if (status == 200) {
                try (var os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } else {
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
