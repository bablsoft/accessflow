package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.ReviewDelegationFilter;
import com.bablsoft.accessflow.core.api.ReviewDelegationService;
import com.bablsoft.accessflow.core.api.ReviewDelegationStatus;
import com.bablsoft.accessflow.core.api.ReviewDelegationView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminReviewDelegationControllerTest {

    private ReviewDelegationService service;
    private AdminReviewDelegationController controller;

    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(ReviewDelegationService.class);
        controller = new AdminReviewDelegationController(service);
    }

    private ReviewDelegationView view() {
        return new ReviewDelegationView(UUID.randomUUID(), orgId, UUID.randomUUID(), "Alice",
                "alice@example.com", UUID.randomUUID(), "Bob", "bob@example.com", null, null, null,
                null, Instant.parse("2026-08-20T00:00:00Z"), Instant.parse("2026-08-30T00:00:00Z"),
                null, ReviewDelegationStatus.ACTIVE, Instant.parse("2026-08-16T09:00:00Z"));
    }

    @Test
    void listMapsThePageAndPassesTheFilterThrough() {
        var delegatorId = UUID.randomUUID();
        var delegateId = UUID.randomUUID();
        when(service.listForOrganization(any(), any(), any()))
                .thenReturn(new PageResponse<>(List.of(view()), 0, 20, 1, 1));

        var body = controller.list(delegatorId, delegateId, true, Pageable.ofSize(20), orgId);

        assertThat(body.content()).hasSize(1);
        assertThat(body.totalElements()).isEqualTo(1);
        var captor = ArgumentCaptor.forClass(ReviewDelegationFilter.class);
        verify(service).listForOrganization(any(), captor.capture(), any());
        assertThat(captor.getValue().delegatorUserId()).isEqualTo(delegatorId);
        assertThat(captor.getValue().delegateUserId()).isEqualTo(delegateId);
        assertThat(captor.getValue().activeOnly()).isTrue();
    }

    @Test
    void listDefaultsToNoNarrowing() {
        when(service.listForOrganization(any(), any(), any()))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        controller.list(null, null, false, Pageable.ofSize(20), orgId);

        var captor = ArgumentCaptor.forClass(ReviewDelegationFilter.class);
        verify(service).listForOrganization(any(), captor.capture(), any());
        assertThat(captor.getValue().delegatorUserId()).isNull();
        assertThat(captor.getValue().activeOnly()).isFalse();
    }
}
