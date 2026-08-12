package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.access.api.GrantUsageReportQuery;
import com.bablsoft.accessflow.access.internal.persistence.entity.GrantUsageSummaryEntity;
import com.bablsoft.accessflow.access.internal.persistence.repo.GrantUsageSummaryRepository;
import com.bablsoft.accessflow.core.api.GrantResourceKind;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.SortOrder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultGrantUsageServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID RESOURCE = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

    private final GrantUsageSummaryRepository repository = mock(GrantUsageSummaryRepository.class);
    private final DefaultGrantUsageService service =
            new DefaultGrantUsageService(repository, new GrantUsageViewMapper(new ObjectMapper()));

    private static GrantUsageSummaryEntity entity() {
        var row = new GrantUsageSummaryEntity();
        row.setId(UUID.randomUUID());
        row.setOrganizationId(ORG);
        row.setResourceKind(GrantResourceKind.DATASOURCE);
        row.setResourceId(RESOURCE);
        row.setResourceName("analytics");
        row.setPermissionId(UUID.randomUUID());
        row.setUserId(USER);
        row.setUserEmail("dev@example.test");
        row.setGrantedAt(NOW);
        row.setObservedSince(NOW);
        return row;
    }

    @Test
    void findsTheSummaryForOneGrant() {
        when(repository.findByOrganizationIdAndResourceKindAndResourceIdAndUserId(
                ORG, GrantResourceKind.DATASOURCE, RESOURCE, USER))
                .thenReturn(Optional.of(entity()));

        assertThat(service.findFor(ORG, GrantResourceKind.DATASOURCE, RESOURCE, USER))
                .isPresent()
                .get()
                .satisfies(view -> assertThat(view.resourceName()).isEqualTo("analytics"));
    }

    /**
     * A grant created since the last tick has no row. Empty must stay empty rather than becoming a
     * default "never used" — the two are indistinguishable in the data and opposite to a reviewer.
     */
    @Test
    void returnsEmptyForAnUnsummarisedOrIncompletelyIdentifiedGrant() {
        when(repository.findByOrganizationIdAndResourceKindAndResourceIdAndUserId(
                any(), any(), any(), any())).thenReturn(Optional.empty());

        assertThat(service.findFor(ORG, GrantResourceKind.DATASOURCE, RESOURCE, USER)).isEmpty();
        assertThat(service.findFor(null, GrantResourceKind.DATASOURCE, RESOURCE, USER)).isEmpty();
        assertThat(service.findFor(ORG, null, RESOURCE, USER)).isEmpty();
        assertThat(service.findFor(ORG, GrantResourceKind.DATASOURCE, null, USER)).isEmpty();
        assertThat(service.findFor(ORG, GrantResourceKind.DATASOURCE, RESOURCE, null)).isEmpty();
    }

    @Test
    void reportMapsThePageOntoViews() {
        when(repository.findAll(ArgumentMatchers.<Specification<GrantUsageSummaryEntity>>any(),
                any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entity()), Pageable.ofSize(20), 1));

        var page = service.report(ORG, GrantUsageReportQuery.empty(), PageRequest.of(0, 20));

        assertThat(page.content()).singleElement()
                .satisfies(view -> assertThat(view.userEmail()).isEqualTo("dev@example.test"));
        assertThat(page.totalElements()).isEqualTo(1);
    }



    /** Worst first, with a tiebreaker — an unstable sort silently skips rows across pages. */
    @Test
    void defaultsToNeverUsedFirstThenLongestIdleWithAStableTiebreaker() {
        when(repository.findAll(ArgumentMatchers.<Specification<GrantUsageSummaryEntity>>any(),
                any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(20), 0));

        service.report(ORG, null, PageRequest.of(0, 20));

        var captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(
                ArgumentMatchers.<Specification<GrantUsageSummaryEntity>>any(),
                captor.capture());
        var orders = captor.getValue().getSort().toList();
        assertThat(orders).hasSize(2);
        assertThat(orders.get(0).getProperty()).isEqualTo("lastUsedAt");
        assertThat(orders.get(0).getDirection()).isEqualTo(Sort.Direction.ASC);
        assertThat(orders.get(0).getNullHandling()).isEqualTo(Sort.NullHandling.NULLS_FIRST);
        assertThat(orders.get(1).getProperty()).isEqualTo("id");
    }

    @Test
    void honoursAnExplicitSort() {
        when(repository.findAll(ArgumentMatchers.<Specification<GrantUsageSummaryEntity>>any(),
                any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(20), 0));

        service.report(ORG, null, PageRequest.of(0, 20, SortOrder.desc("usageCount")));

        var captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(
                ArgumentMatchers.<Specification<GrantUsageSummaryEntity>>any(),
                captor.capture());
        assertThat(captor.getValue().getSort().toList()).singleElement()
                .satisfies(order -> assertThat(order.getProperty()).isEqualTo("usageCount"));
    }

    @Test
    void rejectsAMissingOrganization() {
        assertThatThrownBy(() -> service.report(null, null, PageRequest.of(0, 20)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("organizationId");
    }
}
