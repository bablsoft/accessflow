package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.access.api.AccessGrantStatus;
import com.bablsoft.accessflow.access.internal.persistence.entity.AccessGrantDecisionEntity;
import com.bablsoft.accessflow.access.internal.persistence.entity.AccessGrantRequestEntity;
import com.bablsoft.accessflow.access.internal.persistence.repo.AccessGrantDecisionRepository;
import com.bablsoft.accessflow.access.internal.persistence.repo.AccessGrantRequestRepository;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAccessGrantLookupServiceTest {

    @Mock AccessGrantRequestRepository requestRepository;
    @Mock AccessGrantDecisionRepository decisionRepository;
    @Mock UserQueryService userQueryService;

    private final Instant now = Instant.parse("2026-07-03T10:00:00Z");
    private DefaultAccessGrantLookupService service;

    private final UUID organizationId = UUID.randomUUID();
    private final UUID requesterId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID grantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultAccessGrantLookupService(requestRepository, decisionRepository,
                userQueryService, Clock.fixed(now, ZoneOffset.UTC));
    }

    private AccessGrantRequestEntity grant() {
        var entity = new AccessGrantRequestEntity();
        entity.setId(grantId);
        entity.setOrganizationId(organizationId);
        entity.setRequesterId(requesterId);
        entity.setDatasourceId(datasourceId);
        entity.setCanRead(true);
        entity.setCanWrite(false);
        entity.setCanDdl(false);
        entity.setAllowedSchemas(new String[]{"public"});
        entity.setAllowedTables(new String[]{"orders"});
        entity.setPreApproveQueries(true);
        entity.setStatus(AccessGrantStatus.APPROVED);
        entity.setExpiresAt(now.plusSeconds(3600));
        return entity;
    }

    private UserView userView(UUID id, String email, UserRoleType role) {
        return new UserView(id, email, "User", role, organizationId, true,
                AuthProviderType.LOCAL, null, null, "en", false, Instant.now());
    }

    private AccessGrantDecisionEntity decision(DecisionType type, UUID reviewerId, Instant at) {
        var entity = new AccessGrantDecisionEntity();
        entity.setId(UUID.randomUUID());
        entity.setReviewerId(reviewerId);
        entity.setDecision(type);
        entity.setStage(1);
        entity.setDecidedAt(at);
        return entity;
    }

    @Test
    void findActivePreApprovedGrantsMapsScopeAndProvenance() {
        var reviewerId = UUID.randomUUID();
        when(requestRepository
                .findAllByOrganizationIdAndRequesterIdAndDatasourceIdAndStatusAndPreApproveQueriesTrueAndExpiresAtAfter(
                        organizationId, requesterId, datasourceId, AccessGrantStatus.APPROVED, now))
                .thenReturn(List.of(grant()));
        when(decisionRepository.findAllByAccessGrantRequest_IdOrderByDecidedAtAsc(grantId))
                .thenReturn(List.of(decision(DecisionType.APPROVED, reviewerId, now.minusSeconds(60))));
        when(userQueryService.findById(reviewerId)).thenReturn(Optional.of(
                userView(reviewerId, "rev@x.io", UserRoleType.REVIEWER)));

        var views = service.findActivePreApprovedGrants(organizationId, requesterId, datasourceId);

        assertThat(views).hasSize(1);
        var view = views.get(0);
        assertThat(view.id()).isEqualTo(grantId);
        assertThat(view.canRead()).isTrue();
        assertThat(view.canWrite()).isFalse();
        assertThat(view.allowedSchemas()).containsExactly("public");
        assertThat(view.allowedTables()).containsExactly("orders");
        assertThat(view.status()).isEqualTo(AccessGrantStatus.APPROVED);
        assertThat(view.approverId()).isEqualTo(reviewerId);
        assertThat(view.approverEmail()).isEqualTo("rev@x.io");
        assertThat(view.approvedAt()).isEqualTo(now.minusSeconds(60));
        verify(requestRepository)
                .findAllByOrganizationIdAndRequesterIdAndDatasourceIdAndStatusAndPreApproveQueriesTrueAndExpiresAtAfter(
                        organizationId, requesterId, datasourceId, AccessGrantStatus.APPROVED, now);
    }

    @Test
    void findActivePreApprovedGrantsEmptyWhenNoneMatch() {
        when(requestRepository
                .findAllByOrganizationIdAndRequesterIdAndDatasourceIdAndStatusAndPreApproveQueriesTrueAndExpiresAtAfter(
                        organizationId, requesterId, datasourceId, AccessGrantStatus.APPROVED, now))
                .thenReturn(List.of());

        assertThat(service.findActivePreApprovedGrants(organizationId, requesterId, datasourceId))
                .isEmpty();
    }

    @Test
    void multiStageProvenancePicksTheLastApprovedDecision() {
        var stageOne = UUID.randomUUID();
        var stageTwo = UUID.randomUUID();
        when(requestRepository.findById(grantId)).thenReturn(Optional.of(grant()));
        when(decisionRepository.findAllByAccessGrantRequest_IdOrderByDecidedAtAsc(grantId))
                .thenReturn(List.of(
                        decision(DecisionType.APPROVED, stageOne, now.minusSeconds(120)),
                        decision(DecisionType.APPROVED, stageTwo, now.minusSeconds(30))));
        when(userQueryService.findById(stageTwo)).thenReturn(Optional.of(
                userView(stageTwo, "final@x.io", UserRoleType.ADMIN)));

        var view = service.findGrant(grantId).orElseThrow();

        assertThat(view.approverId()).isEqualTo(stageTwo);
        assertThat(view.approverEmail()).isEqualTo("final@x.io");
        assertThat(view.approvedAt()).isEqualTo(now.minusSeconds(30));
    }

    @Test
    void provenanceIgnoresRejectedDecisions() {
        var approver = UUID.randomUUID();
        when(requestRepository.findById(grantId)).thenReturn(Optional.of(grant()));
        when(decisionRepository.findAllByAccessGrantRequest_IdOrderByDecidedAtAsc(grantId))
                .thenReturn(List.of(
                        decision(DecisionType.APPROVED, approver, now.minusSeconds(90)),
                        decision(DecisionType.REJECTED, UUID.randomUUID(), now.minusSeconds(10))));
        when(userQueryService.findById(approver)).thenReturn(Optional.of(
                userView(approver, "rev@x.io", UserRoleType.REVIEWER)));

        var view = service.findGrant(grantId).orElseThrow();

        assertThat(view.approverId()).isEqualTo(approver);
    }

    @Test
    void provenanceIsNullWithoutApprovedDecisions() {
        when(requestRepository.findById(grantId)).thenReturn(Optional.of(grant()));
        when(decisionRepository.findAllByAccessGrantRequest_IdOrderByDecidedAtAsc(grantId))
                .thenReturn(List.of());

        var view = service.findGrant(grantId).orElseThrow();

        assertThat(view.approverId()).isNull();
        assertThat(view.approverEmail()).isNull();
        assertThat(view.approvedAt()).isNull();
    }

    @Test
    void approverEmailIsNullWhenUserDeleted() {
        var reviewerId = UUID.randomUUID();
        when(requestRepository.findById(grantId)).thenReturn(Optional.of(grant()));
        when(decisionRepository.findAllByAccessGrantRequest_IdOrderByDecidedAtAsc(grantId))
                .thenReturn(List.of(decision(DecisionType.APPROVED, reviewerId, now)));
        when(userQueryService.findById(reviewerId)).thenReturn(Optional.empty());

        var view = service.findGrant(grantId).orElseThrow();

        assertThat(view.approverId()).isEqualTo(reviewerId);
        assertThat(view.approverEmail()).isNull();
    }

    @Test
    void findGrantEmptyWhenUnknown() {
        when(requestRepository.findById(grantId)).thenReturn(Optional.empty());

        assertThat(service.findGrant(grantId)).isEmpty();
    }
}
