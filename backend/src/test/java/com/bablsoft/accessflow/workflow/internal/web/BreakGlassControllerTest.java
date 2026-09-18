package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.serviceaccounts.api.OnBehalfOfPrincipalService;
import com.bablsoft.accessflow.workflow.api.BreakGlassService;
import com.bablsoft.accessflow.workflow.api.BreakGlassService.BreakGlassInput;
import com.bablsoft.accessflow.workflow.api.BreakGlassService.BreakGlassResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BreakGlassControllerTest {

    private final BreakGlassService breakGlassService = mock(BreakGlassService.class);
    private final OnBehalfOfPrincipalService onBehalfOfPrincipalService = mock(OnBehalfOfPrincipalService.class);
    private final BreakGlassController controller = new BreakGlassController(breakGlassService,
            onBehalfOfPrincipalService);

    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final RequestAuditContext auditContext = new RequestAuditContext("203.0.113.5", "ua/1");

    private Authentication auth(UserRoleType role) {
        return new UsernamePasswordAuthenticationToken(
                JwtClaims.forSystemRole(userId, "u@x.com", role, organizationId), "n/a", List.of());
    }

    private BreakGlassResult result() {
        return new BreakGlassResult(UUID.randomUUID(), UUID.randomUUID(), QueryStatus.EXECUTED, 3L, 12);
    }

    @Test
    void breakGlassPassesTheCallerContextAndMapsTheResult() {
        when(onBehalfOfPrincipalService.current()).thenReturn(Optional.empty());
        var result = result();
        when(breakGlassService.breakGlassExecute(any())).thenReturn(result);

        var response = controller.breakGlass(new BreakGlassSubmitRequest(datasourceId, "DELETE FROM t", "outage"),
                auth(UserRoleType.ADMIN), auditContext);

        assertThat(response.id()).isEqualTo(result.queryRequestId());
        assertThat(response.eventId()).isEqualTo(result.eventId());
        assertThat(response.rowsAffected()).isEqualTo(3L);
        var captor = ArgumentCaptor.forClass(BreakGlassInput.class);
        verify(breakGlassService).breakGlassExecute(captor.capture());
        assertThat(captor.getValue().datasourceId()).isEqualTo(datasourceId);
        assertThat(captor.getValue().submitterUserId()).isEqualTo(userId);
        assertThat(captor.getValue().organizationId()).isEqualTo(organizationId);
        assertThat(captor.getValue().isAdmin()).isTrue();
        assertThat(captor.getValue().submittedIp()).isEqualTo("203.0.113.5");
        assertThat(captor.getValue().onBehalfOfUserId()).isNull();
    }

    /** #874: an emergency run for a human is attributed to that human. */
    @Test
    void breakGlassStampsTheOnBehalfOfPrincipal() {
        var alice = UUID.randomUUID();
        when(onBehalfOfPrincipalService.current()).thenReturn(Optional.of(alice));
        when(breakGlassService.breakGlassExecute(any())).thenReturn(result());

        controller.breakGlass(new BreakGlassSubmitRequest(datasourceId, "DELETE FROM t", "outage"),
                auth(UserRoleType.ANALYST), auditContext);

        var captor = ArgumentCaptor.forClass(BreakGlassInput.class);
        verify(breakGlassService).breakGlassExecute(captor.capture());
        assertThat(captor.getValue().onBehalfOfUserId()).isEqualTo(alice);
        assertThat(captor.getValue().isAdmin()).isFalse();
    }
}
