package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.access.api.GrantUsageRecommendation;
import com.bablsoft.accessflow.access.api.GrantUsageReportQuery;
import com.bablsoft.accessflow.access.api.GrantUsageService;
import com.bablsoft.accessflow.access.api.GrantUsageView;
import com.bablsoft.accessflow.access.internal.config.AccessProperties;
import com.bablsoft.accessflow.core.api.GrantResourceKind;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultGrantUsageExportServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-06-01T10:30:00Z");

    private final GrantUsageService grantUsageService = mock(GrantUsageService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private DefaultGrantUsageExportService service(AccessProperties.Usage usage) {
        return new DefaultGrantUsageExportService(grantUsageService,
                new AccessProperties(null, null, null, usage), clock);
    }

    private static GrantUsageView view(String email, Integer granted, int used, long usageCount,
                                       Instant lastUsedAt, GrantUsageRecommendation recommendation) {
        return new GrantUsageView(UUID.randomUUID(), ORG, GrantResourceKind.DATASOURCE,
                UUID.randomUUID(), "analytics", UUID.randomUUID(), UUID.randomUUID(), email, "Dev",
                NOW.minus(Duration.ofDays(200)), null, granted, List.of("public.users"), used,
                usageCount, lastUsedAt, lastUsedAt, NOW.minus(Duration.ofDays(90)), recommendation);
    }

    private void givenRows(List<GrantUsageView> rows) {
        when(grantUsageService.report(any(), any(), any()))
                .thenReturn(new PageResponse<>(rows, 0, Math.max(1, rows.size()), rows.size(), 1));
    }

    private static String body(byte[] content) {
        return new String(content, StandardCharsets.UTF_8);
    }

    @Test
    void writesAHeaderAndOneRowPerGrant() {
        givenRows(List.of(
                view("a@example.test", 4, 1, 9, NOW.minus(Duration.ofDays(2)),
                        GrantUsageRecommendation.OVER_SCOPED),
                view("b@example.test", null, 0, 0, null, GrantUsageRecommendation.NEVER_USED)));

        var export = service(null).export(ORG, GrantUsageReportQuery.empty());

        var lines = body(export.content()).split("\r\n");
        assertThat(lines).hasSize(3);
        assertThat(lines[0]).startsWith("summary_id,resource_kind,resource_name");
        assertThat(lines[1]).contains("a@example.test").contains("OVER_SCOPED");
        assertThat(lines[2]).contains("b@example.test").contains("NEVER_USED");
        assertThat(export.rowCount()).isEqualTo(2);
        assertThat(export.truncated()).isFalse();
    }

    @Test
    void stampsTheFilenameWithTheClockInstant() {
        givenRows(List.of());

        assertThat(service(null).export(ORG, null).filename())
                .isEqualTo("over-provisioned-access-20260601T103000Z.csv");
    }

    /** Null figures mean "not applicable" and must render empty, never the literal "null". */
    @Test
    void rendersNullFiguresAsEmptyCells() {
        givenRows(List.of(view("b@example.test", null, 0, 0, null,
                GrantUsageRecommendation.NEVER_USED)));

        var row = body(service(null).export(ORG, null).content()).split("\r\n")[1];

        assertThat(row).doesNotContain("null");
        // granted_target_count, used_target_count(0), unused_target_count → ",,0,,"
        assertThat(row).contains(",,0,,");
    }

    @Test
    void computesDerivedFiguresAgainstTheClock() {
        givenRows(List.of(view("a@example.test", 4, 1, 9, NOW.minus(Duration.ofDays(3)),
                GrantUsageRecommendation.ACTIVE)));

        var row = body(service(null).export(ORG, null).content()).split("\r\n")[1];

        // days_since_last_use = 3, unused_target_count = 4 - 1 = 3
        assertThat(row).contains(",3,");
    }

    /** Fetch cap + 1 so "exactly cap rows exist" is distinguishable from a truncated page. */
    @Test
    void fetchesOneRowBeyondTheCapAndFlagsTruncation() {
        var usage = new AccessProperties.Usage(null, null, null, null, 0, 0, 0, 2, null, null);
        var rows = new ArrayList<GrantUsageView>();
        for (int i = 0; i < 3; i++) {
            rows.add(view("u" + i + "@example.test", null, 0, 0, null,
                    GrantUsageRecommendation.NEVER_USED));
        }
        givenRows(rows);

        var export = service(usage).export(ORG, null);

        assertThat(export.rowCount()).isEqualTo(2);
        assertThat(export.truncated()).isTrue();
        assertThat(body(export.content()).split("\r\n")).hasSize(3);

        var captor = ArgumentCaptor.forClass(PageRequest.class);
        verify(grantUsageService).report(any(), any(), captor.capture());
        assertThat(captor.getValue().size()).isEqualTo(3);
    }

    @Test
    void exactlyCapRowsIsNotTruncated() {
        var usage = new AccessProperties.Usage(null, null, null, null, 0, 0, 0, 2, null, null);
        givenRows(List.of(
                view("a@example.test", null, 0, 0, null, GrantUsageRecommendation.NEVER_USED),
                view("b@example.test", null, 0, 0, null, GrantUsageRecommendation.NEVER_USED)));

        var export = service(usage).export(ORG, null);

        assertThat(export.rowCount()).isEqualTo(2);
        assertThat(export.truncated()).isFalse();
    }

    @Test
    void passesTheFilterThroughAndDefaultsANullQuery() {
        givenRows(List.of());

        service(null).export(ORG, null);

        verify(grantUsageService).report(org.mockito.ArgumentMatchers.eq(ORG),
                org.mockito.ArgumentMatchers.eq(GrantUsageReportQuery.empty()), any());
    }

    @Test
    void writesOnlyTheHeaderWhenThereAreNoGrants() {
        givenRows(List.of());

        var export = service(null).export(ORG, GrantUsageReportQuery.empty());

        assertThat(export.rowCount()).isZero();
        assertThat(body(export.content()).split("\r\n")).hasSize(1);
    }
}
