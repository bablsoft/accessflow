package com.bablsoft.accessflow.api.internal;

import com.bablsoft.accessflow.api.internal.config.UpdateCheckProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lazily-refreshed, in-memory release update check.
 *
 * <p>{@link #status()} always answers from the cached snapshot. When the snapshot is missing or
 * older than the configured TTL it schedules one background refresh on the update-check executor
 * (virtual threads) and returns immediately, so no request ever waits on the network. There is
 * deliberately no {@code @Scheduled} job — a {@code @SchedulerLock}'d job would only populate the
 * replica that won the lock — and no persistence, which would cost a migration for one
 * install-level row. A persisted snapshot is the upgrade path if cross-replica consistency is
 * ever wanted.
 *
 * <p>Fail-soft: an unreachable host, a non-2xx response, an oversized body or unparseable JSON
 * all resolve to {@link UpdateCheckStatus#UNKNOWN} for one TTL and are logged, never thrown.
 * The only thing that leaves the process is an unauthenticated {@code GET} of a static JSON file.
 */
@Service
class DefaultUpdateCheckService implements UpdateCheckService {

    private static final Logger log = LoggerFactory.getLogger(DefaultUpdateCheckService.class);

    /** A version manifest is a few hundred bytes; anything larger is not the file we expect. */
    static final int MAX_BODY_BYTES = 64 * 1024;

    private final UpdateCheckProperties properties;
    private final RestClient restClient;
    private final Executor executor;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final String currentVersion;
    private final AtomicReference<UpdateStatusView> snapshot = new AtomicReference<>();
    private final AtomicBoolean refreshing = new AtomicBoolean();

    DefaultUpdateCheckService(UpdateCheckProperties properties,
                              @Qualifier("updateCheckRestClient") RestClient restClient,
                              @Qualifier("updateCheckExecutor") Executor executor,
                              ObjectProvider<BuildProperties> buildProperties,
                              Clock clock,
                              ObjectMapper objectMapper) {
        this.properties = properties;
        this.restClient = restClient;
        this.executor = executor;
        this.clock = clock;
        this.objectMapper = objectMapper;
        var build = buildProperties.getIfAvailable();
        this.currentVersion = build == null ? null : build.getVersion();
    }

    @Override
    public UpdateStatusView status() {
        if (!properties.enabled()) {
            return UpdateStatusView.unknown(currentVersion, null);
        }
        var current = snapshot.get();
        if (isStale(current) && refreshing.compareAndSet(false, true)) {
            try {
                executor.execute(this::refresh);
            } catch (RejectedExecutionException shuttingDown) {
                refreshing.set(false);
                log.debug("Update check refresh rejected by the executor (shutting down?)", shuttingDown);
            }
        }
        return current != null ? current : UpdateStatusView.unknown(currentVersion, null);
    }

    private boolean isStale(UpdateStatusView current) {
        return current == null
                || current.checkedAt() == null
                || current.checkedAt().plus(properties.ttl()).isBefore(clock.instant());
    }

    void refresh() {
        try {
            snapshot.set(fetch());
        } catch (RestClientException | JacksonException | IllegalArgumentException e) {
            log.warn("Release update check against {} failed: {}", properties.url(), e.getMessage());
            log.debug("Release update check failure detail", e);
            snapshot.set(UpdateStatusView.unknown(currentVersion, clock.instant()));
        } finally {
            refreshing.set(false);
        }
    }

    private UpdateStatusView fetch() {
        byte[] body = restClient.get()
                .uri(properties.url())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(byte[].class);
        if (body == null || body.length == 0) {
            throw new IllegalArgumentException("empty manifest body");
        }
        if (body.length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("manifest body exceeds " + MAX_BODY_BYTES + " bytes");
        }
        var manifest = objectMapper.readValue(body, VersionManifest.class);
        return evaluate(manifest, clock.instant());
    }

    /** The decision rule, kept side-effect free so the semver cases are testable in isolation. */
    UpdateStatusView evaluate(VersionManifest manifest, Instant now) {
        var latest = SemanticVersion.parse(manifest.version());
        if (latest.isEmpty()) {
            throw new IllegalArgumentException("manifest version is not semver: " + manifest.version());
        }
        var current = SemanticVersion.parse(currentVersion);
        if (current.isEmpty()) {
            return new UpdateStatusView(currentVersion, manifest.version(), false,
                    manifest.changelogUrl(), now, UpdateCheckStatus.UNKNOWN);
        }
        boolean available = !current.get().isPreRelease()
                && !latest.get().isPreRelease()
                && latest.get().isNewerThan(current.get());
        return new UpdateStatusView(currentVersion, manifest.version(), available,
                manifest.changelogUrl(), now,
                available ? UpdateCheckStatus.UPDATE_AVAILABLE : UpdateCheckStatus.UP_TO_DATE);
    }

    /** Wire shape of {@code website/version.json}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record VersionManifest(
            String version,
            @JsonProperty("released_at") String releasedAt,
            @JsonProperty("changelog_url") String changelogUrl) {
    }
}
