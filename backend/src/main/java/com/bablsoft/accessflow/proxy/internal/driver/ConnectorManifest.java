package com.bablsoft.accessflow.proxy.internal.driver;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.SslMode;

/**
 * Declarative descriptor for a database connector, loaded from a
 * {@code connectors/<id>/connector.json} manifest by {@link ConnectorCatalog}. Replaces the
 * formerly-hardcoded {@code DriverRegistryEntry}. The PostgreSQL connector is {@code bundled}
 * (resolved against the application classloader, no {@link #driver}); every other connector
 * carries a {@link DriverArtifact} download descriptor and is fetched + SHA-256-verified on
 * demand.
 *
 * <p>Field names map 1:1 to the JSON keys (see {@code connectors/schema/connector.schema.json}).
 */
record ConnectorManifest(
        int schemaVersion,
        String id,
        String name,
        DbType dbType,
        String vendor,
        String description,
        String documentationUrl,
        String logo,
        int defaultPort,
        SslMode defaultSslMode,
        String jdbcUrlTemplate,
        String driverClassName,
        boolean bundled,
        DriverArtifact driver) {

    /** Served logo path; the frontend serves it statically from {@code public/db-icons}. */
    String iconUrl() {
        return "/db-icons/" + id + ".svg";
    }

    String jarFileName() {
        return driver == null ? null : driver.jarFileName();
    }

    /** Absolute download URL for the driver JAR given the configured Maven repository base. */
    String sourceUrl(String repositoryBaseUrl) {
        return driver == null ? null : driver.sourceUrl(repositoryBaseUrl);
    }

    String sha256() {
        return driver == null ? null : driver.sha256();
    }

    /** Download descriptor for the JDBC driver JAR — a Maven coordinate or a direct URL. */
    record DriverArtifact(
            String type,
            String groupId,
            String artifactId,
            String version,
            String classifier,
            String url,
            String fileName,
            String sha256) {

        static final String TYPE_MAVEN = "maven";
        static final String TYPE_URL = "url";

        String jarFileName() {
            if (TYPE_URL.equals(type)) {
                return fileName;
            }
            var base = artifactId + "-" + version;
            return (classifier != null && !classifier.isBlank())
                    ? base + "-" + classifier + ".jar"
                    : base + ".jar";
        }

        String sourceUrl(String repositoryBaseUrl) {
            if (TYPE_URL.equals(type)) {
                return url;
            }
            return repositoryBaseUrl + "/" + groupId.replace('.', '/') + "/"
                    + artifactId + "/" + version + "/" + jarFileName();
        }
    }
}
