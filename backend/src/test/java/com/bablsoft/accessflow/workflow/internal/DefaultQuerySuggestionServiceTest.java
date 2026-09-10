package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.workflow.api.QuerySuggestionView;
import com.bablsoft.accessflow.workflow.internal.config.QuerySuggestionProperties;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.QuerySuggestionEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.QuerySuggestionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultQuerySuggestionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID DATASOURCE = UUID.randomUUID();

    private DatasourceAdminService datasourceAdminService;
    private DatasourceUserPermissionLookupService permissionLookupService;
    private QuerySuggestionRepository suggestionRepository;
    private DefaultQuerySuggestionService service;

    @BeforeEach
    void setUp() {
        datasourceAdminService = mock(DatasourceAdminService.class);
        permissionLookupService = mock(DatasourceUserPermissionLookupService.class);
        suggestionRepository = mock(QuerySuggestionRepository.class);
        service = newService(true);
    }

    private DefaultQuerySuggestionService newService(boolean enabled) {
        var properties = new QuerySuggestionProperties(enabled, null, null, 0, 0, 0, 0, 0,
                Duration.ofDays(14), 1, 1, 1, 10, 50, null);
        return new DefaultQuerySuggestionService(datasourceAdminService, permissionLookupService,
                suggestionRepository, new QuerySuggestionScorer(properties), properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void invisibleDatasourceIsNotFoundRatherThanForbidden() {
        when(datasourceAdminService.getForUser(DATASOURCE, ORG, USER))
                .thenThrow(new DatasourceNotFoundException(DATASOURCE));

        assertThatThrownBy(() -> service.findForViewer(DATASOURCE, ORG, USER, false, 10))
                .isInstanceOf(DatasourceNotFoundException.class);
        verify(suggestionRepository, never())
                .findByDatasourceIdOrderByApprovedCountDescLastSubmittedAtDesc(any());
    }

    @Test
    void adminVisibilityGoesThroughTheAdminLookup() {
        givenRows();

        service.findForViewer(DATASOURCE, ORG, USER, true, 10);

        verify(datasourceAdminService).getForAdmin(DATASOURCE, ORG);
        verify(datasourceAdminService, never()).getForUser(any(), any(), any());
    }

    @Test
    void disabledFeatureStillAnswersNotFoundForAnInvisibleDatasource() {
        var disabled = newService(false);
        when(datasourceAdminService.getForUser(DATASOURCE, ORG, USER))
                .thenThrow(new DatasourceNotFoundException(DATASOURCE));

        assertThatThrownBy(() -> disabled.findForViewer(DATASOURCE, ORG, USER, false, 10))
                .isInstanceOf(DatasourceNotFoundException.class);
    }

    @Test
    void disabledFeatureReturnsAnEmptyRail() {
        var disabled = newService(false);

        assertThat(disabled.findForViewer(DATASOURCE, ORG, USER, false, 10)).isEmpty();
        verify(permissionLookupService, never()).findFor(any(), any());
    }

    @Test
    void aGrantThatVanishedAfterTheVisibilityCheckFailsClosedToAnEmptyRail() {
        when(permissionLookupService.findFor(USER, DATASOURCE)).thenReturn(Optional.empty());

        assertThat(service.findForViewer(DATASOURCE, ORG, USER, false, 10)).isEmpty();
    }

    @Test
    void aGrantThatExpiredAfterTheVisibilityCheckFailsClosedToAnEmptyRail() {
        when(permissionLookupService.findFor(USER, DATASOURCE))
                .thenReturn(Optional.of(permission(true, true, true, List.of(), List.of(),
                        NOW.minusSeconds(1))));

        assertThat(service.findForViewer(DATASOURCE, ORG, USER, false, 10)).isEmpty();
    }

    @Test
    void unexpiredGrantWithAFutureExpiryIsAccepted() {
        givenRows(row("select 1 from orders", QueryType.SELECT, new String[]{"orders"}, 5));
        when(permissionLookupService.findFor(USER, DATASOURCE))
                .thenReturn(Optional.of(permission(true, false, false, List.of(), List.of(),
                        NOW.plusSeconds(3600))));

        assertThat(service.findForViewer(DATASOURCE, ORG, USER, false, 10)).hasSize(1);
    }

    @Test
    void suggestionsTheGrantLacksTheCapabilityForAreDropped() {
        givenRows(row("select 1 from orders", QueryType.SELECT, new String[]{"orders"}, 5),
                row("delete from orders", QueryType.DELETE, new String[]{"orders"}, 5),
                row("drop table orders", QueryType.DDL, new String[]{"orders"}, 5));
        givenPermission(true, false, false, List.of(), List.of());

        var railed = service.findForViewer(DATASOURCE, ORG, USER, false, 10);

        assertThat(railed).extracting(QuerySuggestionView::queryType)
                .containsExactly(QueryType.SELECT);
    }

    @Test
    void suggestionsReferencingATableOutsideTheAllowListAreDropped() {
        givenRows(row("select 1 from orders", QueryType.SELECT, new String[]{"orders"}, 5),
                row("select 1 from salaries", QueryType.SELECT, new String[]{"salaries"}, 9));
        givenPermission(true, false, false, List.of(), List.of("orders"));

        var railed = service.findForViewer(DATASOURCE, ORG, USER, false, 10);

        assertThat(railed).extracting(QuerySuggestionView::sqlText)
                .containsExactly("select 1 from orders");
    }

    @Test
    void aSuggestionIsDroppedWhenAnyOneOfItsTablesIsOutsideTheAllowList() {
        givenRows(row("join", QueryType.SELECT, new String[]{"orders", "salaries"}, 5));
        givenPermission(true, false, false, List.of(), List.of("orders"));

        assertThat(service.findForViewer(DATASOURCE, ORG, USER, false, 10)).isEmpty();
    }

    @Test
    void aRowWithNoResolvedTablesIsNeverServedToAnyone() {
        // The aggregation should never persist one, but rejectedTables() reports "nothing rejected"
        // for an empty set — so if one ever reached the table it would clear every viewer's
        // allow-list. Belt for the whole feature's disclosure story.
        givenRows(row("select 1", QueryType.SELECT, new String[]{}, 5));
        givenPermission(true, false, false, List.of(), List.of("orders"));

        assertThat(service.findForViewer(DATASOURCE, ORG, USER, false, 10)).isEmpty();
        assertThat(service.findForViewer(DATASOURCE, ORG, USER, true, 10)).isEmpty();
    }

    @Test
    void queryAdminSkipsTheGrantAndTableFilters() {
        givenRows(row("drop table salaries", QueryType.DDL, new String[]{"salaries"}, 5));

        var railed = service.findForViewer(DATASOURCE, ORG, USER, true, 10);

        assertThat(railed).hasSize(1);
        verify(permissionLookupService, never()).findFor(any(), any());
    }

    @Test
    void rowsAreRankedByScoreNotByRepositoryOrder() {
        var stale = row("stale", QueryType.SELECT, new String[]{"orders"}, 40);
        stale.setLastSubmittedAt(NOW.minus(Duration.ofDays(140)));
        var fresh = row("fresh", QueryType.SELECT, new String[]{"orders"}, 30);
        fresh.setLastSubmittedAt(NOW);
        // Repository order is frequency-first, so the stale row arrives first and must be reordered.
        givenRows(stale, fresh);
        givenPermission(true, false, false, List.of(), List.of());

        var railed = service.findForViewer(DATASOURCE, ORG, USER, false, 10);

        assertThat(railed).extracting(QuerySuggestionView::sqlText).containsExactly("fresh",
                "stale");
    }

    @Test
    void theViewersOwnTablesLiftAnOtherwiseWeakerSuggestion() {
        var otherPeoples = row("theirs", QueryType.SELECT, new String[]{"refunds"}, 9);
        var mine = row("mine", QueryType.SELECT, new String[]{"orders"}, 8);
        mine.setSubmitterIds(new UUID[]{USER});
        givenRows(otherPeoples, mine);
        givenPermission(true, false, false, List.of(), List.of());

        var railed = service.findForViewer(DATASOURCE, ORG, USER, false, 10);

        // ln(1+8) + overlap 1.0 beats ln(1+9) + overlap 0 — the analyst's own tables win.
        assertThat(railed).extracting(QuerySuggestionView::sqlText).containsExactly("mine",
                "theirs");
    }

    @Test
    void limitIsClampedAndDefaultsWhenUnset() {
        var rows = new QuerySuggestionEntity[12];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = row("q" + i, QueryType.SELECT, new String[]{"orders"}, 12 - i);
        }
        givenRows(rows);
        givenPermission(true, false, false, List.of(), List.of());

        assertThat(service.findForViewer(DATASOURCE, ORG, USER, false, 3)).hasSize(3);
        // 0 means "unset" and falls back to the configured default of 10.
        assertThat(service.findForViewer(DATASOURCE, ORG, USER, false, 0)).hasSize(10);
        // Above maxLimit it is clamped, which for 12 rows means all of them.
        assertThat(service.findForViewer(DATASOURCE, ORG, USER, false, 900)).hasSize(12);
    }

    @Test
    void viewCarriesTheEvidenceButNeverTheSubmitterIdentities() {
        var entity = row("select 1 from orders", QueryType.SELECT, new String[]{"orders"}, 7);
        entity.setDistinctSubmitterCount(3);
        entity.setSubmitterIds(new UUID[]{USER, UUID.randomUUID()});
        givenRows(entity);
        givenPermission(true, false, false, List.of(), List.of());

        var view = service.findForViewer(DATASOURCE, ORG, USER, false, 10).getFirst();

        assertThat(view.approvedCount()).isEqualTo(7);
        assertThat(view.distinctSubmitterCount()).isEqualTo(3);
        assertThat(view.referencedTables()).containsExactly("orders");
        assertThat(view.score()).isPositive();
    }

    private void givenPermission(boolean read, boolean write, boolean ddl,
                                 List<String> schemas, List<String> tables) {
        when(permissionLookupService.findFor(eq(USER), eq(DATASOURCE)))
                .thenReturn(Optional.of(permission(read, write, ddl, schemas, tables, null)));
    }

    private void givenRows(QuerySuggestionEntity... rows) {
        when(suggestionRepository
                .findByDatasourceIdOrderByApprovedCountDescLastSubmittedAtDesc(DATASOURCE))
                .thenReturn(List.of(rows));
    }

    private static QuerySuggestionEntity row(String sql, QueryType type, String[] tables,
                                             int approvedCount) {
        var entity = new QuerySuggestionEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(ORG);
        entity.setDatasourceId(DATASOURCE);
        entity.setCanonicalHash(UUID.randomUUID().toString().replace("-", ""));
        entity.setSqlText(sql);
        entity.setQueryType(type);
        entity.setReferencedTables(tables);
        entity.setApprovedCount(approvedCount);
        entity.setDistinctSubmitterCount(1);
        entity.setFirstSubmittedAt(NOW.minus(Duration.ofDays(30)));
        entity.setLastSubmittedAt(NOW.minus(Duration.ofDays(1)));
        entity.setComputedAt(NOW);
        return entity;
    }

    private static DatasourceUserPermissionView permission(boolean read, boolean write,
                                                           boolean ddl, List<String> schemas,
                                                           List<String> tables, Instant expiresAt) {
        return new DatasourceUserPermissionView(UUID.randomUUID(), USER, DATASOURCE, read, write,
                ddl, false, schemas, tables, List.of(), expiresAt);
    }
}
