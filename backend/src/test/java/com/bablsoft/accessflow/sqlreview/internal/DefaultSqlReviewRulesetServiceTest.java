package com.bablsoft.accessflow.sqlreview.internal;

import com.bablsoft.accessflow.core.api.DatasourceEnvironment;
import com.bablsoft.accessflow.sqlreview.api.CreateSqlReviewRulesetCommand;
import com.bablsoft.accessflow.sqlreview.api.IllegalSqlReviewRulesetException;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewRuleConfigView;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewRulesetConflictException;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewRulesetNotFoundException;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.UpdateSqlReviewRulesetCommand;
import com.bablsoft.accessflow.sqlreview.internal.persistence.entity.SqlReviewRuleConfigEntity;
import com.bablsoft.accessflow.sqlreview.internal.persistence.entity.SqlReviewRulesetEntity;
import com.bablsoft.accessflow.sqlreview.internal.persistence.repo.SqlReviewRuleConfigRepository;
import com.bablsoft.accessflow.sqlreview.internal.persistence.repo.SqlReviewRulesetRepository;
import com.bablsoft.accessflow.sqlreview.internal.rules.SqlRuleCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultSqlReviewRulesetServiceTest {

    private static final UUID ORG = UUID.randomUUID();

    @Mock
    private SqlReviewRulesetRepository rulesetRepository;
    @Mock
    private SqlReviewRuleConfigRepository ruleConfigRepository;

    private DefaultSqlReviewRulesetService service;

    @BeforeEach
    void setUp() {
        var messages = new StaticMessageSource();
        messages.setUseCodeAsDefaultMessage(true);
        var catalog = new SqlRuleCatalog();
        service = new DefaultSqlReviewRulesetService(rulesetRepository, ruleConfigRepository,
                new SqlRuleParamsValidator(catalog, messages), new SqlRuleParamsCodec(new ObjectMapper(), messages));
        lenient().when(rulesetRepository.saveAndFlush(any(SqlReviewRulesetEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static SqlReviewRulesetEntity ruleset(DatasourceEnvironment environment) {
        var entity = new SqlReviewRulesetEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(ORG);
        entity.setName(environment == null ? "default" : environment.name());
        entity.setEnvironment(environment);
        return entity;
    }

    private static SqlReviewRuleConfigEntity config(SqlReviewRulesetEntity ruleset, String ruleId, String params) {
        var entity = new SqlReviewRuleConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setRuleset(ruleset);
        entity.setRuleId(ruleId);
        entity.setSeverity(SqlReviewSeverity.BLOCK);
        entity.setParams(params);
        return entity;
    }

    private static SqlReviewRuleConfigView rule(String ruleId, Map<String, List<String>> params) {
        return new SqlReviewRuleConfigView(ruleId, SqlReviewSeverity.BLOCK, params);
    }

    @Test
    void listMapsEveryRulesetWithDecodedRules() {
        var production = ruleset(DatasourceEnvironment.PRODUCTION);
        when(rulesetRepository.findAllByOrganizationIdOrderByNameAsc(ORG)).thenReturn(List.of(production));
        when(ruleConfigRepository.findAllByRuleset_IdOrderByRuleIdAsc(production.getId())).thenReturn(List.of(
                config(production, "protected_table", "{\"globs\":[\"payroll.*\"]}"),
                config(production, "select_star", null)));

        var views = service.list(ORG);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).environment()).isEqualTo(DatasourceEnvironment.PRODUCTION);
        assertThat(views.get(0).rules()).extracting(SqlReviewRuleConfigView::ruleId)
                .containsExactly("protected_table", "select_star");
        assertThat(views.get(0).rules().get(0).params()).containsEntry("globs", List.of("payroll.*"));
        assertThat(views.get(0).rules().get(1).params()).isEmpty();
    }

    @Test
    void getInOtherOrganizationIsNotFound() {
        var id = UUID.randomUUID();
        when(rulesetRepository.findByIdAndOrganizationId(id, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(ORG, id))
                .isInstanceOf(SqlReviewRulesetNotFoundException.class)
                .satisfies(ex -> assertThat(((SqlReviewRulesetNotFoundException) ex).rulesetId()).isEqualTo(id));
    }

    @Test
    void createPersistsRulesetAndEncodedRules() {
        when(rulesetRepository.findByOrganizationIdAndEnvironment(ORG, DatasourceEnvironment.PRODUCTION))
                .thenReturn(Optional.empty());
        when(ruleConfigRepository.findAllByRuleset_IdOrderByRuleIdAsc(any())).thenReturn(List.of());

        var view = service.create(ORG, new CreateSqlReviewRulesetCommand(" Production ", "  ",
                DatasourceEnvironment.PRODUCTION, null,
                List.of(rule("protected_table", Map.of("globs", List.of("payroll.*"))), rule("select_star", Map.of()))));

        assertThat(view.id()).isNotNull();
        assertThat(view.organizationId()).isEqualTo(ORG);
        assertThat(view.name()).isEqualTo("Production");
        assertThat(view.description()).isNull();
        assertThat(view.enabled()).isTrue();
        assertThat(view.environment()).isEqualTo(DatasourceEnvironment.PRODUCTION);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SqlReviewRuleConfigEntity>> rows = ArgumentCaptor.forClass(List.class);
        verify(ruleConfigRepository).saveAllAndFlush(rows.capture());
        assertThat(rows.getValue()).extracting(SqlReviewRuleConfigEntity::getRuleId, SqlReviewRuleConfigEntity::getParams)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("protected_table", "{\"globs\":[\"payroll.*\"]}"),
                        org.assertj.core.groups.Tuple.tuple("select_star", null));
        assertThat(rows.getValue()).allSatisfy(row -> {
            assertThat(row.getId()).isNotNull();
            assertThat(row.getRuleset().getId()).isEqualTo(view.id());
        });
    }

    @Test
    void createWithoutRulesWritesNoConfigRows() {
        when(rulesetRepository.findByOrganizationIdAndEnvironmentIsNull(ORG)).thenReturn(Optional.empty());
        when(ruleConfigRepository.findAllByRuleset_IdOrderByRuleIdAsc(any())).thenReturn(List.of());

        var view = service.create(ORG, new CreateSqlReviewRulesetCommand("Default", "desc", null, false, null));

        assertThat(view.environment()).isNull();
        assertThat(view.enabled()).isFalse();
        assertThat(view.description()).isEqualTo("desc");
        verify(ruleConfigRepository, never()).saveAllAndFlush(anyList());
    }

    @Test
    void createRejectsASecondRulesetOnTheSameEnvironment() {
        when(rulesetRepository.findByOrganizationIdAndEnvironment(ORG, DatasourceEnvironment.STAGING))
                .thenReturn(Optional.of(ruleset(DatasourceEnvironment.STAGING)));

        assertThatThrownBy(() -> service.create(ORG, new CreateSqlReviewRulesetCommand("x", null,
                DatasourceEnvironment.STAGING, true, List.of())))
                .isInstanceOf(SqlReviewRulesetConflictException.class)
                .satisfies(ex -> assertThat(((SqlReviewRulesetConflictException) ex).environment())
                        .isEqualTo(DatasourceEnvironment.STAGING));
        verify(rulesetRepository, never()).saveAndFlush(any());
    }

    @Test
    void createRejectsASecondOrganizationDefault() {
        when(rulesetRepository.findByOrganizationIdAndEnvironmentIsNull(ORG)).thenReturn(Optional.of(ruleset(null)));

        assertThatThrownBy(() -> service.create(ORG, new CreateSqlReviewRulesetCommand("x", null, null, true, null)))
                .isInstanceOf(SqlReviewRulesetConflictException.class)
                .satisfies(ex -> assertThat(((SqlReviewRulesetConflictException) ex).environment()).isNull());
    }

    @Test
    void createTranslatesARacedUniqueViolationIntoTheConflict() {
        when(rulesetRepository.findByOrganizationIdAndEnvironment(ORG, DatasourceEnvironment.TEST))
                .thenReturn(Optional.empty());
        when(rulesetRepository.saveAndFlush(any(SqlReviewRulesetEntity.class)))
                .thenThrow(new DataIntegrityViolationException("uq_sql_review_rulesets_org_env"));

        assertThatThrownBy(() -> service.create(ORG, new CreateSqlReviewRulesetCommand("x", null,
                DatasourceEnvironment.TEST, true, null)))
                .isInstanceOf(SqlReviewRulesetConflictException.class);
        verify(ruleConfigRepository, never()).saveAllAndFlush(anyList());
    }

    @Test
    void createValidatesRulesBeforeAnythingElse() {
        assertThatThrownBy(() -> service.create(ORG, new CreateSqlReviewRulesetCommand("x", null, null, true,
                List.of(rule("no_such_rule", Map.of())))))
                .isInstanceOf(IllegalSqlReviewRulesetException.class);
        assertThatThrownBy(() -> service.create(ORG, new CreateSqlReviewRulesetCommand("x", null, null, true,
                List.of(rule("select_star", Map.of()), rule("select_star", Map.of())))))
                .isInstanceOf(IllegalSqlReviewRulesetException.class);
        verify(rulesetRepository, never()).findByOrganizationIdAndEnvironmentIsNull(any());
        verify(rulesetRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateAppliesOnlyTheFieldsSentAndKeepsRulesWhenNull() {
        var entity = ruleset(DatasourceEnvironment.PRODUCTION);
        entity.setDescription("old");
        when(rulesetRepository.findByIdAndOrganizationId(entity.getId(), ORG)).thenReturn(Optional.of(entity));
        when(ruleConfigRepository.findAllByRuleset_IdOrderByRuleIdAsc(entity.getId())).thenReturn(List.of());

        var view = service.update(ORG, entity.getId(),
                new UpdateSqlReviewRulesetCommand("Renamed", null, null, null, false, null));

        assertThat(view.name()).isEqualTo("Renamed");
        assertThat(view.description()).isEqualTo("old");
        assertThat(view.environment()).isEqualTo(DatasourceEnvironment.PRODUCTION);
        assertThat(view.enabled()).isFalse();
        verify(rulesetRepository, never()).findByOrganizationIdAndEnvironment(any(), any());
        verify(ruleConfigRepository, never()).deleteAllByRulesetId(any());
    }

    @Test
    void updateReplacesRulesDeletingBeforeInserting() {
        var entity = ruleset(DatasourceEnvironment.PRODUCTION);
        when(rulesetRepository.findByIdAndOrganizationId(entity.getId(), ORG)).thenReturn(Optional.of(entity));
        when(ruleConfigRepository.findAllByRuleset_IdOrderByRuleIdAsc(entity.getId())).thenReturn(List.of());

        service.update(ORG, entity.getId(), new UpdateSqlReviewRulesetCommand(null, "  ", null, null, null,
                List.of(rule("disallowed_function", Map.of("names", List.of("dblink"))))));

        InOrder order = inOrder(ruleConfigRepository);
        order.verify(ruleConfigRepository).deleteAllByRulesetId(entity.getId());
        order.verify(ruleConfigRepository).saveAllAndFlush(anyList());
        assertThat(entity.getDescription()).isNull();
    }

    @Test
    void updateClearsEnvironmentIntoTheDefaultSlotAfterCheckingIt() {
        var entity = ruleset(DatasourceEnvironment.PRODUCTION);
        when(rulesetRepository.findByIdAndOrganizationId(entity.getId(), ORG)).thenReturn(Optional.of(entity));
        when(rulesetRepository.findByOrganizationIdAndEnvironmentIsNull(ORG)).thenReturn(Optional.empty());
        when(ruleConfigRepository.findAllByRuleset_IdOrderByRuleIdAsc(entity.getId())).thenReturn(List.of());

        var view = service.update(ORG, entity.getId(),
                new UpdateSqlReviewRulesetCommand(null, null, null, true, null, null));

        assertThat(view.environment()).isNull();
    }

    @Test
    void updateRejectsAnEnvironmentAnotherRulesetHoldsButAcceptsItsOwn() {
        var entity = ruleset(DatasourceEnvironment.PRODUCTION);
        when(rulesetRepository.findByIdAndOrganizationId(entity.getId(), ORG)).thenReturn(Optional.of(entity));
        when(rulesetRepository.findByOrganizationIdAndEnvironment(ORG, DatasourceEnvironment.STAGING))
                .thenReturn(Optional.of(ruleset(DatasourceEnvironment.STAGING)));

        assertThatThrownBy(() -> service.update(ORG, entity.getId(),
                new UpdateSqlReviewRulesetCommand(null, null, DatasourceEnvironment.STAGING, null, null, null)))
                .isInstanceOf(SqlReviewRulesetConflictException.class);
        // The pre-check runs before the entity is mutated, so a refused move leaves it untouched.
        assertThat(entity.getEnvironment()).isEqualTo(DatasourceEnvironment.PRODUCTION);
        verify(rulesetRepository, never()).saveAndFlush(any());

        // Re-sending the environment the ruleset already holds is unchanged — no pre-check at all.
        when(ruleConfigRepository.findAllByRuleset_IdOrderByRuleIdAsc(entity.getId())).thenReturn(List.of());
        var view = service.update(ORG, entity.getId(), new UpdateSqlReviewRulesetCommand(null, null,
                DatasourceEnvironment.PRODUCTION, null, null, null));
        assertThat(view.environment()).isEqualTo(DatasourceEnvironment.PRODUCTION);
        verify(rulesetRepository, never()).findByOrganizationIdAndEnvironment(ORG, DatasourceEnvironment.PRODUCTION);
    }

    @Test
    void updateTranslatesARacedUniqueViolationIntoTheConflict() {
        var entity = ruleset(null);
        when(rulesetRepository.findByIdAndOrganizationId(entity.getId(), ORG)).thenReturn(Optional.of(entity));
        when(rulesetRepository.findByOrganizationIdAndEnvironment(ORG, DatasourceEnvironment.TEST))
                .thenReturn(Optional.empty());
        when(rulesetRepository.saveAndFlush(any(SqlReviewRulesetEntity.class)))
                .thenThrow(new DataIntegrityViolationException("uq_sql_review_rulesets_org_env"));

        assertThatThrownBy(() -> service.update(ORG, entity.getId(),
                new UpdateSqlReviewRulesetCommand(null, null, DatasourceEnvironment.TEST, null, null, null)))
                .isInstanceOf(SqlReviewRulesetConflictException.class)
                .satisfies(ex -> assertThat(((SqlReviewRulesetConflictException) ex).environment())
                        .isEqualTo(DatasourceEnvironment.TEST));
    }

    @Test
    void updateValidatesRulesBeforeTouchingTheEntityAndMissingIsNotFound() {
        var entity = ruleset(null);
        when(rulesetRepository.findByIdAndOrganizationId(entity.getId(), ORG)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.update(ORG, entity.getId(), new UpdateSqlReviewRulesetCommand("new", null,
                null, null, null, List.of(rule("protected_table", Map.of("globs", List.of()))))))
                .isInstanceOf(IllegalSqlReviewRulesetException.class);
        assertThat(entity.getName()).isEqualTo("default");
        verify(rulesetRepository, never()).saveAndFlush(any());

        var missing = UUID.randomUUID();
        when(rulesetRepository.findByIdAndOrganizationId(missing, ORG)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(ORG, missing,
                new UpdateSqlReviewRulesetCommand("x", null, null, null, null, null)))
                .isInstanceOf(SqlReviewRulesetNotFoundException.class);
    }

    @Test
    void deleteRemovesConfigsThenTheRuleset() {
        var entity = ruleset(DatasourceEnvironment.TEST);
        when(rulesetRepository.findByIdAndOrganizationId(entity.getId(), ORG)).thenReturn(Optional.of(entity));

        service.delete(ORG, entity.getId());

        InOrder order = inOrder(ruleConfigRepository, rulesetRepository);
        order.verify(ruleConfigRepository).deleteAllByRulesetId(entity.getId());
        order.verify(rulesetRepository).delete(entity);
    }

    @Test
    void deleteOfMissingRulesetIsNotFound() {
        var id = UUID.randomUUID();
        when(rulesetRepository.findByIdAndOrganizationId(id, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(ORG, id)).isInstanceOf(SqlReviewRulesetNotFoundException.class);
        verify(rulesetRepository, never()).delete(any(SqlReviewRulesetEntity.class));
    }
}
