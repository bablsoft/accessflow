package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.AccessTargetMatch;
import com.bablsoft.accessflow.core.api.AccessTargetMatchKind;
import com.bablsoft.accessflow.core.api.ApplicableRowLimitPolicy;
import com.bablsoft.accessflow.core.api.DatasourcePermissionSourceKind;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.ExplainedRowSecurityPredicate;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.ResolvedColumnMask;
import com.bablsoft.accessflow.core.api.ResolvedRowSecurityPredicate;
import com.bablsoft.accessflow.core.api.RevealedColumnMask;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.RowSecurityValueType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.proxy.api.RowCapSource;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.workflow.api.EffectivePermissionExplanation;
import com.bablsoft.accessflow.workflow.api.EffectivePermissionExplanation.AttributedValue;
import com.bablsoft.accessflow.workflow.api.EffectivePermissionExplanation.BytesScannedLimit;
import com.bablsoft.accessflow.workflow.api.EffectivePermissionExplanation.Capability;
import com.bablsoft.accessflow.workflow.api.EffectivePermissionExplanation.CapabilityKind;
import com.bablsoft.accessflow.workflow.api.EffectivePermissionExplanation.Grant;
import com.bablsoft.accessflow.workflow.api.EffectivePermissionExplanation.RowCap;
import com.bablsoft.accessflow.workflow.api.EffectivePermissionExplanation.Scope;
import com.bablsoft.accessflow.workflow.api.EffectivePermissionExplorerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminEffectivePermissionControllerTest {

    @Mock EffectivePermissionExplorerService explorerService;
    @Mock AuditLogService auditLogService;

    private AdminEffectivePermissionController controller;
    private final UUID callerId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID grantId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new AdminEffectivePermissionController(explorerService, auditLogService);
        when(explorerService.explain(organizationId, userId, datasourceId))
                .thenReturn(explanation());
    }

    @Test
    void mapsTheExplanationAndResolvesGroupNamesOnTargetingReasons() {
        var response = controller.explain(userId, datasourceId, authentication(),
                new RequestAuditContext("203.0.113.7", "curl/8.4.0"));

        assertThat(response.user().email()).isEqualTo("dana@example.com");
        assertThat(response.datasource().dbType()).isEqualTo(DbType.POSTGRESQL);
        assertThat(response.rowCap().value()).isEqualTo(5);
        assertThat(response.rowCap().source()).isEqualTo(RowCapSource.OVERRIDE);
        assertThat(response.rowCap().grantIds()).containsExactly(grantId);
        assertThat(response.grants()).singleElement()
                .satisfies(g -> assertThat(g.groupName()).isEqualTo("analysts"));
        assertThat(response.allowedTables().entries()).singleElement()
                .satisfies(v -> assertThat(v.value()).isEqualTo("public.orders"));
        assertThat(response.bytesScannedLimit().value()).isEqualTo(9L);
        assertThat(response.rowSecurity()).singleElement().satisfies(r -> {
            assertThat(r.valueExpression()).isEqualTo("user.region");
            assertThat(r.matchedBy()).singleElement()
                    .satisfies(m -> assertThat(m.name()).isEqualTo("analysts"));
        });
        assertThat(response.revealedMasks()).singleElement().satisfies(m ->
                assertThat(m.revealedBy()).singleElement().satisfies(r -> {
                    assertThat(r.kind()).isEqualTo(AccessTargetMatchKind.ROLE);
                    assertThat(r.name()).isNull();
                }));
        assertThat(response.tableRowLimits()).singleElement().satisfies(t ->
                assertThat(t.matchedBy()).singleElement()
                        .satisfies(m -> assertThat(m.name()).isNull()));
        assertThat(response.maskedColumns()).singleElement()
                .satisfies(m -> assertThat(m.strategy()).isEqualTo(MaskingStrategy.HASH));
    }

    @Test
    void everyReadIsAuditedAgainstTheDatasourceWithTheTargetUser() {
        controller.explain(userId, datasourceId, authentication(),
                new RequestAuditContext("203.0.113.7", "curl/8.4.0"));

        var entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo(AuditAction.EFFECTIVE_PERMISSION_VIEWED);
        assertThat(entry.getValue().resourceType()).isEqualTo(AuditResourceType.DATASOURCE);
        assertThat(entry.getValue().resourceId()).isEqualTo(datasourceId);
        assertThat(entry.getValue().actorId()).isEqualTo(callerId);
        assertThat(entry.getValue().metadata()).containsEntry("target_user_id", userId.toString());
        assertThat(entry.getValue().ipAddress()).isEqualTo("203.0.113.7");
    }

    @Test
    void anAuditOutageDoesNotDenyTheView() {
        doThrow(new IllegalStateException("audit sink down")).when(auditLogService).record(any());

        var response = controller.explain(userId, datasourceId, authentication(), null);

        assertThat(response.hasGrant()).isTrue();
    }

    private EffectivePermissionExplanation explanation() {
        var direct = List.of(grantId);
        return new EffectivePermissionExplanation(userId, "dana@example.com", "Dana",
                datasourceId, "prod", DbType.POSTGRESQL, true, false, null,
                List.of(new Grant(grantId, DatasourcePermissionSourceKind.GROUP, groupId,
                        "analysts", null, 5, 9L, null)),
                List.of(new Capability(CapabilityKind.READ, true, direct)),
                new Scope(true, List.of()),
                new Scope(false, List.of(new AttributedValue("public.orders", direct))),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                new RowCap(5, RowCapSource.OVERRIDE, 5, 1000, 10_000, direct),
                new BytesScannedLimit(9L, direct),
                List.of(new ApplicableRowLimitPolicy(UUID.randomUUID(), null, "orders", 3,
                        List.of(new AccessTargetMatch(AccessTargetMatchKind.GROUP, "not-a-uuid")))),
                List.of(new ResolvedColumnMask(UUID.randomUUID(), "email", MaskingStrategy.HASH,
                        Map.of())),
                List.of(new RevealedColumnMask(UUID.randomUUID(), "ssn", MaskingStrategy.FULL,
                        List.of(new AccessTargetMatch(AccessTargetMatchKind.ROLE, "ADMIN")))),
                List.of(new ExplainedRowSecurityPredicate(new ResolvedRowSecurityPredicate(
                        UUID.randomUUID(), "orders", "region", RowSecurityOperator.EQUALS,
                        List.of("EU")), RowSecurityValueType.VARIABLE, "user.region",
                        List.of(new AccessTargetMatch(AccessTargetMatchKind.GROUP,
                                groupId.toString())))),
                Map.of(groupId, "analysts"));
    }

    private UsernamePasswordAuthenticationToken authentication() {
        var claims = new JwtClaims(callerId, "admin@example.com", UserRoleType.ADMIN, null,
                "ADMIN", Set.of(Permission.DATASOURCE_PERMISSION_MANAGE), organizationId, false);
        return new UsernamePasswordAuthenticationToken(claims, null, List.of());
    }
}
