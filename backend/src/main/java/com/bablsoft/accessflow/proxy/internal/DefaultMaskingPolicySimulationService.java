package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.MaskingPolicyDraft;
import com.bablsoft.accessflow.core.api.MaskingPolicyResolutionService;
import com.bablsoft.accessflow.core.api.PolicySimulationLimits;
import com.bablsoft.accessflow.core.api.QueryCorpusRow;
import com.bablsoft.accessflow.core.api.QueryListFilter;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryResultPersistenceService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.ResolvedColumnMask;
import com.bablsoft.accessflow.core.api.SimulationCaveat;
import com.bablsoft.accessflow.core.api.SimulationWindow;
import com.bablsoft.accessflow.proxy.api.MaskingPolicySimulationService;
import com.bablsoft.accessflow.proxy.api.MaskingSimulationResult;
import com.bablsoft.accessflow.proxy.api.MaskingSimulationResult.ColumnImpact;
import com.bablsoft.accessflow.proxy.api.MaskingSimulationResult.Sample;
import com.bablsoft.accessflow.proxy.api.MaskingSimulationResult.UserImpact;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Replays a datasource's persisted result sets under its current masking policies and under those
 * policies with a draft applied, reporting which columns each submitter would newly see masked — or
 * newly see in the clear (issue AF-630).
 *
 * <p>Matching is on the bare column name, because that is all the persisted result carries:
 * {@code query_request_results.columns} records a name and a JDBC type, and the execution-time
 * schema and table are discarded. The result therefore names the
 * {@link SimulationCaveat#COLUMN_MATCH_BARE_NAME} caveat rather than implying a precision it does
 * not have. This matches how the live resolver behaves when it has no table context, so the
 * simulation does not diverge from enforcement — it is simply less specific.
 *
 * <p>No cell values are ever read: only the column list is loaded.
 */
@Service
@RequiredArgsConstructor
class DefaultMaskingPolicySimulationService implements MaskingPolicySimulationService {

    private static final Logger log =
            LoggerFactory.getLogger(DefaultMaskingPolicySimulationService.class);
    private static final TypeReference<List<Map<String, Object>>> COLUMNS_TYPE =
            new TypeReference<>() {};

    private final QueryRequestLookupService queryRequestLookupService;
    private final MaskingPolicyResolutionService maskingPolicyResolutionService;
    private final QueryResultPersistenceService queryResultPersistenceService;
    private final PolicySimulationLimits limits;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public MaskingSimulationResult simulate(UUID organizationId, UUID datasourceId,
                                            SimulationWindow window, MaskingPolicyDraft draft) {
        window.validate(limits.maxWindow());
        var run = new Run(organizationId, datasourceId, draft);
        var filter = new QueryListFilter(organizationId, null, datasourceId, QueryStatus.EXECUTED,
                QueryType.SELECT, window.from(), window.to());
        int cap = limits.maxRows();
        int scanned = queryRequestLookupService.streamCorpusForOrganization(filter, cap + 1, run::accept);
        return run.toResult(window, scanned > cap);
    }

    private final class Run {

        private final UUID organizationId;
        private final UUID datasourceId;
        private final MaskingPolicyDraft draft;
        private final Map<UUID, ResolvedSets> masksBySubmitter = new HashMap<>();
        private final Map<UUID, UserAccumulator> users = new LinkedHashMap<>();
        private final Map<String, int[]> columns = new TreeMap<>();
        private final List<Sample> samples = new ArrayList<>();
        private int seen;
        private int evaluated;
        private int changed;
        private int newlyMasked;
        private int newlyRevealed;

        private Run(UUID organizationId, UUID datasourceId, MaskingPolicyDraft draft) {
            this.organizationId = organizationId;
            this.datasourceId = datasourceId;
            this.draft = draft;
        }

        void accept(QueryCorpusRow row) {
            if (seen >= limits.maxRows()) {
                return; // the cap+1 probe row: counted as truncation, never evaluated
            }
            seen++;
            var columnNames = resultColumnNames(row.id());
            if (columnNames.isEmpty()) {
                // No stored result means no columns to compare. Skipping keeps evaluatedCount an
                // honest denominator instead of padding it with rows nothing could be said about.
                return;
            }
            evaluated++;
            var sets = masksBySubmitter.computeIfAbsent(row.submittedByUserId(), this::resolveFor);
            var before = maskedColumns(columnNames, sets.baseline());
            var after = maskedColumns(columnNames, sets.simulated());
            record(row, newlyMaskedIn(after, before), newlyRevealedIn(before, after));
        }

        private ResolvedSets resolveFor(UUID submitterId) {
            return new ResolvedSets(
                    maskingPolicyResolutionService
                            .resolveApplicable(organizationId, datasourceId, submitterId),
                    maskingPolicyResolutionService
                            .resolveWithDraft(organizationId, datasourceId, submitterId, draft));
        }

        private void record(QueryCorpusRow row, List<String> gained, List<String> lost) {
            if (gained.isEmpty() && lost.isEmpty()) {
                return;
            }
            changed++;
            newlyMasked += gained.isEmpty() ? 0 : 1;
            newlyRevealed += lost.isEmpty() ? 0 : 1;
            for (var column : gained) {
                columns.computeIfAbsent(column, c -> new int[2])[0]++;
            }
            for (var column : lost) {
                columns.computeIfAbsent(column, c -> new int[2])[1]++;
            }
            var user = users.computeIfAbsent(row.submittedByUserId(),
                    id -> new UserAccumulator(row.submittedByEmail(), row.submittedByDisplayName()));
            user.affectedQueries++;
            user.masked.addAll(gained);
            user.revealed.addAll(lost);
            if (samples.size() < limits.maxSamples()) {
                samples.add(new Sample(row.id(), row.submittedByEmail(), row.createdAt(), gained,
                        lost));
            }
        }

        MaskingSimulationResult toResult(SimulationWindow window, boolean truncated) {
            var columnImpacts = columns.entrySet().stream()
                    .map(e -> new ColumnImpact(e.getKey(), e.getValue()[0], e.getValue()[1]))
                    .sorted(Comparator.comparingInt(
                            (ColumnImpact c) -> c.newlyMaskedQueryCount()
                                    + c.newlyRevealedQueryCount()).reversed())
                    .toList();
            var userImpacts = users.entrySet().stream()
                    .map(e -> new UserImpact(e.getKey(), e.getValue().email, e.getValue().displayName,
                            List.copyOf(e.getValue().masked), List.copyOf(e.getValue().revealed),
                            e.getValue().affectedQueries))
                    .sorted(Comparator.comparingInt(UserImpact::affectedQueryCount).reversed())
                    .limit(limits.maxUserImpacts())
                    .toList();
            return new MaskingSimulationResult(window.from(), window.to(), datasourceId, evaluated,
                    changed, newlyMasked, newlyRevealed, truncated, userImpacts, columnImpacts,
                    samples,
                    List.of(SimulationCaveat.MEMBERSHIP_STATE_CURRENT,
                            SimulationCaveat.COLUMN_MATCH_BARE_NAME));
        }

        /**
         * Column names from the persisted result snapshot; empty when no result is stored. Tolerant
         * of unparseable JSON — a malformed row must not abort a whole simulation.
         */
        private List<String> resultColumnNames(UUID queryRequestId) {
            var result = queryResultPersistenceService.find(queryRequestId).orElse(null);
            if (result == null || result.columnsJson() == null) {
                return List.of();
            }
            try {
                var parsed = objectMapper.readValue(result.columnsJson(), COLUMNS_TYPE);
                var names = new ArrayList<String>(parsed.size());
                for (var column : parsed) {
                    var name = column.get("name");
                    if (name != null) {
                        names.add(String.valueOf(name).toLowerCase(Locale.ROOT));
                    }
                }
                return names;
            } catch (RuntimeException ex) {
                log.warn("Unparseable result columns for query {}; skipping it", queryRequestId);
                return List.of();
            }
        }
    }

    /**
     * Each covered column mapped to <em>how</em> it would be masked, keyed on the bare name.
     *
     * <p>The value matters: comparing only which columns are masked would report a draft that
     * swaps {@code FULL} for {@code PARTIAL} on the same column as no impact at all, and changing
     * a strategy is one of the commonest edits there is.
     */
    private static Map<String, String> maskedColumns(List<String> columnNames,
                                                     List<ResolvedColumnMask> masks) {
        var masked = new LinkedHashMap<String, String>();
        for (var mask : masks) {
            var keys = ColumnRefKeys.parse(mask.columnRef());
            var treatment = mask.strategy() + new TreeMap<>(mask.params()).toString();
            for (var column : columnNames) {
                if (keys.matchLevel(null, null, column) > 0) {
                    // Most specific wins is the resolver's rule; first match here is stable enough
                    // for a diff, and any change to the winner still shows up as a changed value.
                    masked.putIfAbsent(column, treatment);
                }
            }
        }
        return masked;
    }

    /** Columns masked in {@code after} that were unmasked, or masked differently, in {@code before}. */
    private static List<String> newlyMaskedIn(Map<String, String> after, Map<String, String> before) {
        return after.entrySet().stream()
                .filter(entry -> !entry.getValue().equals(before.get(entry.getKey())))
                .map(Map.Entry::getKey)
                .toList();
    }

    /** Columns that were masked in {@code before} and are no longer masked at all in {@code after}. */
    private static List<String> newlyRevealedIn(Map<String, String> before,
                                                Map<String, String> after) {
        return before.keySet().stream().filter(column -> !after.containsKey(column)).toList();
    }

    private record ResolvedSets(List<ResolvedColumnMask> baseline,
                                List<ResolvedColumnMask> simulated) {
    }

    private static final class UserAccumulator {
        private final String email;
        private final String displayName;
        private final Set<String> masked = new LinkedHashSet<>();
        private final Set<String> revealed = new LinkedHashSet<>();
        private int affectedQueries;

        private UserAccumulator(String email, String displayName) {
            this.email = email;
            this.displayName = displayName;
        }
    }
}
