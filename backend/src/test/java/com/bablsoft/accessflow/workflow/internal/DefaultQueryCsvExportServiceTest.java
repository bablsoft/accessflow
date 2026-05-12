package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.QueryListFilter;
import com.bablsoft.accessflow.core.api.QueryListItemView;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultQueryCsvExportServiceTest {

    @Mock QueryRequestLookupService queryRequestLookupService;
    @InjectMocks DefaultQueryCsvExportService service;

    @Test
    void emptyResultSetEmitsHeaderRowOnly() {
        var filter = filter();
        when(queryRequestLookupService.countForOrganization(filter)).thenReturn(0L);

        var export = service.exportQueries(filter);

        assertThat(export.truncated()).isFalse();
        assertThat(export.filename()).matches("queries-\\d{8}-\\d{6}\\.csv");
        assertThat(new String(export.body(), StandardCharsets.UTF_8))
                .isEqualTo("id,created_at,query_type,status,ai_risk_level,ai_risk_score,"
                        + "datasource_id,datasource_name,submitter_email,"
                        + "submitter_display_name\r\n");
    }

    @Test
    void mapsViewToCsvRowAndEscapesSpecialCharacters() {
        var filter = filter();
        var view = new QueryListItemView(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "Prod, PG",
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                "alice@example.com",
                "Alice \"the\" Analyst",
                QueryType.SELECT, QueryStatus.EXECUTED,
                RiskLevel.MEDIUM, 42,
                Instant.parse("2026-05-11T10:00:00Z"));
        when(queryRequestLookupService.countForOrganization(filter)).thenReturn(1L);
        doAnswer(invocation -> {
            Consumer<QueryListItemView> consumer = invocation.getArgument(2);
            consumer.accept(view);
            return null;
        }).when(queryRequestLookupService).streamForOrganization(eq(filter), any(int.class), any());

        var export = service.exportQueries(filter);
        var body = new String(export.body(), StandardCharsets.UTF_8);

        assertThat(body)
                .contains("11111111-1111-1111-1111-111111111111,2026-05-11T10:00:00Z,SELECT,EXECUTED,MEDIUM,42,")
                .contains("\"Prod, PG\"")
                .contains("\"Alice \"\"the\"\" Analyst\"");
    }

    @Test
    void leavesAiFieldsEmptyWhenViewLacksAnalysis() {
        var filter = filter();
        var view = new QueryListItemView(
                UUID.randomUUID(), UUID.randomUUID(), "ds", UUID.randomUUID(),
                "x@example.com", "X", QueryType.UPDATE, QueryStatus.PENDING_AI,
                null, null, Instant.parse("2026-05-11T10:00:00Z"));
        when(queryRequestLookupService.countForOrganization(filter)).thenReturn(1L);
        doAnswer(invocation -> {
            Consumer<QueryListItemView> consumer = invocation.getArgument(2);
            consumer.accept(view);
            return null;
        }).when(queryRequestLookupService).streamForOrganization(eq(filter), any(int.class), any());

        var body = new String(service.exportQueries(filter).body(), StandardCharsets.UTF_8);

        // ai_risk_level and ai_risk_score are positions 5 & 6 (1-indexed) — both empty.
        var dataRow = body.split("\r\n")[1];
        var cells = dataRow.split(",", -1);
        assertThat(cells[4]).isEmpty();
        assertThat(cells[5]).isEmpty();
    }

    @Test
    void marksTruncatedWhenCountExceedsCap() {
        var filter = filter();
        when(queryRequestLookupService.countForOrganization(filter))
                .thenReturn((long) DefaultQueryCsvExportService.MAX_EXPORT_ROWS + 1);

        var export = service.exportQueries(filter);

        assertThat(export.truncated()).isTrue();
    }

    @Test
    void passesMaxExportRowsToStreamCall() {
        var filter = filter();
        when(queryRequestLookupService.countForOrganization(filter)).thenReturn(0L);

        service.exportQueries(filter);

        verify(queryRequestLookupService)
                .streamForOrganization(eq(filter),
                        eq(DefaultQueryCsvExportService.MAX_EXPORT_ROWS),
                        any());
    }

    private static QueryListFilter filter() {
        return new QueryListFilter(UUID.randomUUID(), null, null, null, null, null, null);
    }
}
