package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.compliance.api.ExportDecision;
import com.bablsoft.accessflow.core.api.ExportPolicyMode;
import com.bablsoft.accessflow.core.api.QueryResultPersistenceService;
import com.bablsoft.accessflow.core.api.QueryResultPersistenceService.QueryResultSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryResultCsvRendererTest {

    private static final Instant STAMP = Instant.parse("2026-08-18T09:30:00Z");
    private static final String EMAIL = "reader@example.com";

    private QueryResultPersistenceService resultPersistence;
    private QueryResultCsvRenderer renderer;

    private final UUID queryRequestId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        resultPersistence = mock(QueryResultPersistenceService.class);
        renderer = new QueryResultCsvRenderer(resultPersistence, new ObjectMapper());
    }

    private static ExportDecision allow() {
        return new ExportDecision(true, ExportPolicyMode.ALLOW, null, false, List.of(), List.of());
    }

    private static ExportDecision watermark() {
        return new ExportDecision(true, ExportPolicyMode.WATERMARK, null, true, List.of(),
                List.of());
    }

    private static ExportDecision cap(int rowCap) {
        return new ExportDecision(true, ExportPolicyMode.ROW_CAP, rowCap, true, List.of(),
                List.of());
    }

    @Test
    void rendersHeaderAndRowsWithCrlfAndQuotesCommaCells() {
        stubSnapshot(
                "[{\"name\":\"id\",\"type\":\"int4\",\"restricted\":false},"
                        + "{\"name\":\"note\",\"type\":\"text\",\"restricted\":false}]",
                "[[1,\"a\"],[2,\"b,c\"]]");

        var csv = renderer.render(queryRequestId, allow(), EMAIL, STAMP).orElseThrow();

        assertThat(csv.filename()).isEqualTo("results-" + queryRequestId + ".csv");
        assertThat(csv.rowCount()).isEqualTo(2);
        assertThat(csv.truncated()).isFalse();
        assertThat(new String(csv.content(), StandardCharsets.UTF_8))
                .isEqualTo("id,note\r\n1,a\r\n2,\"b,c\"\r\n");
    }

    @Test
    void doublesEmbeddedQuotesInsideQuotedCell() {
        stubSnapshot(
                "[{\"name\":\"note\",\"type\":\"text\",\"restricted\":false}]",
                "[[\"say \\\"hi\\\"\"]]");

        var csv = renderer.render(queryRequestId, allow(), EMAIL, STAMP).orElseThrow();

        assertThat(new String(csv.content(), StandardCharsets.UTF_8))
                .isEqualTo("note\r\n\"say \"\"hi\"\"\"\r\n");
    }

    @Test
    void rendersNullCellAsEmptyString() {
        stubSnapshot(
                "[{\"name\":\"id\",\"type\":\"int4\",\"restricted\":false},"
                        + "{\"name\":\"note\",\"type\":\"text\",\"restricted\":false}]",
                "[[1,null]]");

        var csv = renderer.render(queryRequestId, allow(), EMAIL, STAMP).orElseThrow();

        assertThat(new String(csv.content(), StandardCharsets.UTF_8))
                .isEqualTo("id,note\r\n1,\r\n");
    }

    @Test
    void rendersNestedStructuresAsJsonText() {
        stubSnapshot(
                "[{\"name\":\"tags\",\"type\":\"jsonb\",\"restricted\":false},"
                        + "{\"name\":\"doc\",\"type\":\"jsonb\",\"restricted\":false}]",
                "[[[1,2],{\"a\":1}]]");

        var csv = renderer.render(queryRequestId, allow(), EMAIL, STAMP).orElseThrow();

        // Nested JSON contains commas, so both cells come out quoted.
        assertThat(new String(csv.content(), StandardCharsets.UTF_8))
                .isEqualTo("tags,doc\r\n\"[1,2]\",\"{\"\"a\"\":1}\"\r\n");
    }

    @Test
    void returnsEmptyWhenNoSnapshotStored() {
        when(resultPersistence.find(queryRequestId)).thenReturn(Optional.empty());

        assertThat(renderer.render(queryRequestId, allow(), EMAIL, STAMP)).isEmpty();
    }

    @Test
    void returnsEmptyWhenColumnsJsonMalformed() {
        stubSnapshot("not-json{{", "[[1]]");

        assertThat(renderer.render(queryRequestId, allow(), EMAIL, STAMP)).isEmpty();
    }

    @Test
    void capsAtTenThousandRowsAndAppendsTruncationNote() {
        var rows = IntStream.rangeClosed(1, 10_001)
                .mapToObj(i -> "[" + i + "]")
                .collect(Collectors.joining(",", "[", "]"));
        stubSnapshot("[{\"name\":\"id\",\"type\":\"int4\",\"restricted\":false}]", rows);

        var csv = renderer.render(queryRequestId, allow(), EMAIL, STAMP).orElseThrow();

        var lines = new String(csv.content(), StandardCharsets.UTF_8).split("\r\n");
        // 1 header + 10 000 data rows + 1 truncation note.
        assertThat(lines).hasSize(10_002);
        assertThat(lines[0]).isEqualTo("id");
        assertThat(lines[10_000]).isEqualTo("10000");
        assertThat(lines[10_001]).contains("truncated at 10000 rows");
        assertThat(csv.rowCount()).isEqualTo(10_000);
        assertThat(csv.truncated()).isTrue();
    }

    @Test
    void watermarkDecisionStampsHeaderAndFooterRows() {
        stubSnapshot(
                "[{\"name\":\"id\",\"type\":\"int4\",\"restricted\":false}]",
                "[[1],[2]]");

        var csv = renderer.render(queryRequestId, watermark(), EMAIL, STAMP).orElseThrow();

        assertThat(new String(csv.content(), StandardCharsets.UTF_8)).isEqualTo(
                "AccessFlow export | reader@example.com | 2026-08-18T09:30:00Z | query "
                        + queryRequestId + "\r\n"
                        + "id\r\n1\r\n2\r\n"
                        + "AccessFlow export end | 2 rows\r\n");
        assertThat(csv.truncated()).isFalse();
    }

    @Test
    void policyRowCapBelowAttachmentCapWinsAndFooterCarriesCapProvenance() {
        stubSnapshot(
                "[{\"name\":\"id\",\"type\":\"int4\",\"restricted\":false}]",
                "[[1],[2],[3]]");

        var csv = renderer.render(queryRequestId, cap(2), EMAIL, STAMP).orElseThrow();

        var text = new String(csv.content(), StandardCharsets.UTF_8);
        assertThat(text).contains("truncated at 2 rows");
        assertThat(text).endsWith("AccessFlow export end | 2 rows | capped at 2\r\n");
        assertThat(csv.rowCount()).isEqualTo(2);
        assertThat(csv.truncated()).isTrue();
    }

    @Test
    void snapshotLevelTruncationFlagPropagates() {
        when(resultPersistence.find(queryRequestId)).thenReturn(Optional.of(
                new QueryResultSnapshot(queryRequestId,
                        "[{\"name\":\"id\",\"type\":\"int4\",\"restricted\":false}]",
                        "[[1]]", 1L, true, "ROW_LIMIT", 5)));

        var csv = renderer.render(queryRequestId, allow(), EMAIL, STAMP).orElseThrow();

        assertThat(csv.truncated()).isTrue();
    }

    private void stubSnapshot(String columnsJson, String rowsJson) {
        when(resultPersistence.find(queryRequestId)).thenReturn(Optional.of(
                new QueryResultSnapshot(queryRequestId, columnsJson, rowsJson, 0L, false,
                        null, 5)));
    }
}
