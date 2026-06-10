package com.bablsoft.accessflow.proxy.internal.driver;

import com.bablsoft.accessflow.core.api.DbType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Loads and validates the declarative connector catalog from the classpath
 * ({@code connectors/<id>/connector.json}, bundled into the jar from the repo-root
 * {@code connectors/} folder via the Maven resources plugin) at startup. Replaces the
 * formerly-hardcoded {@code DriverRegistry}.
 *
 * <p>Adding a supported database is a data change (a new manifest), not a code change. Loading
 * is fail-fast: any malformed or inconsistent manifest aborts application startup.
 */
@Component
class ConnectorCatalog {

    private static final Logger log = LoggerFactory.getLogger(ConnectorCatalog.class);
    private static final String LOCATION_PATTERN = "classpath*:connectors/*/connector.json";
    private static final Pattern FOLDER_PATTERN = Pattern.compile("connectors/([^/]+)/connector\\.json");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final Map<String, ConnectorManifest> byId = new LinkedHashMap<>();
    private final Map<DbType, ConnectorManifest> byDialect = new EnumMap<>(DbType.class);
    private final List<ConnectorManifest> ordered;

    ConnectorCatalog() {
        this(new PathMatchingResourcePatternResolver());
    }

    ConnectorCatalog(PathMatchingResourcePatternResolver resolver) {
        List<ConnectorManifest> loaded = new ArrayList<>();
        Resource[] resources;
        try {
            resources = resolver.getResources(LOCATION_PATTERN);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan connector manifests", e);
        }
        for (Resource resource : resources) {
            loaded.add(parseAndValidate(resource));
        }
        if (loaded.isEmpty()) {
            throw new IllegalStateException(
                    "No connector manifests found on the classpath (" + LOCATION_PATTERN + ")");
        }
        loaded.sort(Comparator
                .comparingInt((ConnectorManifest m) -> m.dbType() == DbType.CUSTOM ? 1 : 0)
                .thenComparingInt(m -> m.dbType().ordinal())
                .thenComparing(ConnectorManifest::id));
        for (ConnectorManifest manifest : loaded) {
            register(manifest);
        }
        this.ordered = List.copyOf(loaded);
        log.info("Loaded {} database connector(s): {}", ordered.size(), byId.keySet());
    }

    /** All connectors, dialects first (by {@link DbType} ordinal), then CUSTOM connectors by id. */
    Collection<ConnectorManifest> all() {
        return ordered;
    }

    Optional<ConnectorManifest> byId(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** The connector backing one of the five first-class dialects (never {@link DbType#CUSTOM}). */
    Optional<ConnectorManifest> byDbType(DbType dbType) {
        return Optional.ofNullable(byDialect.get(dbType));
    }

    ConnectorManifest requireByDbType(DbType dbType) {
        var manifest = byDialect.get(dbType);
        if (manifest == null) {
            throw new IllegalStateException("No connector manifest for db type " + dbType);
        }
        return manifest;
    }

    private void register(ConnectorManifest manifest) {
        if (byId.put(manifest.id(), manifest) != null) {
            throw new IllegalStateException("Duplicate connector id: " + manifest.id());
        }
        if (manifest.dbType() != DbType.CUSTOM
                && byDialect.put(manifest.dbType(), manifest) != null) {
            throw new IllegalStateException(
                    "Multiple connectors map to dialect " + manifest.dbType());
        }
    }

    private ConnectorManifest parseAndValidate(Resource resource) {
        ConnectorManifest manifest;
        try (InputStream in = resource.getInputStream()) {
            manifest = MAPPER.readValue(in, ConnectorManifest.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read connector manifest " + resource, e);
        }
        validate(manifest, folderOf(resource));
        return manifest;
    }

    private void validate(ConnectorManifest m, String folderId) {
        if (m.schemaVersion() != 1) {
            throw new IllegalStateException("Unsupported connector schemaVersion " + m.schemaVersion()
                    + " for " + m.id());
        }
        require(m.id() != null && !m.id().isBlank(), "connector id is blank");
        require(folderId == null || folderId.equals(m.id()),
                "connector id '" + m.id() + "' does not match folder '" + folderId + "'");
        require(m.name() != null && !m.name().isBlank(), "connector " + m.id() + " name is blank");
        require(m.dbType() != null, "connector " + m.id() + " dbType is null");
        require(m.defaultSslMode() != null, "connector " + m.id() + " defaultSslMode is null");
        // Engine-managed (non-RELATIONAL) connectors connect through a native engine plugin, not
        // JDBC — they carry no jdbcUrlTemplate or driverClassName. The JDBC fields are required
        // only for relational connectors.
        if (!m.requiresEngine()) {
            require(m.jdbcUrlTemplate() != null && !m.jdbcUrlTemplate().isBlank(),
                    "connector " + m.id() + " jdbcUrlTemplate is blank");
            require(m.driverClassName() != null && !m.driverClassName().isBlank(),
                    "connector " + m.id() + " driverClassName is blank");
        }
        if (m.bundled()) {
            require(m.driver() == null, "bundled connector " + m.id() + " must not declare a driver");
        } else {
            require(m.driver() != null, "non-bundled connector " + m.id() + " must declare a driver");
            require(m.sha256() != null && SHA256.matcher(m.sha256()).matches(),
                    "connector " + m.id() + " has an invalid sha256");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static String folderOf(Resource resource) {
        try {
            var matcher = FOLDER_PATTERN.matcher(resource.getURL().toString());
            return matcher.find() ? matcher.group(1) : null;
        } catch (IOException e) {
            return null;
        }
    }
}
