package com.bablsoft.accessflow.proxy.internal.driver;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.DriverResolutionException;
import com.bablsoft.accessflow.core.api.EngineMessages;
import com.bablsoft.accessflow.core.api.QueryEngine;
import com.bablsoft.accessflow.core.api.QueryEngineCatalog;
import com.bablsoft.accessflow.core.api.QueryEngineContext;
import com.bablsoft.accessflow.core.api.SecretResolutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.Clock;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link QueryEngineCatalog}: resolves non-JDBC query engines (AF-414) the same way
 * {@link DefaultDriverCatalogService} resolves JDBC drivers. The engine's connector manifest
 * (any non-RELATIONAL {@code category}, {@code bundled=false}) pins a shaded plugin JAR by
 * URL/Maven coordinates + SHA-256; {@link DriverJarCache} downloads and verifies it into the
 * shared driver cache; the JAR is loaded into an isolated child {@link URLClassLoader}; and the
 * {@link QueryEngine} implementation is discovered via {@link ServiceLoader}, matched by
 * {@link QueryEngine#engineId()} against the connector id. The engine is initialized once with a
 * {@link QueryEngineContext} (host message resolution, credential resolution — local AES
 * decryption or an external secret-store fetch for {@code vault:}/{@code aws:}/{@code azure:}
 * references (AF-448) — the per-engine
 * tuning config from {@code accessflow.proxy.engines.<id>.*}, the UTC clock) and cached for the
 * application lifetime — like JDBC connector classloaders, engine classloaders are never
 * unloaded.
 */
@Service
class DefaultQueryEngineCatalog implements QueryEngineCatalog {

    private static final Logger log = LoggerFactory.getLogger(DefaultQueryEngineCatalog.class);

    private final ConnectorCatalog catalog;
    private final DriverJarCache jarCache;
    private final MessageSource messageSource;
    private final SecretResolutionService secretResolutionService;
    private final EngineConfigProperties engineConfigProperties;
    private final Clock clock;
    private final Map<String, QueryEngine> engines = new ConcurrentHashMap<>();

    DefaultQueryEngineCatalog(ConnectorCatalog catalog, DriverJarCache jarCache,
                              MessageSource messageSource,
                              SecretResolutionService secretResolutionService,
                              EngineConfigProperties engineConfigProperties, Clock clock) {
        this.catalog = catalog;
        this.jarCache = jarCache;
        this.messageSource = messageSource;
        this.secretResolutionService = secretResolutionService;
        this.engineConfigProperties = engineConfigProperties;
        this.clock = clock;
    }

    @Override
    public QueryEngine engineFor(DbType dbType) {
        var manifest = catalog.byDbType(dbType)
                .filter(ConnectorManifest::requiresEngine)
                .orElseThrow(() -> new DriverResolutionException(
                        dbType,
                        DriverResolutionException.Reason.UNAVAILABLE,
                        msg("error.engine_plugin_unavailable", dbType.name())));
        var cached = engines.get(manifest.id());
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            var nowCached = engines.get(manifest.id());
            if (nowCached != null) {
                return nowCached;
            }
            var engine = load(manifest);
            engine.initialize(new QueryEngineContext(
                    hostMessages(), secretResolutionService::resolve, engineConfig(manifest.id()), clock));
            engines.put(manifest.id(), engine);
            log.info("Loaded query engine {} ({})", manifest.id(), engine.getClass().getName());
            return engine;
        }
    }

    @Override
    public boolean isEngineManaged(DbType dbType) {
        return catalog.byDbType(dbType)
                .map(ConnectorManifest::requiresEngine)
                .orElse(false);
    }

    @Override
    public void evictDatasource(UUID datasourceId) {
        for (var entry : engines.entrySet()) {
            try {
                entry.getValue().evictDatasource(datasourceId);
            } catch (RuntimeException ex) {
                log.error("Engine {} failed to evict datasource {}: {}",
                        entry.getKey(), datasourceId, ex.getMessage(), ex);
            }
        }
    }

    private QueryEngine load(ConnectorManifest manifest) {
        var loader = manifest.bundled() ? getClass().getClassLoader() : pluginLoader(manifest);
        try {
            for (var provider : ServiceLoader.load(QueryEngine.class, loader)) {
                if (manifest.id().equals(provider.engineId())) {
                    return provider;
                }
            }
        } catch (ServiceConfigurationError e) {
            throw new DriverResolutionException(
                    manifest.dbType(),
                    DriverResolutionException.Reason.UNAVAILABLE,
                    msg("error.engine_plugin_unavailable", manifest.dbType().name()),
                    e);
        }
        throw new DriverResolutionException(
                manifest.dbType(),
                DriverResolutionException.Reason.UNAVAILABLE,
                msg("error.engine_plugin_unavailable", manifest.dbType().name()));
    }

    private ClassLoader pluginLoader(ConnectorManifest manifest) {
        var jarPath = jarCache.ensureCachedJar(manifest);
        try {
            return new URLClassLoader("accessflow-engine-" + manifest.id(),
                    new URL[]{jarPath.toUri().toURL()}, getClass().getClassLoader());
        } catch (IOException e) {
            throw new DriverResolutionException(
                    manifest.dbType(),
                    DriverResolutionException.Reason.UNAVAILABLE,
                    msg("error.engine_plugin_unavailable", manifest.dbType().name()),
                    e);
        }
    }

    /**
     * Resolves against the host {@code MessageSource} with the calling thread's locale at call
     * time; unknown keys fall back to the key itself so a plugin newer than the host degrades
     * gracefully instead of throwing.
     */
    private EngineMessages hostMessages() {
        return (key, args) ->
                messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }

    private Map<String, String> engineConfig(String engineId) {
        return engineConfigProperties.forEngine(engineId);
    }

    private String msg(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }
}
