package com.bablsoft.accessflow.proxy.internal.driver;

import com.bablsoft.accessflow.core.api.DriverResolutionException;
import com.bablsoft.accessflow.core.api.DriverStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

/**
 * The shared download &rarr; SHA-256-verify &rarr; on-disk-cache pipeline for connector artifact
 * JARs, extracted from {@link DefaultDriverCatalogService} so both resolution front-ends — JDBC
 * drivers ({@code DefaultDriverCatalogService}) and non-JDBC engine plugins
 * ({@code DefaultQueryEngineCatalog}) — share one cache directory, one offline-mode policy, and one
 * integrity check. Behaviour is identical to the pre-extraction service: a cached JAR is
 * re-verified against the manifest's pinned SHA-256 on every resolution (deleted and re-downloaded
 * on mismatch), offline mode fails fast with {@code OFFLINE_CACHE_MISS}, and downloads stream to a
 * {@code .part} file moved atomically into place.
 */
@Component
class DriverJarCache {

    private static final Logger log = LoggerFactory.getLogger(DriverJarCache.class);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(60);

    private final DriverProperties properties;
    private final MessageSource messageSource;
    private final HttpClient httpClient;

    DriverJarCache(DriverProperties properties, MessageSource messageSource) {
        this.properties = properties;
        this.messageSource = messageSource;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * The verified on-disk path of the manifest's artifact JAR, downloading it first when absent.
     *
     * @throws DriverResolutionException offline with no cached JAR, download failure, checksum
     *         mismatch, or unwritable cache directory.
     */
    Path ensureCachedJar(ConnectorManifest manifest) {
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

    /** Install-lifecycle status of the manifest's artifact: bundled/cached, downloadable, or not. */
    DriverStatus status(ConnectorManifest manifest) {
        if (manifest.bundled() || cachedJar(manifest).map(Files::isRegularFile).orElse(false)) {
            return DriverStatus.READY;
        }
        if (properties.offline()) {
            return DriverStatus.UNAVAILABLE;
        }
        return cacheDirWritable() ? DriverStatus.AVAILABLE : DriverStatus.UNAVAILABLE;
    }

    static String sha256(Path jarPath) {
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
        // Walk up to the nearest existing ancestor — mirroring what Files.createDirectories will
        // do on install. A fresh container whose default cache dir is several missing levels deep
        // (e.g. ~/.accessflow/drivers) is still writable as long as that ancestor is.
        for (var p = properties.cacheDir().toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.isDirectory(p)) {
                return Files.isWritable(p);
            }
            if (Files.exists(p)) {
                return false; // a non-directory ancestor blocks directory creation
            }
        }
        return false;
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
            log.info("Resolving artifact for connector {} from {}", manifest.id(), sourceUrl);
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

    private String msg(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }
}
