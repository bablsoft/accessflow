package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.AccessTargetMatch;
import com.bablsoft.accessflow.core.api.ApplicableRowLimitPolicy;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.core.api.DatasourcePermissionContribution;
import com.bablsoft.accessflow.core.api.DatasourcePermissionSourceKind;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import com.bablsoft.accessflow.core.api.DatasourceView;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.ExplainedRowSecurityPredicate;
import com.bablsoft.accessflow.core.api.MaskingExplanation;
import com.bablsoft.accessflow.core.api.MaskingPolicyResolutionService;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.QueryShape;
import com.bablsoft.accessflow.core.api.ResolvedColumnMask;
import com.bablsoft.accessflow.core.api.ResolvedRowSecurityPredicate;
import com.bablsoft.accessflow.core.api.RolePermissionHolderLookupService;
import com.bablsoft.accessflow.core.api.RowLimitPolicyResolutionService;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.RowSecurityResolutionService;
import com.bablsoft.accessflow.core.api.RowSecurityValueType;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UserGroupService;
import com.bablsoft.accessflow.core.api.UserGroupView;
import com.bablsoft.accessflow.core.api.UserNotFoundException;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.proxy.api.EffectiveRowCap;
import com.bablsoft.accessflow.proxy.api.RowCapResolver;
import com.bablsoft.accessflow.proxy.api.RowCapSource;
import com.bablsoft.accessflow.workflow.api.EffectivePermissionExplanation.AttributedValue;
import com.bablsoft.accessflow.workflow.api.EffectivePermissionExplanation.CapabilityKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultEffectivePermissionExplorerServiceTest {

    private static final int GLOBAL_CEILING = 10_000;

    @Mock UserQueryService userQueryService;
    @Mock DatasourceAdminService datasourceAdminService;
    @Mock DatasourceUserPermissionLookupService permissionLookupService;
    @Mock RolePermissionHolderLookupService rolePermissionHolderLookupService;
    @Mock MaskingPolicyResolutionService maskingPolicyResolutionService;
    @Mock RowSecurityResolutionService rowSecurityResolutionService;
    @Mock RowLimitPolicyResolutionService rowLimitPolicyResolutionService;
    @Mock UserGroupService userGroupService;
    @Mock RowCapResolver rowCapResolver;

    private DefaultEffectivePermissionExplorerService service;

    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID directId = UUID.randomUUID();
    private final UUID groupGrantId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();
    private final Instant groupExpiry = Instant.parse("2026-10-06T00:00:00Z");

    @BeforeEach
    void setUp() {
        service = new DefaultEffectivePermissionExplorerService(userQueryService,
                datasourceAdminService, permissionLookupService, rolePermissionHolderLookupService,
                maskingPolicyResolutionService, rowSecurityResolutionService,
                rowLimitPolicyResolutionService, userGroupService, rowCapResolver);
        when(userQueryService.findById(userId)).thenReturn(Optional.of(user(organizationId)));
        when(datasourceAdminService.getForAdmin(datasourceId, organizationId))
                .thenReturn(datasource(1000));
        when(rolePermissionHolderLookupService.findUserIdsWithPermission(organizationId,
                Permission.QUERY_ADMIN)).thenReturn(List.of());
        when(maskingPolicyResolutionService.explain(organizationId, datasourceId, userId))
                .thenReturn(new MaskingExplanation(List.of(), List.of()));
        when(userGroupService.listAll(organizationId)).thenReturn(List.of(
                new UserGroupView(groupId, organizationId, "analysts", null, 3, Instant.now(),
                        Instant.now())));
        when(rowCapResolver.resolve(any(), anyInt())).thenAnswer(inv -> EffectiveRowCap.of(
                inv.getArgument(0), inv.getArgument(1), GLOBAL_CEILING));
    }

    @Test
    void attributesEveryMergedElementToTheGrantsThatCarryIt() {
        var direct = contribution(DatasourcePermissionSourceKind.DIRECT, directId, null, null,
                true, false, List.of("public.orders"), List.of("public.secrets"),
                List.of("email"), List.of(), 5000, 2_000L, null);
        var viaGroup = contribution(DatasourcePermissionSourceKind.GROUP, groupGrantId, groupId,
                "analysts", true, true, List.of("public.orders", "public.items"), List.of(),
                List.of("email", "phone"), List.of(QueryShape.CTE), 50, null, groupExpiry);
        givenContributions(List.of(direct, viaGroup), merged(true, true,
                List.of("public.orders", "public.items"), List.of("public.secrets"),
                List.of("email"), List.of(QueryShape.CTE), 50, 2_000L, null));

        var e = service.explain(organizationId, userId, datasourceId);

        assertThat(e.hasGrant()).isTrue();
        assertThat(e.queryAdmin()).isFalse();
        assertThat(e.grants()).extracting(g -> g.grantId()).containsExactly(directId, groupGrantId);
        assertThat(e.grants().get(1).groupName()).isEqualTo("analysts");
        assertThat(e.grants().get(1).expiresAt()).isEqualTo(groupExpiry);
        assertThat(e.capabilities()).filteredOn(c -> c.capability() == CapabilityKind.READ)
                .singleElement().satisfies(c -> {
                    assertThat(c.granted()).isTrue();
                    assertThat(c.grantIds()).containsExactly(directId, groupGrantId);
                });
        assertThat(e.capabilities()).filteredOn(c -> c.capability() == CapabilityKind.WRITE)
                .singleElement().satisfies(c -> assertThat(c.grantIds())
                        .containsExactly(groupGrantId));
        assertThat(e.capabilities()).filteredOn(c -> c.capability() == CapabilityKind.DDL)
                .singleElement().satisfies(c -> {
                    assertThat(c.granted()).isFalse();
                    assertThat(c.grantIds()).isEmpty();
                });
        assertThat(e.allowedTables().unrestricted()).isFalse();
        assertThat(e.allowedTables().entries()).containsExactly(
                new AttributedValue("public.orders", List.of(directId, groupGrantId)),
                new AttributedValue("public.items", List.of(groupGrantId)));
        assertThat(e.allowedSchemas().unrestricted()).isTrue();
        assertThat(e.deniedTables()).containsExactly(
                new AttributedValue("public.secrets", List.of(directId)));
        assertThat(e.restrictedColumns()).containsExactly(
                new AttributedValue("email", List.of(directId, groupGrantId)));
        assertThat(e.deniedShapes()).containsExactly(
                new AttributedValue("CTE", List.of(groupGrantId)));
        assertThat(e.bytesScannedLimit().value()).isEqualTo(2_000L);
        assertThat(e.bytesScannedLimit().grantIds()).containsExactly(directId);
        assertThat(e.groupNames()).containsEntry(groupId, "analysts");
    }

    @Test
    void rowCapFromANamedGroupOverride() {
        var direct = rowLimited(DatasourcePermissionSourceKind.DIRECT, directId, 5000);
        var viaGroup = rowLimited(DatasourcePermissionSourceKind.GROUP, groupGrantId, 50);
        givenContributions(List.of(direct, viaGroup), mergedRowLimit(50));

        var cap = service.explain(organizationId, userId, datasourceId).rowCap();

        assertThat(cap.value()).isEqualTo(50);
        assertThat(cap.source()).isEqualTo(RowCapSource.OVERRIDE);
        assertThat(cap.grantIds()).containsExactly(groupGrantId);
    }

    @Test
    void rowCapFromTheDirectGrantOverride() {
        givenContributions(List.of(rowLimited(DatasourcePermissionSourceKind.DIRECT, directId, 5)),
                mergedRowLimit(5));

        var cap = service.explain(organizationId, userId, datasourceId).rowCap();

        assertThat(cap.value()).isEqualTo(5);
        assertThat(cap.source()).isEqualTo(RowCapSource.OVERRIDE);
        assertThat(cap.grantIds()).containsExactly(directId);
    }

    @Test
    void anOverrideAboveTheDatasourceCapIsClampedAndAttributedToTheDatasource() {
        givenContributions(
                List.of(rowLimited(DatasourcePermissionSourceKind.DIRECT, directId, 5000)),
                mergedRowLimit(5000));

        var cap = service.explain(organizationId, userId, datasourceId).rowCap();

        assertThat(cap.value()).isEqualTo(1000);
        assertThat(cap.source()).isEqualTo(RowCapSource.DATASOURCE_CAP);
        assertThat(cap.override()).isEqualTo(5000);
        assertThat(cap.datasourceCap()).isEqualTo(1000);
        assertThat(cap.grantIds()).isEmpty();
    }

    @Test
    void aDatasourceCapAboveTheGlobalCeilingIsClampedToIt() {
        when(datasourceAdminService.getForAdmin(datasourceId, organizationId))
                .thenReturn(datasource(50_000));
        givenContributions(List.of(), null);

        var e = service.explain(organizationId, userId, datasourceId);

        assertThat(e.rowCap().value()).isEqualTo(GLOBAL_CEILING);
        assertThat(e.rowCap().source()).isEqualTo(RowCapSource.GLOBAL_CEILING);
        assertThat(e.rowCap().globalCeiling()).isEqualTo(GLOBAL_CEILING);
    }

    @Test
    void noGrantIsAnAnswerNotAnError() {
        givenContributions(List.of(), null);
        when(rolePermissionHolderLookupService.findUserIdsWithPermission(organizationId,
                Permission.QUERY_ADMIN)).thenReturn(List.of(userId));

        var e = service.explain(organizationId, userId, datasourceId);

        assertThat(e.hasGrant()).isFalse();
        assertThat(e.queryAdmin()).isTrue();
        assertThat(e.expiresAt()).isNull();
        assertThat(e.grants()).isEmpty();
        assertThat(e.capabilities()).allSatisfy(c -> assertThat(c.granted()).isFalse());
        assertThat(e.allowedTables().unrestricted()).isFalse();
        assertThat(e.deniedTables()).isEmpty();
        assertThat(e.deniedShapes()).isEmpty();
        assertThat(e.bytesScannedLimit()).isNull();
        assertThat(e.rowCap().source()).isEqualTo(RowCapSource.DATASOURCE_CAP);
    }

    @Test
    void policiesComeStraightFromTheResolutionServices() {
        givenContributions(List.of(), null);
        var mask = new ResolvedColumnMask(UUID.randomUUID(), "public.users.email",
                MaskingStrategy.PARTIAL, Map.of());
        when(maskingPolicyResolutionService.explain(organizationId, datasourceId, userId))
                .thenReturn(new MaskingExplanation(List.of(mask), List.of()));
        var predicate = new ExplainedRowSecurityPredicate(new ResolvedRowSecurityPredicate(
                UUID.randomUUID(), "orders", "region", RowSecurityOperator.EQUALS,
                List.of("EU")), RowSecurityValueType.VARIABLE, "user.region",
                List.of(AccessTargetMatch.EVERYONE));
        when(rowSecurityResolutionService.explainApplicable(organizationId, datasourceId, userId))
                .thenReturn(List.of(predicate));
        var tableLimit = new ApplicableRowLimitPolicy(UUID.randomUUID(), "public", "orders", 10,
                List.of(AccessTargetMatch.EVERYONE));
        when(rowLimitPolicyResolutionService.findApplicable(organizationId, datasourceId, userId))
                .thenReturn(List.of(tableLimit));

        var e = service.explain(organizationId, userId, datasourceId);

        assertThat(e.maskedColumns()).containsExactly(mask);
        assertThat(e.rowSecurity()).containsExactly(predicate);
        assertThat(e.tableRowLimits()).containsExactly(tableLimit);
    }

    @Test
    void aUserInAnotherOrganizationIsNotFound() {
        when(userQueryService.findById(userId)).thenReturn(Optional.of(user(UUID.randomUUID())));

        assertThatThrownBy(() -> service.explain(organizationId, userId, datasourceId))
                .isInstanceOf(UserNotFoundException.class);
        verify(permissionLookupService, never()).findContributions(any(), any());
    }

    @Test
    void aDatasourceInAnotherOrganizationIsNotFound() {
        when(datasourceAdminService.getForAdmin(datasourceId, organizationId))
                .thenThrow(new DatasourceNotFoundException(datasourceId));

        assertThatThrownBy(() -> service.explain(organizationId, userId, datasourceId))
                .isInstanceOf(DatasourceNotFoundException.class);
    }

    private void givenContributions(List<DatasourcePermissionContribution> contributions,
                                    DatasourceUserPermissionView merged) {
        when(permissionLookupService.findContributions(userId, datasourceId))
                .thenReturn(contributions);
        when(permissionLookupService.mergeContributions(contributions))
                .thenReturn(Optional.ofNullable(merged));
    }

    private DatasourcePermissionContribution rowLimited(DatasourcePermissionSourceKind kind,
                                                        UUID id, Integer rowLimit) {
        return contribution(kind, id, kind == DatasourcePermissionSourceKind.GROUP ? groupId : null,
                kind == DatasourcePermissionSourceKind.GROUP ? "analysts" : null, true, false,
                List.of(), List.of(), List.of(), List.of(), rowLimit, null, null);
    }

    private DatasourcePermissionContribution contribution(
            DatasourcePermissionSourceKind kind, UUID id, UUID group, String groupName,
            boolean read, boolean write, List<String> tables, List<String> deniedTables,
            List<String> restricted, List<QueryShape> shapes, Integer rowLimit, Long bytesLimit,
            Instant expiresAt) {
        return new DatasourcePermissionContribution(kind, id, userId, datasourceId, group,
                groupName, read, write, false, false, List.of(), tables, restricted, List.of(),
                List.of(), deniedTables, shapes, rowLimit, bytesLimit, expiresAt, null);
    }

    private DatasourceUserPermissionView mergedRowLimit(int rowLimit) {
        return merged(true, false, List.of(), List.of(), List.of(), List.of(), rowLimit, null,
                null);
    }

    private DatasourceUserPermissionView merged(boolean read, boolean write, List<String> tables,
                                                List<String> deniedTables,
                                                List<String> restricted, List<QueryShape> shapes,
                                                Integer rowLimit, Long bytesLimit,
                                                Instant expiresAt) {
        return new DatasourceUserPermissionView(directId, userId, datasourceId, read, write, false,
                false, List.of(), tables, restricted, List.of(), List.of(), deniedTables, shapes,
                rowLimit, bytesLimit, expiresAt);
    }

    private UserView user(UUID orgId) {
        return new UserView(userId, "dana@example.com", "Dana", UserRoleType.ANALYST, null,
                "ANALYST", orgId, true, AuthProviderType.LOCAL, null, null, "en", false, false,
                Instant.now(), null, Instant.now());
    }

    private DatasourceView datasource(int maxRows) {
        return new DatasourceView(datasourceId, organizationId, "prod", DbType.POSTGRESQL,
                "db.invalid", 5432, "app", "u", SslMode.DISABLE, 5, maxRows, false, true, null,
                true, null, false, null, null, null, List.of(), true, Instant.now(), null, false,
                null);
    }
}
