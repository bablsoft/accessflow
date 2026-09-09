package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.workflow.api.ConditionNode;
import com.bablsoft.accessflow.workflow.api.CreateRoutingPolicyCommand;
import com.bablsoft.accessflow.workflow.api.RoutingAction;
import com.bablsoft.accessflow.workflow.api.RoutingPolicyService;
import com.bablsoft.accessflow.workflow.api.RoutingPolicyView;
import com.bablsoft.accessflow.workflow.api.UpdateRoutingPolicyCommand;
import com.bablsoft.accessflow.workflow.internal.routing.RoutingConditionCodec;
import com.bablsoft.accessflow.workflow.internal.web.model.CreateRoutingPolicyRequest;
import com.bablsoft.accessflow.workflow.internal.web.model.ReorderRoutingPoliciesRequest;
import com.bablsoft.accessflow.workflow.internal.web.model.UpdateRoutingPolicyRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminRoutingPolicyControllerTest {

    private RoutingPolicyService service;
    private AuditLogService auditLogService;
    private AdminRoutingPolicyController controller;
    private final RoutingConditionCodec codec = new RoutingConditionCodec(
            JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build(),
            new StaticMessageSource());

    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID policyId = UUID.randomUUID();
    private final RequestAuditContext auditContext = new RequestAuditContext("203.0.113.5", "ua/1");
    private final Authentication authentication = new UsernamePasswordAuthenticationToken(
            JwtClaims.forSystemRole(userId, "admin@x.com", UserRoleType.ADMIN, organizationId), "n/a",
            List.of());
    private final ConditionNode condition = new ConditionNode.QueryTypeIn(Set.of(QueryType.DELETE));
    private com.bablsoft.accessflow.workflow.api.RoutingPolicySimulationService simulationService;

    @BeforeEach
    void setUp() {
        service = mock(RoutingPolicyService.class);
        simulationService = mock(com.bablsoft.accessflow.workflow.api.RoutingPolicySimulationService.class);
        auditLogService = mock(AuditLogService.class);
        controller = new AdminRoutingPolicyController(service, simulationService, codec,
                auditLogService);
        var request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/admin/routing-policies");
        request.setServerName("localhost");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private RoutingPolicyView view(String name) {
        return new RoutingPolicyView(policyId, organizationId, null, name, null, 1, true, condition,
                RoutingAction.AUTO_REJECT, null, "blocked", 0L, Instant.now(), Instant.now());
    }

    @Test
    void listMapsConditionToJson() {
        when(service.list(organizationId)).thenReturn(List.of(view("Block")));

        var result = controller.list(authentication);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("Block");
        assertThat(result.get(0).condition().get("type").asString()).isEqualTo("query_type");
    }

    @Test
    void getMaps() {
        when(service.get(policyId, organizationId)).thenReturn(view("Block"));

        assertThat(controller.get(policyId, authentication).name()).isEqualTo("Block");
    }

    @Test
    void createDelegatesAndAudits() {
        when(service.create(any(CreateRoutingPolicyCommand.class))).thenReturn(view("Block"));
        var request = new CreateRoutingPolicyRequest("Block", null, null, 1, true,
                codec.toJson(condition), RoutingAction.AUTO_REJECT, null, "blocked");

        var response = controller.create(request, authentication, auditContext);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getHeaders().getLocation()).isNotNull();
        verify(auditLogService).record(any(AuditEntry.class));
    }

    @Test
    void updateDelegatesAndAudits() {
        when(service.update(eq(policyId), eq(organizationId), any(UpdateRoutingPolicyCommand.class)))
                .thenReturn(view("Renamed"));
        var request = new UpdateRoutingPolicyRequest("Renamed", null, null, 1, true,
                codec.toJson(condition), RoutingAction.AUTO_REJECT, null, null);

        var response = controller.update(policyId, request, authentication, auditContext);

        assertThat(response.name()).isEqualTo("Renamed");
        verify(auditLogService).record(any(AuditEntry.class));
    }

    @Test
    void deleteReturns204AndAudits() {
        var response = controller.delete(policyId, authentication, auditContext);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(service).delete(policyId, organizationId);
        verify(auditLogService).record(any(AuditEntry.class));
    }

    @Test
    void reorderDelegatesAndAudits() {
        when(service.reorder(eq(organizationId), any())).thenReturn(List.of(view("Block")));

        var result = controller.reorder(
                new ReorderRoutingPoliciesRequest(List.of(policyId)), authentication, auditContext);

        assertThat(result).hasSize(1);
        verify(auditLogService).record(any(AuditEntry.class));
    }

    // ---- simulate (AF-630) -----------------------------------------------------------------------

    @Test
    void simulateDecodesTheDraftConditionAndPassesTheWindowThrough() {
        var from = java.time.Instant.parse("2026-06-01T00:00:00Z");
        var to = java.time.Instant.parse("2026-07-01T00:00:00Z");
        var replaces = UUID.randomUUID();
        var result = new com.bablsoft.accessflow.workflow.api.RoutingSimulationResult(from, to, null,
                12, 3, 0, false, List.of(), List.of(), List.of(), List.of());
        when(simulationService.simulate(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(result);

        var body = new com.bablsoft.accessflow.workflow.internal.web.model
                .SimulateRoutingPolicyRequest(from, to, null,
                new com.bablsoft.accessflow.workflow.internal.web.model
                        .SimulateRoutingPolicyRequest.Draft(replaces, "Draft", null, 10, null,
                        codec.toJson(condition), RoutingAction.AUTO_REJECT, null, "because"));

        var response = controller.simulate(body, authentication);

        assertThat(response.evaluatedCount()).isEqualTo(12);
        assertThat(response.changedCount()).isEqualTo(3);
        var captor = org.mockito.ArgumentCaptor
                .forClass(com.bablsoft.accessflow.workflow.api.RoutingPolicyDraft.class);
        verify(simulationService).simulate(org.mockito.ArgumentMatchers.eq(organizationId),
                org.mockito.ArgumentMatchers.eq(
                        new com.bablsoft.accessflow.core.api.SimulationWindow(from, to)),
                org.mockito.ArgumentMatchers.isNull(), captor.capture());
        assertThat(captor.getValue().replacesPolicyId()).isEqualTo(replaces);
        assertThat(captor.getValue().condition()).isEqualTo(condition);
        assertThat(captor.getValue().enabled()).isTrue();
    }

    @Test
    void simulateDefaultsAnOmittedEnabledFlagToTrue() {
        var from = java.time.Instant.parse("2026-06-01T00:00:00Z");
        var to = java.time.Instant.parse("2026-07-01T00:00:00Z");
        when(simulationService.simulate(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.bablsoft.accessflow.workflow.api.RoutingSimulationResult(
                        from, to, null, 0, 0, 0, false, List.of(), List.of(), List.of(), List.of()));

        controller.simulate(new com.bablsoft.accessflow.workflow.internal.web.model
                .SimulateRoutingPolicyRequest(from, to, null,
                new com.bablsoft.accessflow.workflow.internal.web.model
                        .SimulateRoutingPolicyRequest.Draft(null, "Draft", null, 10, Boolean.FALSE,
                        codec.toJson(condition), RoutingAction.AUTO_APPROVE, null, null)),
                authentication);

        var captor = org.mockito.ArgumentCaptor
                .forClass(com.bablsoft.accessflow.workflow.api.RoutingPolicyDraft.class);
        verify(simulationService).simulate(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                captor.capture());
        assertThat(captor.getValue().enabled()).isFalse();
    }

    @Test
    void simulateWritesNoAuditRowBecauseItChangesNothing() {
        var from = java.time.Instant.parse("2026-06-01T00:00:00Z");
        var to = java.time.Instant.parse("2026-07-01T00:00:00Z");
        when(simulationService.simulate(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.bablsoft.accessflow.workflow.api.RoutingSimulationResult(
                        from, to, null, 0, 0, 0, false, List.of(), List.of(), List.of(), List.of()));

        controller.simulate(new com.bablsoft.accessflow.workflow.internal.web.model
                .SimulateRoutingPolicyRequest(from, to, null,
                new com.bablsoft.accessflow.workflow.internal.web.model
                        .SimulateRoutingPolicyRequest.Draft(null, "Draft", null, 10, null,
                        codec.toJson(condition), RoutingAction.AUTO_REJECT, null, null)),
                authentication);

        verifyNoInteractions(auditLogService);
    }
}
