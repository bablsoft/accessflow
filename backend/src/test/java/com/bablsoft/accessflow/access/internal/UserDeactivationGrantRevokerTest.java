package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.access.api.AccessGrantStatus;
import com.bablsoft.accessflow.access.internal.persistence.repo.AccessGrantRequestRepository;
import com.bablsoft.accessflow.core.events.UserDeactivatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserDeactivationGrantRevokerTest {

    @Mock AccessGrantRequestRepository requestRepository;
    @Mock AccessGrantRequestStateService stateService;

    UserDeactivationGrantRevoker revoker;

    private final UUID userId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        revoker = new UserDeactivationGrantRevoker(requestRepository, stateService);
    }

    @Test
    void revokesEveryApprovedGrantSystemAttributed() {
        var grantA = UUID.randomUUID();
        var grantB = UUID.randomUUID();
        when(requestRepository.findIdsByRequesterIdAndStatus(userId, AccessGrantStatus.APPROVED))
                .thenReturn(List.of(grantA, grantB));

        revoker.onUserDeactivated(new UserDeactivatedEvent(userId, orgId));

        verify(stateService).revoke(eq(grantA), isNull());
        verify(stateService).revoke(eq(grantB), isNull());
    }

    @Test
    void noGrantsMeansNoRevocations() {
        when(requestRepository.findIdsByRequesterIdAndStatus(userId, AccessGrantStatus.APPROVED))
                .thenReturn(List.of());

        revoker.onUserDeactivated(new UserDeactivatedEvent(userId, orgId));

        verify(stateService, never()).revoke(any(), any());
    }

    @Test
    void perGrantFailureDoesNotStopTheRest() {
        var failing = UUID.randomUUID();
        var succeeding = UUID.randomUUID();
        when(requestRepository.findIdsByRequesterIdAndStatus(userId, AccessGrantStatus.APPROVED))
                .thenReturn(List.of(failing, succeeding));
        doThrow(new IllegalStateException("boom")).when(stateService).revoke(eq(failing), isNull());

        revoker.onUserDeactivated(new UserDeactivatedEvent(userId, orgId));

        verify(stateService).revoke(eq(succeeding), isNull());
    }
}
