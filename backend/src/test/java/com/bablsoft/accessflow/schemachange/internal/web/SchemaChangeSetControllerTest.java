package com.bablsoft.accessflow.schemachange.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.schemachange.api.CreateSchemaChangeSetCommand;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetListFilter;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetService;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatementInput;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatementView;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetView;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeStatementFinding;
import com.bablsoft.accessflow.schemachange.api.UpdateSchemaChangeSetCommand;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewFindingRenderer;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchemaChangeSetControllerTest {

    private final UUID orgId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();
    private final UUID changeSetId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();

    private SchemaChangeSetService service;
    private SqlReviewFindingRenderer renderer;
    private SchemaChangeSetController controller;

    @BeforeEach
    void setUp() {
        service = mock(SchemaChangeSetService.class);
        renderer = mock(SqlReviewFindingRenderer.class);
        when(renderer.message(any(SqlReviewFinding.class), any(Locale.class)))
                .thenAnswer(inv -> "msg:" + ((SqlReviewFinding) inv.getArgument(0)).ruleId()
                        + ":" + ((Locale) inv.getArgument(1)).toLanguageTag());
        controller = new SchemaChangeSetController(service, renderer);
        LocaleContextHolder.setLocale(Locale.GERMANY);
    }

    /** The holder is thread-local and surefire runs every class in one fork — a leaked locale breaks unrelated tests. */
    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void controllerIsGatedByTheSchemaChangeManagePermission() {
        var gate = SchemaChangeSetController.class.getAnnotation(PreAuthorize.class);

        assertThat(gate).isNotNull();
        assertThat(gate.value()).isEqualTo("hasAuthority('PERM_SCHEMA_CHANGE_MANAGE')");
    }

    @Test
    void listForwardsFiltersAndPagingAndRendersNothingOnReads() {
        var view = view(List.of());
        when(service.list(eq(orgId), any(), any())).thenReturn(new PageResponse<>(List.of(view), 1, 5, 6, 2));

        var response = controller.list(pipelineId, SchemaChangeSetStatus.DRAFT, auth(), PageRequest.of(1, 5));

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).id()).isEqualTo(changeSetId);
        assertThat(response.content().get(0).reviewWarnings()).isEmpty();
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(5);
        assertThat(response.totalElements()).isEqualTo(6);
        assertThat(response.totalPages()).isEqualTo(2);
        var filter = ArgumentCaptor.forClass(SchemaChangeSetListFilter.class);
        var page = ArgumentCaptor.forClass(com.bablsoft.accessflow.core.api.PageRequest.class);
        verify(service).list(eq(orgId), filter.capture(), page.capture());
        assertThat(filter.getValue().pipelineId()).isEqualTo(pipelineId);
        assertThat(filter.getValue().status()).isEqualTo(SchemaChangeSetStatus.DRAFT);
        assertThat(page.getValue().page()).isEqualTo(1);
        assertThat(page.getValue().size()).isEqualTo(5);
    }

    @Test
    void listWithoutFiltersPassesNulls() {
        when(service.list(eq(orgId), any(), any())).thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        controller.list(null, null, auth(), PageRequest.of(0, 20));

        var filter = ArgumentCaptor.forClass(SchemaChangeSetListFilter.class);
        verify(service).list(eq(orgId), filter.capture(), any());
        assertThat(filter.getValue()).isEqualTo(SchemaChangeSetListFilter.none());
    }

    @Test
    void getScopesToTheCallerOrganization() {
        when(service.get(orgId, changeSetId)).thenReturn(view(List.of()));

        var response = controller.get(changeSetId, auth());

        assertThat(response.id()).isEqualTo(changeSetId);
        assertThat(response.pipelineId()).isEqualTo(pipelineId);
        assertThat(response.statements()).extracting("sqlText").containsExactly("CREATE TABLE t (id INT)");
    }

    @Test
    void createForwardsTheCallerAndRendersWarningsInTheRequestLocale() {
        var warning = new SchemaChangeStatementFinding(0, datasourceId,
                new SqlReviewFinding("ddl_statement", SqlReviewSeverity.WARN, 0, 1, Map.of()));
        when(service.create(eq(orgId), eq(adminId), any())).thenReturn(view(List.of(warning)));

        var response = controller.create(new CreateSchemaChangeSetRequest(pipelineId, "cs", "d",
                List.of(new SchemaChangeSetStatementRequest("CREATE TABLE t (id INT)"))), auth());

        assertThat(response.reviewWarnings()).singleElement().satisfies(w -> {
            assertThat(w.statementIndex()).isZero();
            assertThat(w.datasourceId()).isEqualTo(datasourceId);
            assertThat(w.ruleId()).isEqualTo("ddl_statement");
            assertThat(w.severity()).isEqualTo(SqlReviewSeverity.WARN);
            assertThat(w.lineNumber()).isEqualTo(1);
            assertThat(w.message()).isEqualTo("msg:ddl_statement:de-DE");
        });
        var command = ArgumentCaptor.forClass(CreateSchemaChangeSetCommand.class);
        verify(service).create(eq(orgId), eq(adminId), command.capture());
        assertThat(command.getValue().pipelineId()).isEqualTo(pipelineId);
        assertThat(command.getValue().name()).isEqualTo("cs");
        assertThat(command.getValue().description()).isEqualTo("d");
        assertThat(command.getValue().statements()).containsExactly(new SchemaChangeSetStatementInput("CREATE TABLE t (id INT)"));
    }

    @Test
    void createWithoutStatementsSendsAnEmptyList() {
        when(service.create(eq(orgId), eq(adminId), any())).thenReturn(view(List.of()));

        controller.create(new CreateSchemaChangeSetRequest(pipelineId, "cs", null, null), auth());

        var command = ArgumentCaptor.forClass(CreateSchemaChangeSetCommand.class);
        verify(service).create(eq(orgId), eq(adminId), command.capture());
        assertThat(command.getValue().statements()).isEmpty();
    }

    @Test
    void updateForwardsTheNullMeansUnchangedCommand() {
        when(service.update(eq(orgId), eq(changeSetId), any())).thenReturn(view(List.of()));

        controller.update(changeSetId, new UpdateSchemaChangeSetRequest(null, "d2", SchemaChangeSetStatus.ARCHIVED), auth());

        verify(service).update(orgId, changeSetId, new UpdateSchemaChangeSetCommand(null, "d2", SchemaChangeSetStatus.ARCHIVED));
    }

    @Test
    void replaceStatementsForwardsTheOrderedInputs() {
        when(service.replaceStatements(eq(orgId), eq(changeSetId), any())).thenReturn(view(List.of()));

        controller.replaceStatements(changeSetId, new ReplaceSchemaChangeSetStatementsRequest(List.of(
                new SchemaChangeSetStatementRequest("B"), new SchemaChangeSetStatementRequest("A"))), auth());

        verify(service).replaceStatements(orgId, changeSetId,
                List.of(new SchemaChangeSetStatementInput("B"), new SchemaChangeSetStatementInput("A")));
    }

    @Test
    void deleteScopesToTheCallerOrganization() {
        controller.delete(changeSetId, auth());

        verify(service).delete(orgId, changeSetId);
    }

    private Authentication auth() {
        var authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(
                JwtClaims.forSystemRole(adminId, "admin@acme.test", UserRoleType.ADMIN, orgId));
        return authentication;
    }

    private SchemaChangeSetView view(List<SchemaChangeStatementFinding> warnings) {
        var statement = new SchemaChangeSetStatementView(UUID.randomUUID(), 0, "CREATE TABLE t (id INT)",
                QueryType.DDL, Instant.EPOCH);
        return new SchemaChangeSetView(changeSetId, orgId, pipelineId, "cs", "d", SchemaChangeSetStatus.DRAFT,
                "a".repeat(64), adminId, Instant.EPOCH, Instant.EPOCH, List.of(statement), warnings);
    }
}
