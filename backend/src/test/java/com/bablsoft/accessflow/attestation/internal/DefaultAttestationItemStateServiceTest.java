package com.bablsoft.accessflow.attestation.internal;

import com.bablsoft.accessflow.attestation.api.AttestationItemCloseReason;
import com.bablsoft.accessflow.attestation.api.AttestationItemDecision;
import com.bablsoft.accessflow.attestation.api.AttestationItemNotFoundException;
import com.bablsoft.accessflow.attestation.internal.persistence.entity.AttestationItemEntity;
import com.bablsoft.accessflow.attestation.internal.persistence.repo.AttestationItemRepository;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourcePermissionNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAttestationItemStateServiceTest {

    @Mock AttestationItemRepository itemRepository;
    @Mock DatasourceAdminService datasourceAdminService;
    @InjectMocks DefaultAttestationItemStateService service;

    private final UUID itemId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID permissionId = UUID.randomUUID();
    private final UUID reviewerId = UUID.randomUUID();

    private AttestationItemEntity pendingItem() {
        var item = new AttestationItemEntity();
        item.setId(itemId);
        item.setOrganizationId(orgId);
        item.setDatasourceId(datasourceId);
        item.setPermissionId(permissionId);
        item.setDecision(AttestationItemDecision.PENDING);
        return item;
    }

    @Test
    void certifyMarksItemCertified() {
        var item = pendingItem();
        when(itemRepository.findByIdForUpdate(itemId)).thenReturn(Optional.of(item));

        var outcome = service.certify(itemId, reviewerId, "ok",
                AttestationItemCloseReason.REVIEWER);

        assertThat(outcome.decision()).isEqualTo(AttestationItemDecision.CERTIFIED);
        assertThat(outcome.wasIdempotentReplay()).isFalse();
        assertThat(item.getDecision()).isEqualTo(AttestationItemDecision.CERTIFIED);
        assertThat(item.getDecidedBy()).isEqualTo(reviewerId);
        assertThat(item.getCloseReason()).isEqualTo(AttestationItemCloseReason.REVIEWER);
        verify(itemRepository).save(item);
    }

    @Test
    void certifyOnTerminalItemReplaysWithoutMutating() {
        var item = pendingItem();
        item.setDecision(AttestationItemDecision.REVOKED);
        when(itemRepository.findByIdForUpdate(itemId)).thenReturn(Optional.of(item));

        var outcome = service.certify(itemId, reviewerId, "ok",
                AttestationItemCloseReason.REVIEWER);

        assertThat(outcome.wasIdempotentReplay()).isTrue();
        assertThat(outcome.decision()).isEqualTo(AttestationItemDecision.REVOKED);
        verify(itemRepository, never()).save(any());
    }

    @Test
    void revokeRevokesPermissionAndMarksItemRevoked() {
        var item = pendingItem();
        when(itemRepository.findByIdForUpdate(itemId)).thenReturn(Optional.of(item));

        var outcome = service.revoke(itemId, reviewerId, "gone",
                AttestationItemCloseReason.AUTO_DEFAULT_REVOKE);

        assertThat(outcome.decision()).isEqualTo(AttestationItemDecision.REVOKED);
        verify(datasourceAdminService).revokePermission(datasourceId, orgId, permissionId);
        assertThat(item.getDecision()).isEqualTo(AttestationItemDecision.REVOKED);
        assertThat(item.getCloseReason()).isEqualTo(AttestationItemCloseReason.AUTO_DEFAULT_REVOKE);
    }

    @Test
    void revokeToleratesAlreadyAbsentPermission() {
        var item = pendingItem();
        when(itemRepository.findByIdForUpdate(itemId)).thenReturn(Optional.of(item));
        org.mockito.Mockito.doThrow(new DatasourcePermissionNotFoundException(permissionId))
                .when(datasourceAdminService).revokePermission(datasourceId, orgId, permissionId);

        var outcome = service.revoke(itemId, reviewerId, "gone",
                AttestationItemCloseReason.REVIEWER);

        assertThat(outcome.decision()).isEqualTo(AttestationItemDecision.REVOKED);
        assertThat(item.getDecision()).isEqualTo(AttestationItemDecision.REVOKED);
        verify(itemRepository).save(item);
    }

    @Test
    void missingItemThrows() {
        when(itemRepository.findByIdForUpdate(itemId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.certify(itemId, reviewerId, null,
                AttestationItemCloseReason.REVIEWER))
                .isInstanceOf(AttestationItemNotFoundException.class);
    }
}
