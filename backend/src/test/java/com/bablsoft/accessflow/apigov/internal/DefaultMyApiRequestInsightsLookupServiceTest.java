package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.internal.persistence.repo.MyApiRequestInsightsRepository;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RiskLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultMyApiRequestInsightsLookupServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final Instant FROM = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-06-30T00:00:00Z");

    private final MyApiRequestInsightsRepository repo = mock(MyApiRequestInsightsRepository.class);
    private final DefaultMyApiRequestInsightsLookupService service =
            new DefaultMyApiRequestInsightsLookupService(repo);

    private MyApiRequestInsightsRepository.StatusBucketRow statusRow(LocalDate d, String status, long n) {
        var r = mock(MyApiRequestInsightsRepository.StatusBucketRow.class);
        when(r.getBucketDate()).thenReturn(d);
        when(r.getStatus()).thenReturn(status);
        when(r.getCnt()).thenReturn(n);
        return r;
    }

    private MyApiRequestInsightsRepository.RiskBucketRow riskRow(LocalDate d, String risk, long n) {
        var r = mock(MyApiRequestInsightsRepository.RiskBucketRow.class);
        when(r.getBucketDate()).thenReturn(d);
        when(r.getRiskLevel()).thenReturn(risk);
        when(r.getCnt()).thenReturn(n);
        return r;
    }

    @Test
    void trendsMapEnumStringsToEnums() {
        var d = LocalDate.of(2026, 6, 10);
        var statusRow = statusRow(d, "EXECUTED", 3);
        var riskRow = riskRow(d, "HIGH", 2);
        when(repo.findStatusByDay(ORG, USER, FROM, TO)).thenReturn(List.of(statusRow));
        when(repo.findRiskByDay(ORG, USER, FROM, TO)).thenReturn(List.of(riskRow));

        var trends = service.trends(ORG, USER, FROM, TO);

        assertThat(trends.statusByDay()).singleElement().satisfies(b -> {
            assertThat(b.status()).isEqualTo(QueryStatus.EXECUTED);
            assertThat(b.count()).isEqualTo(3);
            assertThat(b.date()).isEqualTo(d);
        });
        assertThat(trends.riskByDay()).singleElement().satisfies(b -> {
            assertThat(b.riskLevel()).isEqualTo(RiskLevel.HIGH);
            assertThat(b.count()).isEqualTo(2);
        });
    }

    @Test
    void statusCountsMapRows() {
        var r = mock(MyApiRequestInsightsRepository.StatusCountRow.class);
        when(r.getStatus()).thenReturn("PENDING_REVIEW");
        when(r.getCnt()).thenReturn(4L);
        when(repo.findStatusCounts(ORG, USER)).thenReturn(List.of(r));

        var counts = service.statusCounts(ORG, USER);

        assertThat(counts).singleElement().satisfies(c -> {
            assertThat(c.status()).isEqualTo(QueryStatus.PENDING_REVIEW);
            assertThat(c.count()).isEqualTo(4);
        });
    }

    @Test
    void rejectsNullScope() {
        assertThatThrownBy(() -> service.statusCounts(null, USER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.trends(ORG, null, FROM, TO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
