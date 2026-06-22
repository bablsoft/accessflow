package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DatasourceRef;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.workflow.api.BreakGlassAlreadyReviewedException;
import com.bablsoft.accessflow.workflow.api.BreakGlassEventFilter;
import com.bablsoft.accessflow.workflow.api.BreakGlassEventNotFoundException;
import com.bablsoft.accessflow.workflow.api.BreakGlassStatus;
import com.bablsoft.accessflow.workflow.api.SelfAcknowledgeNotAllowedException;
import com.bablsoft.accessflow.workflow.events.BreakGlassReviewedEvent;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.BreakGlassEventEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.BreakGlassEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultBreakGlassAdminServiceTest {

    @Mock BreakGlassEventRepository repository;
    @Mock QueryRequestLookupService queryRequestLookupService;
    @Mock DatasourceLookupService datasourceLookupService;
    @Mock UserQueryService userQueryService;
    @Mock ApplicationEventPublisher eventPublisher;

    DefaultBreakGlassAdminService service;

    private final UUID organizationId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();
    private final UUID queryId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID submitterId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultBreakGlassAdminService(repository, queryRequestLookupService,
                datasourceLookupService, userQueryService, eventPublisher);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void listMapsEntitiesToViewsWithResolvedNames() {
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(page(pendingEntity()));
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(snapshot()));
        when(datasourceLookupService.findRef(datasourceId))
                .thenReturn(Optional.of(new DatasourceRef(datasourceId, "prod-db")));
        when(userQueryService.findById(submitterId))
                .thenReturn(Optional.of(user(submitterId, "alice@x.io", "Alice")));

        var result = service.list(organizationId, new BreakGlassEventFilter(
                BreakGlassStatus.PENDING_REVIEW, null, null, null, null), PageRequest.of(0, 20));

        assertThat(result.totalElements()).isEqualTo(1);
        var view = result.content().get(0);
        assertThat(view.datasourceName()).isEqualTo("prod-db");
        assertThat(view.submittedByDisplayName()).isEqualTo("Alice");
        assertThat(view.executionStatus()).isEqualTo(QueryStatus.EXECUTED);
        assertThat(view.sqlText()).isEqualTo("SELECT 1");
        assertThat(view.status()).isEqualTo(BreakGlassStatus.PENDING_REVIEW);
    }

    @Test
    void listToleratesMissingLookups() {
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(page(pendingEntity()));
        when(queryRequestLookupService.findById(any())).thenReturn(Optional.empty());
        when(datasourceLookupService.findRef(any())).thenReturn(Optional.empty());
        when(userQueryService.findById(any())).thenReturn(Optional.empty());

        var view = service.list(organizationId, null, PageRequest.of(0, 20)).content().get(0);

        assertThat(view.datasourceName()).isNull();
        assertThat(view.submittedByDisplayName()).isNull();
        assertThat(view.executionStatus()).isNull();
    }

    @Test
    void acknowledgeTransitionsToReviewedAndPublishesEvent() {
        when(repository.findByIdAndOrganizationId(eventId, organizationId))
                .thenReturn(Optional.of(pendingEntity()));
        when(userQueryService.findById(adminId))
                .thenReturn(Optional.of(user(adminId, "admin@x.io", "Admin")));

        var view = service.acknowledge(organizationId, eventId, adminId, "reconciled");

        assertThat(view.status()).isEqualTo(BreakGlassStatus.REVIEWED);
        assertThat(view.reviewedByUserId()).isEqualTo(adminId);
        assertThat(view.reviewComment()).isEqualTo("reconciled");
        assertThat(view.reviewedAt()).isNotNull();
        verify(eventPublisher).publishEvent(any(BreakGlassReviewedEvent.class));
    }

    @Test
    void acknowledgeBlankCommentStoredAsNull() {
        when(repository.findByIdAndOrganizationId(eventId, organizationId))
                .thenReturn(Optional.of(pendingEntity()));

        var view = service.acknowledge(organizationId, eventId, adminId, "   ");

        assertThat(view.reviewComment()).isNull();
    }

    @Test
    void acknowledgeRejectsSelfAcknowledge() {
        when(repository.findByIdAndOrganizationId(eventId, organizationId))
                .thenReturn(Optional.of(pendingEntity()));

        assertThatThrownBy(() -> service.acknowledge(organizationId, eventId, submitterId, null))
                .isInstanceOf(SelfAcknowledgeNotAllowedException.class);

        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void acknowledgeRejectsAlreadyReviewed() {
        var entity = pendingEntity();
        entity.setStatus(BreakGlassStatus.REVIEWED);
        when(repository.findByIdAndOrganizationId(eventId, organizationId))
                .thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.acknowledge(organizationId, eventId, adminId, null))
                .isInstanceOf(BreakGlassAlreadyReviewedException.class);
    }

    @Test
    void acknowledgeThrowsNotFound() {
        when(repository.findByIdAndOrganizationId(eventId, organizationId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acknowledge(organizationId, eventId, adminId, null))
                .isInstanceOf(BreakGlassEventNotFoundException.class);
    }

    @Test
    void getThrowsNotFound() {
        when(repository.findByIdAndOrganizationId(eventId, organizationId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(organizationId, eventId))
                .isInstanceOf(BreakGlassEventNotFoundException.class);
    }

    private Page<BreakGlassEventEntity> page(BreakGlassEventEntity entity) {
        return new PageImpl<>(java.util.List.of(entity), Pageable.ofSize(20), 1);
    }

    private BreakGlassEventEntity pendingEntity() {
        var entity = new BreakGlassEventEntity();
        entity.setId(eventId);
        entity.setQueryRequestId(queryId);
        entity.setOrganizationId(organizationId);
        entity.setDatasourceId(datasourceId);
        entity.setSubmittedBy(submitterId);
        entity.setJustification("prod is down");
        entity.setStatus(BreakGlassStatus.PENDING_REVIEW);
        entity.setCreatedAt(Instant.now());
        return entity;
    }

    private QueryRequestSnapshot snapshot() {
        return new QueryRequestSnapshot(queryId, datasourceId, organizationId, submitterId,
                "SELECT 1", QueryType.SELECT, false, QueryStatus.EXECUTED, null, null, null, false);
    }

    private UserView user(UUID id, String email, String displayName) {
        return new UserView(id, email, displayName, UserRoleType.ADMIN, organizationId, true,
                AuthProviderType.LOCAL, null, null, "en", false, Instant.now());
    }
}
