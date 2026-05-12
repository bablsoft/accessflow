package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.QueryListFilter;
import com.bablsoft.accessflow.core.api.QueryListItemView;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.workflow.api.QueryCsvExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
class DefaultQueryCsvExportService implements QueryCsvExportService {

    static final int MAX_EXPORT_ROWS = 50_000;

    private static final DateTimeFormatter FILENAME_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private static final List<String> HEADER = List.of(
            "id", "created_at", "query_type", "status", "ai_risk_level", "ai_risk_score",
            "datasource_id", "datasource_name", "submitter_email", "submitter_display_name");

    private final QueryRequestLookupService queryRequestLookupService;

    @Override
    public CsvExport exportQueries(QueryListFilter filter) {
        boolean truncated = queryRequestLookupService.countForOrganization(filter) > MAX_EXPORT_ROWS;

        var buffer = new StringWriter();
        try {
            CsvWriter.writeRow(buffer, HEADER);
            queryRequestLookupService.streamForOrganization(filter, MAX_EXPORT_ROWS, view -> {
                try {
                    CsvWriter.writeRow(buffer, toRow(view));
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }

        var filename = "queries-" + FILENAME_TIMESTAMP.format(Instant.now()) + ".csv";
        return new CsvExport(buffer.toString().getBytes(StandardCharsets.UTF_8), filename, truncated);
    }

    private static List<String> toRow(QueryListItemView view) {
        return List.of(
                stringOf(view.id()),
                stringOf(view.createdAt()),
                stringOf(view.queryType()),
                stringOf(view.status()),
                stringOf(view.aiRiskLevel()),
                stringOf(view.aiRiskScore()),
                stringOf(view.datasourceId()),
                stringOf(view.datasourceName()),
                stringOf(view.submittedByEmail()),
                stringOf(view.submittedByDisplayName()));
    }

    private static String stringOf(Object value) {
        return value == null ? "" : value.toString();
    }
}
