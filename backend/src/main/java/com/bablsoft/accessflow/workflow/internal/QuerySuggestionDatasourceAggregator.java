package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.QuerySuggestionCorpusLookupService;
import com.bablsoft.accessflow.core.api.QuerySuggestionCorpusRow;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SqlCanonicalizer;
import com.bablsoft.accessflow.proxy.api.QueryParser;
import com.bablsoft.accessflow.workflow.internal.config.QuerySuggestionProperties;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.QuerySuggestionEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.QuerySuggestionRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Rebuilds one datasource's {@code query_suggestions} rows from its approved query history (#776).
 *
 * <p>Four things here are less obvious than they look.
 *
 * <p><strong>The group key is canonicalised on read, not taken from {@code canonical_sql}.</strong>
 * That column is stamped only when a query executes, so grouping by it would silently drop every
 * query that cleared review but was never run — exactly the queries a suggestion rail most wants.
 *
 * <p><strong>Parsing happens once per group, not once per row.</strong> An organisation running the
 * same report a hundred times costs one parse. The corpus arrives newest-first, so a group's first
 * row is also the representative whose raw text and timestamp are kept — the raw text, never the
 * canonical form, which is upper-cased and whitespace-collapsed and would load into the editor as
 * something nobody wrote.
 *
 * <p><strong>A key that fails to parse is remembered as poisoned.</strong> Without the sentinel an
 * unparseable shape is re-parsed once per corpus row that carries it, which is the exact cost the
 * per-key scoping exists to avoid.
 *
 * <p><strong>A group with no detected tables is dropped, not admitted.</strong>
 * {@code SqlParseResult} defines an empty {@code referencedTables} as "no tables detected", never
 * "allow everything" — and {@code DatasourcePermissionChecker.rejectedTables} returns "nothing
 * rejected" for an empty set, so such a row would clear every viewer's allow-list unconditionally.
 * Dropping it here is what keeps that read-side check fail-closed; do not move this guard.
 */
@Component
@RequiredArgsConstructor
class QuerySuggestionDatasourceAggregator {

    private static final Logger log =
            LoggerFactory.getLogger(QuerySuggestionDatasourceAggregator.class);

    /** Marks a canonical key whose representative could not be parsed or resolved to tables. */
    private static final Accumulator POISONED = new Accumulator(null, null, null);

    private final QuerySuggestionCorpusLookupService corpusLookupService;
    private final SqlCanonicalizer sqlCanonicalizer;
    private final QueryParser queryParser;
    private final QuerySuggestionRepository suggestionRepository;
    private final QuerySuggestionProperties properties;

    @Transactional
    void aggregate(UUID organizationId, UUID datasourceId, Instant runStamp) {
        var since = runStamp.minus(properties.lookback());
        var rows = corpusLookupService.findCorpus(datasourceId, since,
                properties.maxCorpusRowsPerDatasource());

        Map<String, Accumulator> byKey = new LinkedHashMap<>();
        for (var row : rows) {
            accumulate(byKey, row);
        }

        var candidates = new ArrayList<Candidate>();
        int poisoned = 0;
        for (var entry : byKey.entrySet()) {
            var accumulator = entry.getValue();
            if (accumulator == POISONED) {
                poisoned++;
                continue;
            }
            if (accumulator.count < properties.minApprovedCount()) {
                continue;
            }
            candidates.add(accumulator.toCandidate(entry.getKey()));
        }

        // An engine plugin that will not resolve poisons every key at once. Sweeping on that pass
        // would delete the datasource's whole rail over a transient failure and leave it empty
        // until the next tick — so when nothing parsed, leave what is already there alone. A
        // genuinely empty corpus produces no keys at all and still sweeps, which is the case the
        // sweep exists for.
        if (poisoned > 0 && poisoned == byKey.size()) {
            log.warn("Query suggestions for datasource {}: all {} query shapes failed to parse; "
                    + "keeping the existing rows rather than sweeping them", datasourceId, poisoned);
            return;
        }

        for (var candidate : rank(candidates)) {
            upsert(organizationId, datasourceId, candidate, runStamp);
        }
        int swept = suggestionRepository.deleteStaleForDatasource(datasourceId, runStamp);
        log.debug("Query suggestions for datasource {}: {} corpus rows, {} keys ({} unparseable), "
                        + "{} kept, {} swept", datasourceId, rows.size(), byKey.size(), poisoned,
                Math.min(candidates.size(), properties.maxSuggestionsPerDatasource()), swept);
    }

    private void accumulate(Map<String, Accumulator> byKey, QuerySuggestionCorpusRow row) {
        if (row.sqlText() == null || row.sqlText().length() > properties.maxSqlLength()) {
            return;
        }
        var canonical = sqlCanonicalizer.canonicalize(row.sqlText());
        if (canonical == null) {
            return;
        }
        var key = sha256Hex(canonical);
        var accumulator = byKey.get(key);
        if (accumulator == null) {
            accumulator = open(row);
            byKey.put(key, accumulator);
        }
        if (accumulator == POISONED) {
            return;
        }
        accumulator.add(row.submittedByUserId(), row.submittedAt(),
                properties.maxTrackedSubmitters());
    }

    /** Parses the group's representative exactly once; poisons the key when it cannot be offered. */
    private Accumulator open(QuerySuggestionCorpusRow row) {
        Set<String> tables;
        try {
            tables = queryParser.parse(row.sqlText(), row.dbType()).referencedTables();
        } catch (RuntimeException ex) {
            // A query approved months ago can be unparseable today — a dialect the engine plugin
            // has since tightened, or a plugin that is not resolvable right now. Expected, not an
            // incident: skip the shape rather than cost the datasource its other suggestions.
            log.debug("Skipping suggestion candidate on datasource {}: unparseable historical SQL",
                    row.datasourceId(), ex);
            return POISONED;
        }
        if (tables.isEmpty()) {
            return POISONED;
        }
        return new Accumulator(row, row.queryType(), List.copyOf(tables));
    }

    /**
     * Keeps the strongest candidates. Only the organisation-wide terms exist here — the per-viewer
     * table-overlap term is applied at read time — so the trim orders by frequency then recency,
     * which is the part of the ranking no viewer can change.
     */
    private List<Candidate> rank(List<Candidate> candidates) {
        candidates.sort(Comparator.comparingInt(Candidate::approvedCount).reversed()
                .thenComparing(Comparator.comparing(Candidate::lastSubmittedAt).reversed()));
        return candidates.subList(0,
                Math.min(candidates.size(), properties.maxSuggestionsPerDatasource()));
    }

    private void upsert(UUID organizationId, UUID datasourceId, Candidate candidate,
                        Instant runStamp) {
        var entity = suggestionRepository
                .findByDatasourceIdAndCanonicalHash(datasourceId, candidate.canonicalHash())
                .orElseGet(() -> {
                    var fresh = new QuerySuggestionEntity();
                    fresh.setId(UUID.randomUUID());
                    fresh.setOrganizationId(organizationId);
                    fresh.setDatasourceId(datasourceId);
                    fresh.setCanonicalHash(candidate.canonicalHash());
                    fresh.setCreatedAt(runStamp);
                    return fresh;
                });
        entity.setSqlText(candidate.sqlText());
        entity.setQueryType(candidate.queryType());
        entity.setReferencedTables(candidate.referencedTables().toArray(String[]::new));
        entity.setSubmitterIds(candidate.submitterIds().toArray(UUID[]::new));
        entity.setApprovedCount(candidate.approvedCount());
        entity.setDistinctSubmitterCount(candidate.distinctSubmitterCount());
        entity.setFirstSubmittedAt(candidate.firstSubmittedAt());
        entity.setLastSubmittedAt(candidate.lastSubmittedAt());
        entity.setComputedAt(runStamp);
        entity.setUpdatedAt(runStamp);
        suggestionRepository.save(entity);
    }

    private static String sha256Hex(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private record Candidate(String canonicalHash, String sqlText, QueryType queryType,
                             List<String> referencedTables, List<UUID> submitterIds,
                             int approvedCount, int distinctSubmitterCount,
                             Instant firstSubmittedAt, Instant lastSubmittedAt) {
    }

    /** Mutable per-group tally; the representative fields are fixed by the first (newest) row. */
    private static final class Accumulator {

        private final QuerySuggestionCorpusRow representative;
        private final QueryType queryType;
        private final List<String> referencedTables;
        /** Every submitter seen, for a truthful breadth count. */
        private final Set<UUID> seenSubmitters = new LinkedHashSet<>();
        /** The prefix of {@link #seenSubmitters} actually stored, bounding the array width. */
        private final Set<UUID> trackedSubmitters = new LinkedHashSet<>();
        private Instant firstSubmittedAt;
        private int count;

        private Accumulator(QuerySuggestionCorpusRow representative, QueryType queryType,
                            List<String> referencedTables) {
            this.representative = representative;
            this.queryType = queryType;
            this.referencedTables = referencedTables;
            this.firstSubmittedAt = representative == null ? null : representative.submittedAt();
        }

        /**
         * The stored submitter list is capped, but the breadth count is not: counting off the
         * capped set would make every shape run by more than {@code maxTrackedSubmitters} people
         * report the cap, and would double-count anyone who reappeared after it was reached.
         */
        private void add(UUID submitterId, Instant submittedAt, int maxTrackedSubmitters) {
            count++;
            if (submitterId != null && seenSubmitters.add(submitterId)
                    && trackedSubmitters.size() < maxTrackedSubmitters) {
                trackedSubmitters.add(submitterId);
            }
            if (submittedAt != null && submittedAt.isBefore(firstSubmittedAt)) {
                firstSubmittedAt = submittedAt;
            }
        }

        private Candidate toCandidate(String canonicalHash) {
            return new Candidate(canonicalHash, representative.sqlText(), queryType,
                    referencedTables, List.copyOf(trackedSubmitters), count, seenSubmitters.size(),
                    firstSubmittedAt, representative.submittedAt());
        }
    }
}
