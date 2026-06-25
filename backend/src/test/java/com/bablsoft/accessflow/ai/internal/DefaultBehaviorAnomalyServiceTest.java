package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AnomalyListFilter;
import com.bablsoft.accessflow.ai.api.AnomalyNotFoundException;
import com.bablsoft.accessflow.ai.api.BehaviorAnomalyStatus;
import com.bablsoft.accessflow.ai.api.IllegalAnomalyStatusTransitionException;
import com.bablsoft.accessflow.ai.internal.persistence.entity.BehaviorAnomalyEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.BehaviorAnomalyRepository;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DatasourceRef;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.SortOrder;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultBehaviorAnomalyServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final UUID ORG = UUID.randomUUID();

    private final BehaviorAnomalyRepository repo = mock(BehaviorAnomalyRepository.class);
    private final UserQueryService userQueryService = mock(UserQueryService.class);
    private final DatasourceLookupService datasourceLookupService = mock(DatasourceLookupService.class);
    private final tools.jackson.databind.ObjectMapper objectMapper =
            new tools.jackson.databind.ObjectMapper();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final DefaultBehaviorAnomalyService service = new DefaultBehaviorAnomalyService(
            repo, userQueryService, datasourceLookupService, objectMapper, clock);

    private UUID userId;
    private UUID datasourceId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        datasourceId = UUID.randomUUID();
    }

    private BehaviorAnomalyEntity entity(BehaviorAnomalyStatus status) {
        var e = new BehaviorAnomalyEntity();
        e.setId(UUID.randomUUID());
        e.setOrganizationId(ORG);
        e.setUserId(userId);
        e.setDatasourceId(datasourceId);
        e.setFeature("query_count");
        e.setScore(4.2);
        e.setObservedValue(100.0);
        e.setBaselineMean(10.0);
        e.setBaselineStddev(2.0);
        e.setDetail("{\"method\":\"zscore\"}");
        e.setAiSummary("summary");
        e.setStatus(status);
        e.setDetectedAt(NOW);
        e.setWindowStart(Instant.parse("2026-01-01T11:00:00Z"));
        e.setWindowEnd(NOW);
        return e;
    }

    private UserView userView() {
        return new UserView(userId, "alice@example.com", "Alice", UserRoleType.ANALYST, ORG, true,
                AuthProviderType.LOCAL, "hash", null, "en", false, NOW);
    }

    // ----- list -----

    @Test
    void listMapsPageAndEnrichesView() {
        var e = entity(BehaviorAnomalyStatus.OPEN);
        var page = new PageImpl<>(List.of(e), Pageable.ofSize(20), 1);
        when(repo.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);
        when(userQueryService.findById(userId)).thenReturn(Optional.of(userView()));
        when(datasourceLookupService.findRef(datasourceId))
                .thenReturn(Optional.of(new DatasourceRef(datasourceId, "Prod DB")));

        var result = service.list(ORG, AnomalyListFilter.empty(),
                PageRequest.of(0, 20, SortOrder.desc("detectedAt")));

        assertThat(result.content()).hasSize(1);
        assertThat(result.totalElements()).isEqualTo(1);
        var view = result.content().get(0);
        assertThat(view.userDisplayName()).isEqualTo("Alice");
        assertThat(view.userEmail()).isEqualTo("alice@example.com");
        assertThat(view.datasourceName()).isEqualTo("Prod DB");
        assertThat(view.detail()).containsEntry("method", "zscore");
    }

    @Test
    void listWithNullFilterUsesEmptyFilter() {
        var page = new PageImpl<BehaviorAnomalyEntity>(List.of(), Pageable.ofSize(20), 0);
        when(repo.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        var result = service.list(ORG, null, PageRequest.of(0, 20));

        assertThat(result.content()).isEmpty();
        verify(repo).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void listWithNullPageRequestUsesUnpaged() {
        var e = entity(BehaviorAnomalyStatus.OPEN);
        // An unpaged page reports getSize() from its content; seed one element so size > 0.
        var page = new PageImpl<>(List.of(e), Pageable.unpaged(), 1);
        when(repo.findAll(any(Specification.class), eq(Pageable.unpaged()))).thenReturn(page);
        when(userQueryService.findById(any())).thenReturn(Optional.empty());
        when(datasourceLookupService.findRef(any())).thenReturn(Optional.empty());

        var result = service.list(ORG, AnomalyListFilter.empty(), null);

        assertThat(result.content()).hasSize(1);
        verify(repo).findAll(any(Specification.class), eq(Pageable.unpaged()));
    }

    @Test
    void listRejectsNullOrganization() {
        assertThatThrownBy(() -> service.list(null, AnomalyListFilter.empty(), PageRequest.of(0, 20)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ----- get -----

    @Test
    void getReturnsViewWhenFound() {
        var e = entity(BehaviorAnomalyStatus.OPEN);
        when(repo.findByIdAndOrganizationId(e.getId(), ORG)).thenReturn(Optional.of(e));
        when(userQueryService.findById(userId)).thenReturn(Optional.empty());
        when(datasourceLookupService.findRef(datasourceId)).thenReturn(Optional.empty());

        var view = service.get(ORG, e.getId());

        assertThat(view.id()).isEqualTo(e.getId());
        assertThat(view.userDisplayName()).isNull();
        assertThat(view.datasourceName()).isNull();
    }

    @Test
    void getThrowsWhenMissing() {
        var id = UUID.randomUUID();
        when(repo.findByIdAndOrganizationId(id, ORG)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(ORG, id)).isInstanceOf(AnomalyNotFoundException.class);
    }

    // ----- acknowledge -----

    @Test
    void acknowledgeTransitionsOpenToAcknowledged() {
        var e = entity(BehaviorAnomalyStatus.OPEN);
        var actor = UUID.randomUUID();
        when(repo.findByIdAndOrganizationId(e.getId(), ORG)).thenReturn(Optional.of(e));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userQueryService.findById(any())).thenReturn(Optional.empty());
        when(datasourceLookupService.findRef(any())).thenReturn(Optional.empty());

        var view = service.acknowledge(ORG, e.getId(), actor);

        assertThat(view.status()).isEqualTo(BehaviorAnomalyStatus.ACKNOWLEDGED);
        assertThat(e.getAcknowledgedBy()).isEqualTo(actor);
        assertThat(e.getAcknowledgedAt()).isEqualTo(NOW);
    }

    @Test
    void acknowledgeRejectsNonOpenAnomaly() {
        var e = entity(BehaviorAnomalyStatus.ACKNOWLEDGED);
        when(repo.findByIdAndOrganizationId(e.getId(), ORG)).thenReturn(Optional.of(e));

        assertThatThrownBy(() -> service.acknowledge(ORG, e.getId(), UUID.randomUUID()))
                .isInstanceOf(IllegalAnomalyStatusTransitionException.class);
        verify(repo, never()).save(any());
    }

    @Test
    void acknowledgeThrowsWhenMissing() {
        var id = UUID.randomUUID();
        when(repo.findByIdAndOrganizationId(id, ORG)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.acknowledge(ORG, id, UUID.randomUUID()))
                .isInstanceOf(AnomalyNotFoundException.class);
    }

    // ----- dismiss -----

    @Test
    void dismissTransitionsOpenToDismissed() {
        var e = entity(BehaviorAnomalyStatus.OPEN);
        when(repo.findByIdAndOrganizationId(e.getId(), ORG)).thenReturn(Optional.of(e));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userQueryService.findById(any())).thenReturn(Optional.empty());
        when(datasourceLookupService.findRef(any())).thenReturn(Optional.empty());

        var view = service.dismiss(ORG, e.getId(), UUID.randomUUID());

        assertThat(view.status()).isEqualTo(BehaviorAnomalyStatus.DISMISSED);
    }

    @Test
    void dismissTransitionsAcknowledgedToDismissed() {
        var e = entity(BehaviorAnomalyStatus.ACKNOWLEDGED);
        when(repo.findByIdAndOrganizationId(e.getId(), ORG)).thenReturn(Optional.of(e));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userQueryService.findById(any())).thenReturn(Optional.empty());
        when(datasourceLookupService.findRef(any())).thenReturn(Optional.empty());

        var view = service.dismiss(ORG, e.getId(), UUID.randomUUID());

        assertThat(view.status()).isEqualTo(BehaviorAnomalyStatus.DISMISSED);
    }

    @Test
    void dismissRejectsAlreadyDismissed() {
        var e = entity(BehaviorAnomalyStatus.DISMISSED);
        when(repo.findByIdAndOrganizationId(e.getId(), ORG)).thenReturn(Optional.of(e));

        assertThatThrownBy(() -> service.dismiss(ORG, e.getId(), UUID.randomUUID()))
                .isInstanceOf(IllegalAnomalyStatusTransitionException.class);
        verify(repo, never()).save(any());
    }

    // ----- self-scoped user API (AF-498) -----

    @Test
    void listForUserMapsPage() {
        var e = entity(BehaviorAnomalyStatus.OPEN);
        var page = new PageImpl<>(List.of(e), Pageable.ofSize(20), 1);
        when(repo.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);
        when(userQueryService.findById(userId)).thenReturn(Optional.of(userView()));
        when(datasourceLookupService.findRef(datasourceId))
                .thenReturn(Optional.of(new DatasourceRef(datasourceId, "Prod DB")));

        var result = service.listForUser(ORG, userId, BehaviorAnomalyStatus.OPEN, PageRequest.of(0, 20));

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).userId()).isEqualTo(userId);
    }

    @Test
    void listForUserRejectsNullArgs() {
        assertThatThrownBy(() -> service.listForUser(null, userId, null, PageRequest.of(0, 20)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.listForUser(ORG, null, null, PageRequest.of(0, 20)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acknowledgeOwnTransitionsOpenToAcknowledged() {
        var e = entity(BehaviorAnomalyStatus.OPEN);
        when(repo.findByIdAndOrganizationId(e.getId(), ORG)).thenReturn(Optional.of(e));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userQueryService.findById(any())).thenReturn(Optional.empty());
        when(datasourceLookupService.findRef(any())).thenReturn(Optional.empty());

        var view = service.acknowledgeOwn(ORG, userId, e.getId());

        assertThat(view.status()).isEqualTo(BehaviorAnomalyStatus.ACKNOWLEDGED);
        assertThat(e.getAcknowledgedBy()).isEqualTo(userId);
    }

    @Test
    void acknowledgeOwnRejectsAnotherUsersAnomalyAsNotFound() {
        var e = entity(BehaviorAnomalyStatus.OPEN);
        e.setUserId(UUID.randomUUID()); // belongs to someone else
        when(repo.findByIdAndOrganizationId(e.getId(), ORG)).thenReturn(Optional.of(e));

        assertThatThrownBy(() -> service.acknowledgeOwn(ORG, userId, e.getId()))
                .isInstanceOf(AnomalyNotFoundException.class);
        verify(repo, never()).save(any());
    }

    @Test
    void acknowledgeOwnRejectsNonOpen() {
        var e = entity(BehaviorAnomalyStatus.DISMISSED);
        when(repo.findByIdAndOrganizationId(e.getId(), ORG)).thenReturn(Optional.of(e));

        assertThatThrownBy(() -> service.acknowledgeOwn(ORG, userId, e.getId()))
                .isInstanceOf(IllegalAnomalyStatusTransitionException.class);
    }

    @Test
    void dismissOwnTransitionsToDismissed() {
        var e = entity(BehaviorAnomalyStatus.OPEN);
        when(repo.findByIdAndOrganizationId(e.getId(), ORG)).thenReturn(Optional.of(e));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userQueryService.findById(any())).thenReturn(Optional.empty());
        when(datasourceLookupService.findRef(any())).thenReturn(Optional.empty());

        var view = service.dismissOwn(ORG, userId, e.getId());

        assertThat(view.status()).isEqualTo(BehaviorAnomalyStatus.DISMISSED);
    }

    @Test
    void dismissOwnRejectsAnotherUsersAnomalyAsNotFound() {
        var e = entity(BehaviorAnomalyStatus.OPEN);
        e.setUserId(UUID.randomUUID());
        when(repo.findByIdAndOrganizationId(e.getId(), ORG)).thenReturn(Optional.of(e));

        assertThatThrownBy(() -> service.dismissOwn(ORG, userId, e.getId()))
                .isInstanceOf(AnomalyNotFoundException.class);
    }

    @Test
    void dismissOwnRejectsAlreadyDismissed() {
        var e = entity(BehaviorAnomalyStatus.DISMISSED);
        when(repo.findByIdAndOrganizationId(e.getId(), ORG)).thenReturn(Optional.of(e));

        assertThatThrownBy(() -> service.dismissOwn(ORG, userId, e.getId()))
                .isInstanceOf(IllegalAnomalyStatusTransitionException.class);
    }

    // ----- hasActiveAnomaly -----

    @Test
    void hasActiveAnomalyDelegatesToRepository() {
        when(repo.existsByOrganizationIdAndUserIdAndDatasourceIdAndStatus(
                ORG, userId, datasourceId, BehaviorAnomalyStatus.OPEN)).thenReturn(true);
        assertThat(service.hasActiveAnomaly(ORG, userId, datasourceId)).isTrue();
    }

    @Test
    void hasActiveAnomalyFalseForNullArgs() {
        assertThat(service.hasActiveAnomaly(null, userId, datasourceId)).isFalse();
        assertThat(service.hasActiveAnomaly(ORG, null, datasourceId)).isFalse();
        assertThat(service.hasActiveAnomaly(ORG, userId, null)).isFalse();
        verify(repo, never()).existsByOrganizationIdAndUserIdAndDatasourceIdAndStatus(
                any(), any(), any(), any());
    }

    // ----- badges -----

    @Test
    void badgeForUserReturnsNoneWhenNoOpenAnomalies() {
        when(repo.findByOrganizationIdAndUserIdAndStatus(ORG, userId, BehaviorAnomalyStatus.OPEN))
                .thenReturn(List.of());
        var badge = service.badgeForUser(ORG, userId);
        assertThat(badge.openCount()).isZero();
        assertThat(badge.maxScore()).isZero();
    }

    @Test
    void badgeForUserReturnsCountAndMaxScore() {
        var low = entity(BehaviorAnomalyStatus.OPEN);
        low.setScore(3.0);
        var high = entity(BehaviorAnomalyStatus.OPEN);
        high.setScore(8.5);
        when(repo.findByOrganizationIdAndUserIdAndStatus(ORG, userId, BehaviorAnomalyStatus.OPEN))
                .thenReturn(List.of(low, high));

        var badge = service.badgeForUser(ORG, userId);

        assertThat(badge.openCount()).isEqualTo(2);
        assertThat(badge.maxScore()).isEqualTo(8.5);
    }

    @Test
    void badgeForUserDatasourceReturnsMaxScore() {
        var a = entity(BehaviorAnomalyStatus.OPEN);
        a.setScore(5.0);
        when(repo.findByOrganizationIdAndUserIdAndDatasourceIdAndStatus(
                ORG, userId, datasourceId, BehaviorAnomalyStatus.OPEN)).thenReturn(List.of(a));

        var badge = service.badgeForUserDatasource(ORG, userId, datasourceId);

        assertThat(badge.openCount()).isEqualTo(1);
        assertThat(badge.maxScore()).isEqualTo(5.0);
    }

    // ----- findById -----

    @Test
    void findByIdReturnsViewWhenPresent() {
        var e = entity(BehaviorAnomalyStatus.OPEN);
        when(repo.findByIdAndOrganizationId(e.getId(), ORG)).thenReturn(Optional.of(e));
        when(userQueryService.findById(any())).thenReturn(Optional.empty());
        when(datasourceLookupService.findRef(any())).thenReturn(Optional.empty());

        assertThat(service.findById(ORG, e.getId())).isPresent();
    }

    @Test
    void findByIdEmptyWhenMissing() {
        var id = UUID.randomUUID();
        when(repo.findByIdAndOrganizationId(id, ORG)).thenReturn(Optional.empty());
        assertThat(service.findById(ORG, id)).isEmpty();
    }

    // ----- existsForWindow -----

    @Test
    void existsForWindowDelegatesToRepository() {
        var ws = Instant.parse("2026-01-01T11:00:00Z");
        when(repo.existsByOrganizationIdAndUserIdAndDatasourceIdAndFeatureAndWindowStart(
                ORG, userId, datasourceId, "query_count", ws)).thenReturn(true);
        assertThat(service.existsForWindow(ORG, userId, datasourceId, "query_count", ws)).isTrue();
    }

    // ----- persist -----

    @Test
    void persistReturnsSavedEntity() {
        var anomaly = new DetectedAnomaly("query_count", 4.2, 100.0, 10.0, 2.0,
                Map.of("method", "zscore"));
        var ws = Instant.parse("2026-01-01T11:00:00Z");
        when(repo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        var saved = service.persist(ORG, userId, datasourceId, anomaly, ws, NOW, "ai");

        assertThat(saved).isPresent();
        var e = saved.get();
        assertThat(e.getFeature()).isEqualTo("query_count");
        assertThat(e.getScore()).isEqualTo(4.2);
        assertThat(e.getObservedValue()).isEqualTo(100.0);
        assertThat(e.getStatus()).isEqualTo(BehaviorAnomalyStatus.OPEN);
        assertThat(e.getAiSummary()).isEqualTo("ai");
        assertThat(e.getDetectedAt()).isEqualTo(NOW);
        assertThat(e.getWindowStart()).isEqualTo(ws);
        assertThat(e.getDetail()).contains("zscore");
    }

    @Test
    void persistReturnsEmptyOnDataIntegrityViolation() {
        var anomaly = new DetectedAnomaly("query_count", 4.2, 100.0, 10.0, 2.0, Map.of());
        when(repo.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("dup"));

        var saved = service.persist(ORG, userId, datasourceId, anomaly,
                Instant.parse("2026-01-01T11:00:00Z"), NOW, null);

        assertThat(saved).isEmpty();
    }

    // ----- parseDetail via toView -----

    @Test
    void toViewParsesGarbageDetailToEmptyMap() {
        var e = entity(BehaviorAnomalyStatus.OPEN);
        e.setDetail("{ broken json ]");
        when(repo.findByIdAndOrganizationId(e.getId(), ORG)).thenReturn(Optional.of(e));
        when(userQueryService.findById(any())).thenReturn(Optional.empty());
        when(datasourceLookupService.findRef(any())).thenReturn(Optional.empty());

        assertThat(service.get(ORG, e.getId()).detail()).isEmpty();
    }

    @Test
    void toViewParsesBlankDetailToEmptyMap() {
        var e = entity(BehaviorAnomalyStatus.OPEN);
        e.setDetail("   ");
        when(repo.findByIdAndOrganizationId(e.getId(), ORG)).thenReturn(Optional.of(e));
        when(userQueryService.findById(any())).thenReturn(Optional.empty());
        when(datasourceLookupService.findRef(any())).thenReturn(Optional.empty());

        assertThat(service.get(ORG, e.getId()).detail()).isEmpty();
    }
}
