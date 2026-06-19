package com.bablsoft.accessflow.compliance.internal;

import com.bablsoft.accessflow.compliance.api.ClassifiedAccessReportRow;
import com.bablsoft.accessflow.compliance.api.MatchedClassification;
import com.bablsoft.accessflow.core.api.OrganizationDataClassificationView;
import com.bablsoft.accessflow.workflow.api.QuerySnapshotView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Joins executed-query snapshots to data-classification tags by datasource + table name, producing
 * one {@link ClassifiedAccessReportRow} per snapshot that touched at least one classified object
 * (#459). Snapshots with no classified match are dropped — the report is "queries touching
 * classified objects".
 */
final class ClassificationJoiner {

    private ClassificationJoiner() {
    }

    static List<ClassifiedAccessReportRow> join(List<QuerySnapshotView> snapshots,
                                                List<OrganizationDataClassificationView> classifications,
                                                Map<UUID, String> submitterEmails,
                                                Map<UUID, String> datasourceNames) {
        Map<UUID, List<OrganizationDataClassificationView>> byDatasource = new LinkedHashMap<>();
        for (var tag : classifications) {
            byDatasource.computeIfAbsent(tag.datasourceId(), k -> new ArrayList<>()).add(tag);
        }

        var rows = new ArrayList<ClassifiedAccessReportRow>();
        for (var snapshot : snapshots) {
            var tags = byDatasource.get(snapshot.datasourceId());
            if (tags == null || tags.isEmpty()) {
                continue;
            }
            var matched = matchesFor(snapshot.referencedTables(), tags);
            if (matched.isEmpty()) {
                continue;
            }
            rows.add(new ClassifiedAccessReportRow(
                    snapshot.queryRequestId(),
                    snapshot.datasourceId(),
                    datasourceNames.get(snapshot.datasourceId()),
                    snapshot.submittedBy(),
                    submitterEmails.get(snapshot.submittedBy()),
                    snapshot.queryType(),
                    snapshot.referencedTables(),
                    matched,
                    snapshot.rowsAffected(),
                    snapshot.executedAt()));
        }
        return rows;
    }

    private static List<MatchedClassification> matchesFor(List<String> referencedTables,
                                                          List<OrganizationDataClassificationView> tags) {
        Set<MatchedClassification> matched = new LinkedHashSet<>();
        for (var referenced : referencedTables) {
            var refNormalized = TableNameNormalizer.normalize(referenced);
            for (var tag : tags) {
                if (TableNameNormalizer.matches(refNormalized, TableNameNormalizer.normalize(tag.tableName()))) {
                    matched.add(new MatchedClassification(tag.tableName(), tag.columnName(),
                            tag.classification()));
                }
            }
        }
        return List.copyOf(matched);
    }
}
