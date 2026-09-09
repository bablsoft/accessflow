package com.bablsoft.accessflow.discovery.internal;

import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import com.bablsoft.accessflow.discovery.internal.config.DiscoveryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects the non-blank string values a discovery scan should run detectors over, flattening
 * nested {@code Map}/{@code List} cell values into dot-path pseudo-columns (AF-658).
 *
 * <p>Document engines materialize a page into columns that are the ordered union of <em>top-level</em>
 * fields, keeping nested objects and arrays as {@code Map}/{@code List} inside the cell. Walking
 * those yields {@code profile.contact.email} — the same dot-path convention the Elasticsearch,
 * DynamoDB and BigQuery result mappers use for masking, so a confirmed finding's AF-447 tag
 * addresses the same leaf. Map keys consume a path segment; <strong>lists are transparent fan-out
 * points and never contribute an index segment</strong>, so {@code contacts.email} addresses the
 * field in every element.
 *
 * <p>Only non-blank {@code String} leaves are collected — numbers, booleans and dates are ignored
 * exactly as non-string top-level cells always were, so stringifying them cannot manufacture
 * candidates.
 *
 * <p>Iteration order is first-encounter: the result's top-level columns first (seeded even when
 * empty, so an all-null column still reaches the sample-count filter), then nested paths as the
 * walk discovers them. The order is load-bearing — it decides which paths survive once a bound
 * binds, and it keeps higher-signal top-level columns ahead of nested ones in the AI candidate
 * list.
 *
 * <p>Four bounds keep an arbitrarily shaped document from turning a bounded sample into an
 * unbounded scan: {@code maxNestedDepth}, a per-row visit budget ({@code maxNestedLeavesPerRow},
 * charged for every node visited rather than every value kept), {@link #MAX_PATH_LENGTH}, and
 * {@link #MAX_PATHS_PER_TABLE}. Values per path are capped at the sample size, because a list
 * fans out one value per element per row rather than one per row.
 */
@Component
@Slf4j
class NestedValueFlattener {

    /** Distinct pseudo-columns per table; beyond it known paths keep filling but no new ones open. */
    static final int MAX_PATHS_PER_TABLE = 500;

    /** Longest pseudo-column name kept — the name is part of the finding's natural key. */
    static final int MAX_PATH_LENGTH = 256;

    private final int maxDepth;
    private final int maxVisitsPerRow;

    NestedValueFlattener(DiscoveryProperties properties) {
        this.maxDepth = properties.maxNestedDepth();
        this.maxVisitsPerRow = properties.maxNestedLeavesPerRow();
    }

    /**
     * Non-blank string values per column name — top-level columns plus flattened nested dot-paths.
     *
     * @param sampleSize the requested sample size, used as the per-path value cap
     */
    Map<String, List<String>> collect(SelectExecutionResult select, int sampleSize) {
        var byColumn = new LinkedHashMap<String, List<String>>();
        var columns = select.columns();
        for (var column : columns) {
            byColumn.computeIfAbsent(column.name(), key -> new ArrayList<>());
        }
        int valueCap = Math.max(1, sampleSize);
        boolean pathCapReported = false;
        for (var row : select.rows()) {
            var budget = new Budget(maxVisitsPerRow);
            for (var i = 0; i < columns.size() && i < row.size(); i++) {
                var name = columns.get(i).name();
                // A scalar top-level cell is free: the per-row budget bounds nested work only, so a
                // wide relational table behaves exactly as it did before AF-658.
                if (row.get(i) instanceof String s) {
                    append(name, s, byColumn, valueCap);
                } else {
                    flatten(name, row.get(i), byColumn, maxDepth, budget, valueCap);
                }
            }
            if (!pathCapReported && byColumn.size() >= MAX_PATHS_PER_TABLE) {
                pathCapReported = true;
                log.info("Discovery scan reached the {}-path ceiling while flattening nested values;"
                        + " further nested paths in this table are skipped", MAX_PATHS_PER_TABLE);
            }
        }
        return byColumn;
    }

    private void flatten(String path, Object value, Map<String, List<String>> byColumn,
                         int depthRemaining, Budget budget, int valueCap) {
        if (value == null || !budget.spend()) {
            return;
        }
        switch (value) {
            case String s -> append(path, s, byColumn, valueCap);
            case Map<?, ?> map -> {
                if (depthRemaining <= 0) {
                    return;
                }
                for (var entry : map.entrySet()) {
                    var key = entry.getKey() == null ? null : String.valueOf(entry.getKey()).trim();
                    if (key == null || key.isEmpty()) {
                        continue;
                    }
                    var child = path + "." + key;
                    if (child.length() > MAX_PATH_LENGTH) {
                        continue;
                    }
                    flatten(child, entry.getValue(), byColumn, depthRemaining - 1, budget, valueCap);
                }
            }
            // A list is a fan-out point, not a path segment — every element shares the parent path.
            // It still costs a depth level so a self-nesting structure cannot exhaust the stack.
            case Collection<?> collection -> {
                if (depthRemaining <= 0) {
                    return;
                }
                for (var element : collection) {
                    flatten(path, element, byColumn, depthRemaining - 1, budget, valueCap);
                }
            }
            default -> {
                // Non-string scalars are not detector input, exactly as before AF-658.
            }
        }
    }

    private static void append(String path, String value, Map<String, List<String>> byColumn,
                               int valueCap) {
        if (value.isBlank()) {
            return;
        }
        var values = byColumn.get(path);
        if (values == null) {
            if (byColumn.size() >= MAX_PATHS_PER_TABLE) {
                return;
            }
            values = new ArrayList<String>();
            byColumn.put(path, values);
        }
        if (values.size() < valueCap) {
            values.add(value);
        }
    }

    /** Per-row visit allowance, charged for every node the walk touches. */
    private static final class Budget {
        private int remaining;

        private Budget(int remaining) {
            this.remaining = remaining;
        }

        private boolean spend() {
            if (remaining <= 0) {
                return false;
            }
            remaining--;
            return true;
        }
    }
}
