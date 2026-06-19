package com.bablsoft.accessflow.compliance.internal;

import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.OrganizationDataClassificationView;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.workflow.api.QuerySnapshotView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ClassificationJoinerTest {

    private final UUID dsA = UUID.randomUUID();
    private final UUID dsB = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final Instant executedAt = Instant.parse("2026-05-01T10:00:00Z");

    private QuerySnapshotView snapshot(UUID datasourceId, List<String> tables) {
        return new QuerySnapshotView(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                datasourceId, userId, "SELECT 1", QueryType.SELECT, false, DbType.POSTGRESQL,
                tables, null, null, "[]", 5L, 10, executedAt, executedAt);
    }

    private OrganizationDataClassificationView tag(UUID datasourceId, String table, String column,
                                                   DataClassification classification) {
        return new OrganizationDataClassificationView(UUID.randomUUID(), datasourceId, "DS",
                table, column, classification, null, executedAt, executedAt);
    }

    @Test
    void matchesSchemaQualifiedReferenceAgainstBareTag() {
        var snapshots = List.of(snapshot(dsA, List.of("public.customers")));
        var tags = List.of(tag(dsA, "customers", "ssn", DataClassification.PII));

        var rows = ClassificationJoiner.join(snapshots, tags, Map.of(userId, "a@x.com"),
                Map.of(dsA, "Prod"));

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().datasourceName()).isEqualTo("Prod");
        assertThat(rows.getFirst().submitterEmail()).isEqualTo("a@x.com");
        assertThat(rows.getFirst().matched()).hasSize(1);
        assertThat(rows.getFirst().matched().getFirst().classification()).isEqualTo(DataClassification.PII);
    }

    @Test
    void dropsSnapshotsTouchingNoClassifiedObject() {
        var snapshots = List.of(snapshot(dsA, List.of("public.orders")));
        var tags = List.of(tag(dsA, "customers", null, DataClassification.PII));

        var rows = ClassificationJoiner.join(snapshots, tags, Map.of(), Map.of());

        assertThat(rows).isEmpty();
    }

    @Test
    void isolatesClassificationsPerDatasource() {
        // A query on dsB referencing "customers" must NOT match dsA's "customers" classification.
        var snapshots = List.of(snapshot(dsB, List.of("customers")));
        var tags = List.of(tag(dsA, "customers", "ssn", DataClassification.PII));

        var rows = ClassificationJoiner.join(snapshots, tags, Map.of(), Map.of());

        assertThat(rows).isEmpty();
    }

    @Test
    void collapsesMultipleColumnTagsOnSameTableToDistinctMatches() {
        var snapshots = List.of(snapshot(dsA, List.of("customers")));
        var tags = List.of(
                tag(dsA, "customers", "ssn", DataClassification.PII),
                tag(dsA, "customers", "card", DataClassification.PCI));

        var rows = ClassificationJoiner.join(snapshots, tags, Map.of(), Map.of(dsA, "Prod"));

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().matched()).hasSize(2);
        assertThat(rows.getFirst().matched())
                .extracting(m -> m.classification())
                .containsExactlyInAnyOrder(DataClassification.PII, DataClassification.PCI);
    }

    @Test
    void emptyInputsProduceNoRows() {
        assertThat(ClassificationJoiner.join(List.of(), List.of(), Map.of(), Map.of())).isEmpty();
    }
}
