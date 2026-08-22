package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.deploygov.api.DeploymentFreezeWindowService;
import com.bablsoft.accessflow.deploygov.api.DeploymentFreezeWindowView;
import com.bablsoft.accessflow.deploygov.api.FreezeBehavior;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeploymentFreezeWindowControllerTest {

    private final UUID orgId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final UUID windowId = UUID.randomUUID();

    private DeploymentFreezeWindowService service;
    private DeploymentFreezeWindowController controller;

    @BeforeEach
    void setUp() {
        service = mock(DeploymentFreezeWindowService.class);
        controller = new DeploymentFreezeWindowController(service);
    }

    @Test
    void listAdaptsPageableAndMapsPage() {
        when(service.list(eq(orgId), any()))
                .thenReturn(new PageResponse<>(List.of(recurringView()), 0, 20, 1, 1));

        var response = controller.list(auth(), Pageable.ofSize(20));

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).daysOfWeek()).containsExactly(5, 6);
    }

    @Test
    void getMapsView() {
        when(service.get(windowId, orgId)).thenReturn(recurringView());

        var response = controller.get(windowId, auth());

        assertThat(response.id()).isEqualTo(windowId);
        assertThat(response.behavior()).isEqualTo(FreezeBehavior.HOLD);
    }

    @Test
    void createDelegatesWithCallersOrganization() {
        when(service.create(any())).thenReturn(recurringView());

        var body = new DeploymentFreezeWindowRequest(null, null, null, null, List.of(5, 6),
                LocalTime.of(18, 0), LocalTime.of(22, 0), "Europe/Berlin", FreezeBehavior.HOLD,
                "weekend freeze", null);
        var response = controller.create(body, auth());

        assertThat(response.timezone()).isEqualTo("Europe/Berlin");
        verify(service).create(org.mockito.ArgumentMatchers.argThat(
                c -> orgId.equals(c.organizationId()) && "weekend freeze".equals(c.reason())));
    }

    @Test
    void updateDelegatesFullReplacement() {
        when(service.update(eq(windowId), any())).thenReturn(recurringView());

        var body = new DeploymentFreezeWindowRequest(null, null,
                Instant.parse("2026-12-24T00:00:00Z"), Instant.parse("2027-01-02T00:00:00Z"),
                null, null, null, null, FreezeBehavior.REJECT, null, false);
        controller.update(windowId, body, auth());

        verify(service).update(eq(windowId), org.mockito.ArgumentMatchers.argThat(
                c -> orgId.equals(c.organizationId())
                        && c.behavior() == FreezeBehavior.REJECT
                        && Boolean.FALSE.equals(c.enabled())));
    }

    @Test
    void deleteDelegates() {
        controller.delete(windowId, auth());

        verify(service).delete(windowId, orgId);
    }

    private Authentication auth() {
        var authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(
                JwtClaims.forSystemRole(adminId, "admin@acme.test", UserRoleType.ADMIN, orgId));
        return authentication;
    }

    private DeploymentFreezeWindowView recurringView() {
        return new DeploymentFreezeWindowView(windowId, orgId, null, null, null, null,
                List.of(5, 6), LocalTime.of(18, 0), LocalTime.of(22, 0), "Europe/Berlin",
                FreezeBehavior.HOLD, "weekend freeze", true, Instant.now());
    }
}
