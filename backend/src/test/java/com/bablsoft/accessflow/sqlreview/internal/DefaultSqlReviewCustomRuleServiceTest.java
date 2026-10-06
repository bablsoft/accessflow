package com.bablsoft.accessflow.sqlreview.internal;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.proxy.api.SqlParserService;
import com.bablsoft.accessflow.sqlreview.api.IllegalSqlReviewCustomRuleException;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewCustomRuleCommand;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewCustomRuleConflictException;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewCustomRuleNotFoundException;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewCustomRuleService;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCondition;
import com.bablsoft.accessflow.sqlreview.events.SqlReviewCustomRuleChangedEvent;
import com.bablsoft.accessflow.sqlreview.internal.persistence.entity.SqlReviewCustomRuleEntity;
import com.bablsoft.accessflow.sqlreview.internal.persistence.repo.SqlReviewCustomRuleRepository;
import com.bablsoft.accessflow.sqlreview.internal.persistence.repo.SqlReviewRuleConfigRepository;
import com.bablsoft.accessflow.sqlreview.internal.rules.condition.SqlRuleConditionValidator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DefaultSqlReviewCustomRuleServiceTest {

    private static final SqlRuleCondition NO_WHERE_UPDATE = new SqlRuleCondition.And(List.of(
            new SqlRuleCondition.QueryTypeIn(Set.of(QueryType.UPDATE)),
            new SqlRuleCondition.HasWhereClause(false)));

    private final SqlReviewCustomRuleRepository customRuleRepository = mock(SqlReviewCustomRuleRepository.class);
    private final SqlReviewRuleConfigRepository ruleConfigRepository = mock(SqlReviewRuleConfigRepository.class);
    private final SqlParserService sqlParserService = mock(SqlParserService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final StaticMessageSource messages = messages();
    private final DefaultSqlReviewCustomRuleService service = new DefaultSqlReviewCustomRuleService(
            customRuleRepository, ruleConfigRepository, SqlRuleSources.codec(messages),
            new SqlRuleConditionValidator(messages), sqlParserService, eventPublisher, messages);

    private final UUID organizationId = UUID.randomUUID();

    private static StaticMessageSource messages() {
        var ms = new StaticMessageSource();
        ms.setUseCodeAsDefaultMessage(true);
        return ms;
    }

    private static SqlReviewCustomRuleCommand command(String ruleId, SqlRuleCondition condition) {
        return new SqlReviewCustomRuleCommand(ruleId, " Unbounded update ", "  ", " {statement_type} on {tables} ",
                SqlRuleCategory.STATEMENT_SAFETY, SqlReviewSeverity.BLOCK, null, condition);
    }

    private SqlReviewCustomRuleEntity stored(String ruleId) {
        var entity = new SqlReviewCustomRuleEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organizationId);
        entity.setRuleId(ruleId);
        entity.setName("old");
        entity.setMessage("old");
        entity.setCategory(SqlRuleCategory.PERFORMANCE);
        entity.setDefaultSeverity(SqlReviewSeverity.WARN);
        entity.setCondition(SqlRuleSources.codec(messages).encode(new SqlRuleCondition.HasLimitClause(false)));
        return entity;
    }

    private void saveReturnsArgument() {
        when(customRuleRepository.saveAndFlush(any(SqlReviewCustomRuleEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void verifyChangedEventPublished() {
        var captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new SqlReviewCustomRuleChangedEvent(organizationId));
    }

    @Test
    void createNormalisesStoresTheEncodedConditionAndPublishes() {
        saveReturnsArgument();

        var view = service.create(organizationId, command("custom_unbounded_update", NO_WHERE_UPDATE));

        var captor = ArgumentCaptor.forClass(SqlReviewCustomRuleEntity.class);
        verify(customRuleRepository).saveAndFlush(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getOrganizationId()).isEqualTo(organizationId);
        assertThat(saved.getName()).isEqualTo("Unbounded update");
        assertThat(saved.getDescription()).isNull();
        assertThat(saved.getMessage()).isEqualTo("{statement_type} on {tables}");
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getCondition()).contains("\"type\":\"and\"");
        assertThat(view.ruleId()).isEqualTo("custom_unbounded_update");
        assertThat(view.condition()).isEqualTo(NO_WHERE_UPDATE);
        verifyChangedEventPublished();
    }

    @Test
    void createRejectsAnInvalidRuleIdBeforeTouchingTheDatabase() {
        assertThatThrownBy(() -> service.create(organizationId, command("custom_A", NO_WHERE_UPDATE)))
                .isInstanceOf(IllegalSqlReviewCustomRuleException.class)
                .hasMessage("error.sql_review_rule_id_invalid");
        assertThatThrownBy(() -> service.create(organizationId, command(null, NO_WHERE_UPDATE)))
                .isInstanceOf(IllegalSqlReviewCustomRuleException.class);
        verifyNoInteractions(customRuleRepository, eventPublisher);
    }

    @Test
    void createRejectsAMissingNameOrCategoryOrSeverity() {
        var noName = new SqlReviewCustomRuleCommand("custom_abc", " ", null, "m", SqlRuleCategory.PERFORMANCE,
                SqlReviewSeverity.WARN, true, NO_WHERE_UPDATE);
        var noCategory = new SqlReviewCustomRuleCommand("custom_abc", "n", null, "m", null,
                SqlReviewSeverity.WARN, true, NO_WHERE_UPDATE);
        var noSeverity = new SqlReviewCustomRuleCommand("custom_abc", "n", null, "m", SqlRuleCategory.PERFORMANCE,
                null, true, NO_WHERE_UPDATE);

        assertThatThrownBy(() -> service.create(organizationId, noName)).hasMessage("error.sql_review_rule_name_required");
        assertThatThrownBy(() -> service.create(organizationId, noCategory))
                .hasMessage("error.sql_review_rule_category_severity_required");
        assertThatThrownBy(() -> service.create(organizationId, noSeverity))
                .hasMessage("error.sql_review_rule_category_severity_required");
    }

    @Test
    void createPropagatesConditionValidatorFailures() {
        var empty = new SqlRuleCondition.FunctionCalled(List.of());

        assertThatThrownBy(() -> service.create(organizationId, command("custom_abc", empty)))
                .isInstanceOf(IllegalSqlReviewCustomRuleException.class)
                .hasMessage("error.sql_review_rule_condition_empty_list");
        verify(customRuleRepository, never()).saveAndFlush(any());
    }

    @Test
    void createEnforcesTheFiftyRuleCap() {
        when(customRuleRepository.countByOrganizationId(organizationId))
                .thenReturn((long) SqlReviewCustomRuleService.MAX_RULES_PER_ORGANIZATION);

        assertThatThrownBy(() -> service.create(organizationId, command("custom_abc", NO_WHERE_UPDATE)))
                .isInstanceOf(IllegalSqlReviewCustomRuleException.class)
                .hasMessage("error.sql_review_rule_limit_reached");
        verify(customRuleRepository, never()).saveAndFlush(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void createBelowTheCapSucceeds() {
        when(customRuleRepository.countByOrganizationId(organizationId))
                .thenReturn((long) SqlReviewCustomRuleService.MAX_RULES_PER_ORGANIZATION - 1);
        saveReturnsArgument();

        assertThat(service.create(organizationId, command("custom_abc", NO_WHERE_UPDATE)).ruleId())
                .isEqualTo("custom_abc");
    }

    @Test
    void createPreChecksADuplicateRuleId() {
        when(customRuleRepository.existsByOrganizationIdAndRuleId(organizationId, "custom_abc")).thenReturn(true);

        assertThatThrownBy(() -> service.create(organizationId, command("custom_abc", NO_WHERE_UPDATE)))
                .isInstanceOfSatisfying(SqlReviewCustomRuleConflictException.class,
                        ex -> assertThat(ex.ruleId()).isEqualTo("custom_abc"));
        verify(customRuleRepository, never()).saveAndFlush(any());
    }

    @Test
    void aRacedUniqueViolationIsTheSameConflict() {
        when(customRuleRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq"));

        assertThatThrownBy(() -> service.create(organizationId, command("custom_abc", NO_WHERE_UPDATE)))
                .isInstanceOf(SqlReviewCustomRuleConflictException.class);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void listAndGetAreOrganizationScoped() {
        var entity = stored("custom_abc");
        when(customRuleRepository.findAllByOrganizationIdOrderByRuleIdAsc(organizationId)).thenReturn(List.of(entity));
        when(customRuleRepository.findByIdAndOrganizationId(entity.getId(), organizationId))
                .thenReturn(Optional.of(entity));

        assertThat(service.list(organizationId)).singleElement()
                .satisfies(view -> assertThat(view.condition()).isEqualTo(new SqlRuleCondition.HasLimitClause(false)));
        assertThat(service.get(organizationId, entity.getId()).ruleId()).isEqualTo("custom_abc");
        var foreign = UUID.randomUUID();
        when(customRuleRepository.findByIdAndOrganizationId(foreign, organizationId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(organizationId, foreign))
                .isInstanceOfSatisfying(SqlReviewCustomRuleNotFoundException.class,
                        ex -> assertThat(ex.id()).isEqualTo(foreign));
    }

    @Test
    void updateReplacesEveryFieldAndPublishes() {
        var entity = stored("custom_abc");
        when(customRuleRepository.findByIdAndOrganizationId(entity.getId(), organizationId))
                .thenReturn(Optional.of(entity));
        saveReturnsArgument();
        var disabled = new SqlReviewCustomRuleCommand("custom_abc", "New", "About", "msg",
                SqlRuleCategory.DATA_PROTECTION, SqlReviewSeverity.OFF, false, NO_WHERE_UPDATE);

        var view = service.update(organizationId, entity.getId(), disabled);

        assertThat(view.name()).isEqualTo("New");
        assertThat(view.description()).isEqualTo("About");
        assertThat(view.category()).isEqualTo(SqlRuleCategory.DATA_PROTECTION);
        assertThat(view.defaultSeverity()).isEqualTo(SqlReviewSeverity.OFF);
        assertThat(view.enabled()).isFalse();
        assertThat(view.condition()).isEqualTo(NO_WHERE_UPDATE);
        verifyChangedEventPublished();
    }

    @Test
    void updateRefusesAChangedRuleId() {
        var entity = stored("custom_abc");
        when(customRuleRepository.findByIdAndOrganizationId(entity.getId(), organizationId))
                .thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.update(organizationId, entity.getId(), command("custom_xyz", NO_WHERE_UPDATE)))
                .isInstanceOf(IllegalSqlReviewCustomRuleException.class)
                .hasMessage("error.sql_review_rule_id_immutable");
        assertThat(entity.getRuleId()).isEqualTo("custom_abc");
        verify(customRuleRepository, never()).saveAndFlush(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void updateOfAMissingRuleIsNotFound() {
        var id = UUID.randomUUID();
        when(customRuleRepository.findByIdAndOrganizationId(id, organizationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(organizationId, id, command("custom_abc", NO_WHERE_UPDATE)))
                .isInstanceOf(SqlReviewCustomRuleNotFoundException.class);
    }

    @Test
    void deleteRemovesTheRulesetConfigsTheRowAndPublishes() {
        var entity = stored("custom_abc");
        when(customRuleRepository.findByIdAndOrganizationId(entity.getId(), organizationId))
                .thenReturn(Optional.of(entity));

        service.delete(organizationId, entity.getId());

        verify(ruleConfigRepository).deleteAllByOrganizationIdAndRuleId(organizationId, "custom_abc");
        verify(customRuleRepository).delete(entity);
        verifyChangedEventPublished();
    }

    @Test
    void deleteOfAMissingRuleIsNotFoundAndTouchesNoConfig() {
        var id = UUID.randomUUID();
        when(customRuleRepository.findByIdAndOrganizationId(id, organizationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(organizationId, id))
                .isInstanceOf(SqlReviewCustomRuleNotFoundException.class);
        verifyNoInteractions(ruleConfigRepository, eventPublisher);
    }

    @Test
    void testEvaluatesTheDraftAtItsDefaultSeverityAndPersistsNothing() {
        var sql = "UPDATE billing.invoices SET paid = true";
        when(sqlParserService.parse(sql)).thenReturn(new SqlParseResult(QueryType.UPDATE, sql));

        var result = service.test(command("custom_unbounded_update", NO_WHERE_UPDATE), sql, null);

        assertThat(result.applicable()).isTrue();
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.ruleId()).isEqualTo("custom_unbounded_update");
            assertThat(finding.severity()).isEqualTo(SqlReviewSeverity.BLOCK);
            assertThat(finding.args()).containsEntry("message", "UPDATE on billing.invoices");
        });
        verifyNoInteractions(customRuleRepository, ruleConfigRepository, eventPublisher);
    }

    @Test
    void testRunsAnOffDraftAtWarnSoItsConditionCanStillBeChecked() {
        var sql = "UPDATE t SET a = 1";
        when(sqlParserService.parse(sql)).thenReturn(new SqlParseResult(QueryType.UPDATE, sql));
        var off = new SqlReviewCustomRuleCommand("custom_abc", "n", null, "m", SqlRuleCategory.PERFORMANCE,
                SqlReviewSeverity.OFF, true, NO_WHERE_UPDATE);

        assertThat(service.test(off, sql, null).findings()).singleElement()
                .satisfies(finding -> assertThat(finding.severity()).isEqualTo(SqlReviewSeverity.WARN));
    }

    @Test
    void testOfANonMatchingStatementIsClean() {
        var sql = "UPDATE t SET a = 1 WHERE id = 2";
        when(sqlParserService.parse(sql)).thenReturn(new SqlParseResult(QueryType.UPDATE, sql));

        assertThat(service.test(command("custom_abc", NO_WHERE_UPDATE), sql, DbType.MYSQL).findings()).isEmpty();
    }

    @Test
    void testRefusesANonRelationalDialectAndAMalformedDraft() {
        assertThatThrownBy(() -> service.test(command("custom_abc", NO_WHERE_UPDATE), "SELECT 1", DbType.MONGODB))
                .isInstanceOf(IllegalSqlReviewCustomRuleException.class)
                .hasMessage("error.sql_review_rule_dialect_unsupported");
        assertThatThrownBy(() -> service.test(command("custom_abc", null), "SELECT 1", null))
                .isInstanceOf(IllegalSqlReviewCustomRuleException.class)
                .hasMessage("error.sql_review_rule_condition_required");
        verifyNoInteractions(sqlParserService, customRuleRepository, eventPublisher);
    }
}
