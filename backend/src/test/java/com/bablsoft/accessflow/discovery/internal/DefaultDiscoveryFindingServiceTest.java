package com.bablsoft.accessflow.discovery.internal;

import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.discovery.api.DiscoveryDecision;
import com.bablsoft.accessflow.discovery.api.DiscoveryDetector;
import com.bablsoft.accessflow.discovery.api.DiscoveryFindingStatus;
import com.bablsoft.accessflow.discovery.api.DiscoveryRowStatus;
import com.bablsoft.accessflow.discovery.internal.DiscoveryFindingStateService.ConfirmOutcome;
import com.bablsoft.accessflow.discovery.internal.persistence.entity.DiscoveryFindingEntity;
import com.bablsoft.accessflow.discovery.internal.persistence.repo.DiscoveryFindingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultDiscoveryFindingServiceTest {

    @Mock
    private DiscoveryFindingRepository findingRepository;
    @Mock
    private DiscoveryFindingStateService stateService;

    private final UUID dsId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    private DefaultDiscoveryFindingService service() {
        return new DefaultDiscoveryFindingService(findingRepository, stateService);
    }

    private DiscoveryFindingEntity finding(DiscoveryFindingStatus status) {
        var finding = new DiscoveryFindingEntity();
        finding.setId(UUID.randomUUID());
        finding.setOrganizationId(orgId);
        finding.setDatasourceId(dsId);
        finding.setSchemaName("public");
        finding.setTableName("users");
        finding.setColumnName("email");
        finding.setClassification(DataClassification.PII);
        finding.setDetector(DiscoveryDetector.EMAIL);
        finding.setConfidence(90);
        finding.setStatus(status);
        finding.setFirstDetectedAt(Instant.EPOCH);
        finding.setLastDetectedAt(Instant.EPOCH);
        return finding;
    }

    @Test
    void findWithoutStatusPagesAllAndDefaultsSortToLastDetectedDesc() {
        when(findingRepository.findAllByDatasourceIdAndOrganizationId(eq(dsId), eq(orgId),
                any(Pageable.class))).thenReturn(new PageImpl<>(List.of(finding(
                        DiscoveryFindingStatus.PENDING))));

        var page = service().find(dsId, orgId, null, PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(1);
        var pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(findingRepository).findAllByDatasourceIdAndOrganizationId(eq(dsId), eq(orgId),
                pageable.capture());
        assertThat(pageable.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Direction.DESC, "lastDetectedAt"));
    }

    @Test
    void findWithStatusUsesStatusQuery() {
        when(findingRepository.findAllByDatasourceIdAndOrganizationIdAndStatus(eq(dsId), eq(orgId),
                eq(DiscoveryFindingStatus.PENDING), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(),
                        org.springframework.data.domain.PageRequest.of(0, 20), 0));

        var page = service().find(dsId, orgId, DiscoveryFindingStatus.PENDING,
                PageRequest.of(0, 20));

        assertThat(page.content()).isEmpty();
    }

    @Test
    void decideReportsNotFoundForUnknownId() {
        var unknownId = UUID.randomUUID();
        when(findingRepository.findByIdAndDatasourceIdAndOrganizationId(unknownId, dsId, orgId))
                .thenReturn(Optional.empty());

        var outcome = service().decide(dsId, orgId, actorId, List.of(unknownId),
                DiscoveryDecision.CONFIRM);

        assertThat(outcome.results()).hasSize(1);
        var row = outcome.results().getFirst();
        assertThat(row.status()).isEqualTo(DiscoveryRowStatus.NOT_FOUND);
        assertThat(row.newStatus()).isNull();
        assertThat(row.finding()).isNull();
    }

    @Test
    void decideReportsInvalidStateForAlreadyDecidedFinding() {
        var decided = finding(DiscoveryFindingStatus.DISMISSED);
        when(findingRepository.findByIdAndDatasourceIdAndOrganizationId(decided.getId(), dsId,
                orgId)).thenReturn(Optional.of(decided));

        var outcome = service().decide(dsId, orgId, actorId, List.of(decided.getId()),
                DiscoveryDecision.CONFIRM);

        var row = outcome.results().getFirst();
        assertThat(row.status()).isEqualTo(DiscoveryRowStatus.INVALID_STATE);
        assertThat(row.newStatus()).isEqualTo(DiscoveryFindingStatus.DISMISSED);
        verify(stateService, never()).confirm(any(), any());
    }

    @Test
    void confirmSuccessAndTagConflictAreReportedPerRow() {
        var success = finding(DiscoveryFindingStatus.PENDING);
        var conflicting = finding(DiscoveryFindingStatus.PENDING);
        when(findingRepository.findByIdAndDatasourceIdAndOrganizationId(success.getId(), dsId,
                orgId)).thenReturn(Optional.of(success));
        when(findingRepository.findByIdAndDatasourceIdAndOrganizationId(conflicting.getId(), dsId,
                orgId)).thenReturn(Optional.of(conflicting));
        when(stateService.confirm(success, actorId))
                .thenAnswer(inv -> confirmed(success, false));
        when(stateService.confirm(conflicting, actorId))
                .thenAnswer(inv -> confirmed(conflicting, true));

        var outcome = service().decide(dsId, orgId, actorId,
                List.of(success.getId(), conflicting.getId()), DiscoveryDecision.CONFIRM);

        assertThat(outcome.results()).extracting(r -> r.status()).containsExactly(
                DiscoveryRowStatus.SUCCESS, DiscoveryRowStatus.TAG_CONFLICT);
        assertThat(outcome.results()).extracting(r -> r.newStatus()).containsOnly(
                DiscoveryFindingStatus.CONFIRMED);
    }

    @Test
    void dismissDelegatesToStateService() {
        var pending = finding(DiscoveryFindingStatus.PENDING);
        when(findingRepository.findByIdAndDatasourceIdAndOrganizationId(pending.getId(), dsId,
                orgId)).thenReturn(Optional.of(pending));
        when(stateService.dismiss(pending, actorId)).thenAnswer(inv -> {
            pending.setStatus(DiscoveryFindingStatus.DISMISSED);
            return pending;
        });

        var outcome = service().decide(dsId, orgId, actorId, List.of(pending.getId()),
                DiscoveryDecision.DISMISS);

        var row = outcome.results().getFirst();
        assertThat(row.status()).isEqualTo(DiscoveryRowStatus.SUCCESS);
        assertThat(row.newStatus()).isEqualTo(DiscoveryFindingStatus.DISMISSED);
    }

    @Test
    void unexpectedErrorYieldsErrorRowAndContinues() {
        var failing = finding(DiscoveryFindingStatus.PENDING);
        var ok = finding(DiscoveryFindingStatus.PENDING);
        when(findingRepository.findByIdAndDatasourceIdAndOrganizationId(failing.getId(), dsId,
                orgId)).thenReturn(Optional.of(failing));
        when(findingRepository.findByIdAndDatasourceIdAndOrganizationId(ok.getId(), dsId, orgId))
                .thenReturn(Optional.of(ok));
        when(stateService.confirm(failing, actorId)).thenThrow(new IllegalStateException("boom"));
        when(stateService.confirm(ok, actorId)).thenAnswer(inv -> confirmed(ok, false));

        var outcome = service().decide(dsId, orgId, actorId,
                List.of(failing.getId(), ok.getId()), DiscoveryDecision.CONFIRM);

        assertThat(outcome.results()).extracting(r -> r.status()).containsExactly(
                DiscoveryRowStatus.ERROR, DiscoveryRowStatus.SUCCESS);
        assertThat(outcome.results().getFirst().newStatus())
                .isEqualTo(DiscoveryFindingStatus.PENDING);
    }

    private static ConfirmOutcome confirmed(DiscoveryFindingEntity finding, boolean conflict) {
        finding.setStatus(DiscoveryFindingStatus.CONFIRMED);
        return new ConfirmOutcome(finding, conflict);
    }
}
