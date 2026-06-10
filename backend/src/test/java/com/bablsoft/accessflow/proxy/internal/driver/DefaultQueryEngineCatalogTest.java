package com.bablsoft.accessflow.proxy.internal.driver;

import com.bablsoft.accessflow.core.api.ConnectorCategory;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.DriverResolutionException;
import com.bablsoft.accessflow.core.api.SslMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.StaticMessageSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultQueryEngineCatalogTest {

    private static final String SERVICE_FILE =
            "META-INF/services/com.bablsoft.accessflow.core.api.QueryEngine";

    @TempDir
    Path cacheDir;

    private final ConnectorCatalog connectorCatalog = mock(ConnectorCatalog.class);
    private final CredentialEncryptionService encryptionService =
            mock(CredentialEncryptionService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-10T12:00:00Z"), ZoneOffset.UTC);
    private StaticMessageSource messageSource;

    @BeforeEach
    void setUp() {
        FakeQueryEngine.reset("mongodb");
        messageSource = new StaticMessageSource();
        messageSource.addMessage("error.engine_plugin_unavailable", Locale.getDefault(),
                "no-engine-{0}");
        messageSource.addMessage("error.datasource_driver_unavailable.offline_cache_miss",
                Locale.getDefault(), "offline-miss-{0}-{1}");
        messageSource.addMessage("error.datasource_driver_unavailable.checksum_mismatch",
                Locale.getDefault(), "checksum-mismatch-{0}-{1}-{2}");
        messageSource.addMessage("error.mongo.blank", Locale.getDefault(), "blank query");
    }

    private DefaultQueryEngineCatalog catalog(boolean offline) {
        var properties = new DriverProperties(cacheDir, "https://example.com/maven2", offline);
        return new DefaultQueryEngineCatalog(connectorCatalog,
                new DriverJarCache(properties, messageSource), messageSource, encryptionService,
                new MongoEngineProperties(Duration.ofSeconds(3), Duration.ofSeconds(7), 25), clock);
    }

    private void registerManifest(String sha256) {
        var manifest = new ConnectorManifest(1, "mongodb", "MongoDB", DbType.MONGODB,
                ConnectorCategory.DOCUMENT, "MongoDB, Inc.", "desc", null, "logo.svg", 27017,
                SslMode.REQUIRE, null, null, false,
                new ConnectorManifest.DriverArtifact("url", null, null, null, null,
                        "https://example.invalid/fake-plugin.jar", "fake-plugin.jar", sha256));
        when(connectorCatalog.byDbType(DbType.MONGODB)).thenReturn(Optional.of(manifest));
    }

    /** Pre-seed the cache with a jar holding ONLY the ServiceLoader registration. */
    private String seedFixtureJar() throws IOException {
        var jar = cacheDir.resolve("fake-plugin.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(SERVICE_FILE));
            out.write((FakeQueryEngine.class.getName() + "\n").getBytes());
            out.closeEntry();
        }
        return sha256(jar);
    }

    @Test
    void engineForLoadsViaServiceLoaderInitializesOnceAndCaches() throws IOException {
        registerManifest(seedFixtureJar());
        var catalog = catalog(true);

        var first = catalog.engineFor(DbType.MONGODB);
        var second = catalog.engineFor(DbType.MONGODB);

        assertThat(first).isInstanceOf(FakeQueryEngine.class);
        assertThat(second).isSameAs(first);
        assertThat(FakeQueryEngine.initializations).hasSize(1);
    }

    @Test
    void engineForWiresContextMessagesConfigCredentialsAndClock() throws IOException {
        registerManifest(seedFixtureJar());
        when(encryptionService.decrypt("ciphertext")).thenReturn("plaintext");

        catalog(true).engineFor(DbType.MONGODB);

        var context = FakeQueryEngine.initializations.get(0);
        assertThat(context.messages().get("error.mongo.blank")).isEqualTo("blank query");
        // Unknown keys degrade to the key itself instead of throwing.
        assertThat(context.messages().get("error.mongo.not_a_key"))
                .isEqualTo("error.mongo.not_a_key");
        assertThat(context.credentials().decrypt("ciphertext")).isEqualTo("plaintext");
        assertThat(context.config()).containsEntry("connect-timeout", "PT3S")
                .containsEntry("server-selection-timeout", "PT7S")
                .containsEntry("max-pool-size", "25");
        assertThat(context.clock()).isSameAs(clock);
    }

    @Test
    void engineForThrowsUnavailableWhenNoProviderMatchesEngineId() throws IOException {
        FakeQueryEngine.reset("something-else");
        registerManifest(seedFixtureJar());

        assertThatThrownBy(() -> catalog(true).engineFor(DbType.MONGODB))
                .isInstanceOf(DriverResolutionException.class)
                .satisfies(ex -> {
                    assertThat(((DriverResolutionException) ex).reason())
                            .isEqualTo(DriverResolutionException.Reason.UNAVAILABLE);
                    assertThat(ex.getMessage()).isEqualTo("no-engine-MONGODB");
                });
    }

    @Test
    void engineForThrowsUnavailableWhenNoDocumentManifestForDbType() {
        when(connectorCatalog.byDbType(DbType.POSTGRESQL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> catalog(true).engineFor(DbType.POSTGRESQL))
                .isInstanceOf(DriverResolutionException.class)
                .satisfies(ex -> assertThat(((DriverResolutionException) ex).reason())
                        .isEqualTo(DriverResolutionException.Reason.UNAVAILABLE));
    }

    @Test
    void engineForThrowsUnavailableForRelationalManifest() {
        var relational = new ConnectorManifest(1, "postgresql", "PostgreSQL", DbType.POSTGRESQL,
                ConnectorCategory.RELATIONAL, null, null, null, "logo.svg", 5432, SslMode.DISABLE,
                "jdbc:postgresql://{host}:{port}/{database_name}", "org.postgresql.Driver", true,
                null);
        when(connectorCatalog.byDbType(DbType.POSTGRESQL)).thenReturn(Optional.of(relational));

        assertThatThrownBy(() -> catalog(true).engineFor(DbType.POSTGRESQL))
                .isInstanceOf(DriverResolutionException.class);
    }

    @Test
    void engineForOfflineWithoutCachedJarThrowsOfflineCacheMiss() {
        registerManifest("a".repeat(64));

        assertThatThrownBy(() -> catalog(true).engineFor(DbType.MONGODB))
                .isInstanceOf(DriverResolutionException.class)
                .satisfies(ex -> assertThat(((DriverResolutionException) ex).reason())
                        .isEqualTo(DriverResolutionException.Reason.OFFLINE_CACHE_MISS));
    }

    @Test
    void engineForCachedJarWithWrongChecksumThrowsChecksumMismatch() throws IOException {
        seedFixtureJar();
        registerManifest("a".repeat(64)); // pin that cannot match

        assertThatThrownBy(() -> catalog(true).engineFor(DbType.MONGODB))
                .isInstanceOf(DriverResolutionException.class)
                .satisfies(ex -> assertThat(((DriverResolutionException) ex).reason())
                        .isEqualTo(DriverResolutionException.Reason.CHECKSUM_MISMATCH));
    }

    @Test
    void bundledDocumentManifestLoadsFromApplicationClassLoader() {
        // Future-proof lane: a DOCUMENT connector marked bundled discovers providers on the app
        // classpath. The test classpath has none registered, so resolution reports UNAVAILABLE.
        var bundled = new ConnectorManifest(1, "mongodb", "MongoDB", DbType.MONGODB,
                ConnectorCategory.DOCUMENT, null, null, null, "logo.svg", 27017, SslMode.REQUIRE,
                null, null, true, null);
        when(connectorCatalog.byDbType(DbType.MONGODB)).thenReturn(Optional.of(bundled));

        assertThatThrownBy(() -> catalog(true).engineFor(DbType.MONGODB))
                .isInstanceOf(DriverResolutionException.class)
                .satisfies(ex -> assertThat(((DriverResolutionException) ex).reason())
                        .isEqualTo(DriverResolutionException.Reason.UNAVAILABLE));
    }

    @Test
    void evictDatasourceFansOutToLoadedEnginesOnly() throws IOException {
        registerManifest(seedFixtureJar());
        var catalog = catalog(true);
        var datasourceId = UUID.randomUUID();

        catalog.evictDatasource(datasourceId); // nothing loaded yet — must not download/throw
        assertThat(FakeQueryEngine.evictions).isEmpty();

        catalog.engineFor(DbType.MONGODB);
        catalog.evictDatasource(datasourceId);
        assertThat(FakeQueryEngine.evictions).containsExactly(datasourceId);
    }

    private static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
