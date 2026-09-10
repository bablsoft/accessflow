package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.access.api.AccessGrantLookupService;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourcePermissionContribution;
import com.bablsoft.accessflow.core.api.DatasourcePermissionSourceKind;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RolePermissionHolderLookupService;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.workflow.api.EffectiveAccessQuery;
import com.bablsoft.accessflow.workflow.api.StatementCapability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The reverse index and the enforcement gate must agree, table for table (AF-859).
 *
 * <p>This is the failure mode the feature exists to avoid: a report that confidently says someone
 * can write to a table the gate would refuse — or, worse, stays silent about one it would allow — is
 * worse than no report at all. Both sides run against the same permission fixture and the same
 * quoted / mixed-case / schema-qualified table spellings, and the assertion is a strict
 * <em>if and only if</em>.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EffectiveAccessEnforcementParityTest {

    @Mock DatasourceAdminService datasourceAdminService;
    @Mock DatasourceUserPermissionLookupService permissionLookupService;
    @Mock RolePermissionHolderLookupService rolePermissionHolderLookupService;
    @Mock AccessGrantLookupService accessGrantLookupService;
    @Mock UserQueryService userQueryService;

    private DefaultEffectiveAccessService reportService;
    private DatasourcePermissionVerifier verifier;

    private final UUID organizationId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void buildBothSides() {
        reportService = new DefaultEffectiveAccessService(datasourceAdminService,
                permissionLookupService, rolePermissionHolderLookupService,
                accessGrantLookupService, userQueryService);
        var messages = new StaticMessageSource();
        // The denial message is not what this test is about, and the ambient locale is whatever the
        // machine reports — resolving codes to themselves keeps the assertion on the verdict.
        messages.setUseCodeAsDefaultMessage(true);
        verifier = new DatasourcePermissionVerifier(permissionLookupService, messages,
                Clock.fixed(Instant.parse("2026-09-10T11:00:00Z"), ZoneOffset.UTC));
        when(rolePermissionHolderLookupService.findUserIdsWithPermission(organizationId,
                Permission.QUERY_ADMIN)).thenReturn(List.of());
        when(accessGrantLookupService.findPreApprovingGrantsForDatasource(organizationId,
                datasourceId)).thenReturn(List.of());
        when(userQueryService.findByIds(any())).thenReturn(List.of(
                new UserView(userId, "dana@example.com", "Dana", UserRoleType.ANALYST, null,
                        "ANALYST", organizationId, true, AuthProviderType.LOCAL, null, null, "en",
                        false, false, Instant.now(), null, Instant.now())));
    }

    @ParameterizedTest(name = "[{index}] allow={0}/{1} table={2} -> {3}")
    @CsvSource(delimiter = '|', value = {
            // allowedSchemas | allowedTables      | queried table        | expected
            "               | public.payments     | public.payments      | true",
            "               | public.payments     | PUBLIC.PAYMENTS      | true",
            "               | \"public\".payments | public.payments      | true",
            "               | public.payments     | [public].[payments]  | true",
            "               | public.payments     | public.orders        | false",
            " public        |                     | public.payments      | true",
            " PUBLIC        |                     | public.payments      | true",
            " public        |                     | reporting.payments   | false",
            " public        |                     | payments             | false",
            "               |                     | anything             | true",
            "               | payments            | payments             | true",
            "               | payments            | public.payments      | false",
    })
    void theReportAgreesWithTheGateOnEveryTableSpelling(String allowedSchemas, String allowedTables,
                                                        String table, boolean expected) {
        var schemas = split(allowedSchemas);
        var tables = split(allowedTables);
        var permission = new DatasourceUserPermissionView(UUID.randomUUID(), userId, datasourceId,
                true, false, false, false, schemas, tables, List.of(), null);
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(permission));
        when(permissionLookupService.findContributionsForDatasource(datasourceId))
                .thenReturn(List.of(new DatasourcePermissionContribution(
                        DatasourcePermissionSourceKind.DIRECT, permission.id(), userId,
                        datasourceId, null, null, true, false, false, false, schemas, tables,
                        List.of(), null)));
        when(permissionLookupService.mergeContributions(any()))
                .thenReturn(Optional.of(permission));

        boolean reported = !reportService.report(organizationId,
                new EffectiveAccessQuery(datasourceId, table, StatementCapability.READ),
                PageRequest.of(0, 20)).content().isEmpty();

        // The gate is fed the table exactly as the parser would hand it over: already normalized.
        var normalized = DatasourcePermissionChecker.normalizeList(List.of(table));
        boolean gateAllows = allows(Set.copyOf(normalized));

        assertThat(reported).isEqualTo(expected);
        assertThat(reported).isEqualTo(gateAllows);
    }

    private boolean allows(Set<String> referencedTables) {
        try {
            verifier.verify(userId, datasourceId, QueryType.SELECT, referencedTables);
            return true;
        } catch (AccessDeniedException ex) {
            return false;
        }
    }

    private static List<String> split(String csv) {
        return csv == null || csv.isBlank() ? List.of() : List.of(csv.trim().split(","));
    }
}
