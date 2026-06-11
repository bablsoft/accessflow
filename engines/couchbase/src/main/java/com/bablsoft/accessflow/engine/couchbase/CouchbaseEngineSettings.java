package com.bablsoft.accessflow.engine.couchbase;

import com.couchbase.client.java.query.QueryScanConsistency;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Couchbase client tuning parsed from the host-provided {@code QueryEngineContext.config()} map
 * (bound by the host from {@code accessflow.proxy.engines.couchbase.*} — AF-418's generic
 * per-engine lane; operators set {@code ACCESSFLOW_PROXY_ENGINES_COUCHBASE_<KEY>} env vars). Key
 * names are the host&harr;plugin contract: {@code connect-timeout} and
 * {@code wait-until-ready-timeout} are ISO-8601 durations; {@code scan-consistency} is
 * {@code request-plus} (default — reads observe all mutations submitted before the query, the
 * predictable choice for a governance proxy where an approved write is often followed by a
 * verifying read) or {@code not-bounded} (Couchbase's own default, faster under load). Missing or
 * unparseable values fall back to the defaults rather than failing engine initialization.
 */
record CouchbaseEngineSettings(
        Duration connectTimeout,
        Duration waitUntilReadyTimeout,
        QueryScanConsistency scanConsistency) {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    static CouchbaseEngineSettings from(Map<String, String> config) {
        return new CouchbaseEngineSettings(
                duration(config.get("connect-timeout")),
                duration(config.get("wait-until-ready-timeout")),
                scanConsistency(config.get("scan-consistency")));
    }

    private static QueryScanConsistency scanConsistency(String raw) {
        if (raw != null && raw.strip().toLowerCase(Locale.ROOT).replace('_', '-')
                .equals("not-bounded")) {
            return QueryScanConsistency.NOT_BOUNDED;
        }
        return QueryScanConsistency.REQUEST_PLUS;
    }

    private static Duration duration(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_TIMEOUT;
        }
        try {
            return Duration.parse(raw);
        } catch (java.time.format.DateTimeParseException e) {
            return DEFAULT_TIMEOUT;
        }
    }
}
