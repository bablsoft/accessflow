package com.bablsoft.accessflow.engine.databricks;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Databricks client tuning parsed from the host-provided {@code QueryEngineContext.config()} map
 * (bound by the host from {@code accessflow.proxy.engines.databricks.*} — AF-418's generic
 * per-engine lane; operators set {@code ACCESSFLOW_PROXY_ENGINES_DATABRICKS_<KEY>} env vars). Key
 * names are the host&harr;plugin contract: {@code connect-timeout} (the JDK HttpClient's TCP
 * connect timeout, default {@code PT10S}), {@code wait-timeout} (the Statement Execution API's
 * server-side {@code wait_timeout} hybrid-wait window, default {@code PT10S}, clamped to the API's
 * allowed 5–50 s and formatted {@code "10s"}), {@code poll-interval} (the client-side cadence
 * between status GETs while a statement is {@code PENDING}/{@code RUNNING}, default {@code PT1S}),
 * {@code result-disposition} (AF-633 — {@code auto} / {@code inline} / {@code external-links},
 * default {@code auto}) and {@code max-result-bytes} (AF-633 — the engine-side byte backstop while
 * streaming result chunks, default 50 MiB, clamped to 1 MiB–1 GiB). Missing or unparseable values
 * fall back to the defaults rather than failing engine initialization.
 */
record DatabricksEngineSettings(Duration connectTimeout, Duration waitTimeout,
                                Duration pollInterval, ResultDisposition resultDisposition,
                                long maxResultBytes) {

    /**
     * How results are requested from the Statement Execution API. {@code AUTO} submits
     * {@code INLINE} and falls back once to {@code EXTERNAL_LINKS} when the API's ~25 MiB inline
     * ceiling is hit; the other two force a single mode.
     */
    enum ResultDisposition {
        AUTO, INLINE, EXTERNAL_LINKS
    }

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_WAIT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1);
    private static final long MIN_WAIT_SECONDS = 5;
    private static final long MAX_WAIT_SECONDS = 50;
    private static final long DEFAULT_MAX_RESULT_BYTES = 52_428_800L;
    private static final long MIN_MAX_RESULT_BYTES = 1_048_576L;
    private static final long MAX_MAX_RESULT_BYTES = 1_073_741_824L;

    static DatabricksEngineSettings from(Map<String, String> config) {
        var cfg = config == null ? Map.<String, String>of() : config;
        return new DatabricksEngineSettings(
                duration(cfg.get("connect-timeout"), DEFAULT_CONNECT_TIMEOUT),
                clampWait(duration(cfg.get("wait-timeout"), DEFAULT_WAIT_TIMEOUT)),
                duration(cfg.get("poll-interval"), DEFAULT_POLL_INTERVAL),
                disposition(cfg.get("result-disposition")),
                clampBytes(bytes(cfg.get("max-result-bytes"))));
    }

    /** The {@code wait_timeout} request value the API expects, e.g. {@code "10s"}. */
    String waitTimeoutValue() {
        return waitTimeout.toSeconds() + "s";
    }

    private static Duration clampWait(Duration wait) {
        long seconds = Math.clamp(wait.toSeconds(), MIN_WAIT_SECONDS, MAX_WAIT_SECONDS);
        return Duration.ofSeconds(seconds);
    }

    private static Duration duration(String raw, Duration fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            var parsed = Duration.parse(raw);
            return parsed.isNegative() || parsed.isZero() ? fallback : parsed;
        } catch (java.time.format.DateTimeParseException e) {
            return fallback;
        }
    }

    /** Lenient: {@code external-links}, {@code EXTERNAL_LINKS} and unknown text all resolve. */
    private static ResultDisposition disposition(String raw) {
        if (raw == null || raw.isBlank()) {
            return ResultDisposition.AUTO;
        }
        var normalized = raw.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        for (var candidate : ResultDisposition.values()) {
            if (candidate.name().equals(normalized)) {
                return candidate;
            }
        }
        return ResultDisposition.AUTO;
    }

    private static long bytes(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MAX_RESULT_BYTES;
        }
        try {
            long parsed = Long.parseLong(raw.strip());
            return parsed <= 0 ? DEFAULT_MAX_RESULT_BYTES : parsed;
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_RESULT_BYTES;
        }
    }

    private static long clampBytes(long value) {
        return Math.clamp(value, MIN_MAX_RESULT_BYTES, MAX_MAX_RESULT_BYTES);
    }
}
