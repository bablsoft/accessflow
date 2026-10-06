package com.bablsoft.accessflow.sqlreview.internal;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.proxy.api.SqlParserService;
import com.bablsoft.accessflow.sqlreview.api.IllegalSqlReviewCustomRuleException;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewCustomRuleCommand;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewCustomRuleConflictException;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewCustomRuleNotFoundException;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewCustomRuleService;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewCustomRuleView;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewResult;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.events.SqlReviewCustomRuleChangedEvent;
import com.bablsoft.accessflow.sqlreview.internal.persistence.entity.SqlReviewCustomRuleEntity;
import com.bablsoft.accessflow.sqlreview.internal.persistence.repo.SqlReviewCustomRuleRepository;
import com.bablsoft.accessflow.sqlreview.internal.persistence.repo.SqlReviewRuleConfigRepository;
import com.bablsoft.accessflow.sqlreview.internal.rules.condition.CustomSqlRule;
import com.bablsoft.accessflow.sqlreview.internal.rules.condition.SqlRuleConditionCodec;
import com.bablsoft.accessflow.sqlreview.internal.rules.condition.SqlRuleConditionValidator;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Admin CRUD over an organization's custom SQL review rules and the draft test run (#1010).
 *
 * <p>The rule id is checked against the same pattern as the {@code V201} CHECK so a bad id is a
 * localized 422, not a constraint violation. Uniqueness is pre-checked and the {@code saveAndFlush}
 * is wrapped so a concurrent create that slips past the pre-check surfaces as the same 409. The
 * 50-rule cap is a count before insert: two racing creates at 49 can both land, which is accepted —
 * the cap bounds evaluation cost, it is not a security boundary.
 *
 * <p>Every write publishes {@link SqlReviewCustomRuleChangedEvent}, which {@link SqlRuleSource}
 * consumes after commit to evict the organization's cached rules. {@link #test} builds a transient
 * {@link CustomSqlRule} and runs the pure evaluator (an {@code OFF} draft at {@code WARN}) — no
 * repository, no event, no audit.
 */
@Service
@Transactional
public class DefaultSqlReviewCustomRuleService implements SqlReviewCustomRuleService {

    static final Pattern RULE_ID = Pattern.compile("^custom_[a-z][a-z0-9_]{2,60}$");

    private final SqlReviewCustomRuleRepository customRuleRepository;
    private final SqlReviewRuleConfigRepository ruleConfigRepository;
    private final SqlRuleConditionCodec conditionCodec;
    private final SqlRuleConditionValidator conditionValidator;
    private final SqlParserService sqlParserService;
    private final ApplicationEventPublisher eventPublisher;
    private final MessageSource messageSource;
    private final SqlReviewEvaluator evaluator = new SqlReviewEvaluator();

    public DefaultSqlReviewCustomRuleService(SqlReviewCustomRuleRepository customRuleRepository,
                                             SqlReviewRuleConfigRepository ruleConfigRepository,
                                             SqlRuleConditionCodec conditionCodec,
                                             SqlRuleConditionValidator conditionValidator,
                                             SqlParserService sqlParserService,
                                             ApplicationEventPublisher eventPublisher,
                                             MessageSource messageSource) {
        this.customRuleRepository = customRuleRepository;
        this.ruleConfigRepository = ruleConfigRepository;
        this.conditionCodec = conditionCodec;
        this.conditionValidator = conditionValidator;
        this.sqlParserService = sqlParserService;
        this.eventPublisher = eventPublisher;
        this.messageSource = messageSource;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SqlReviewCustomRuleView> list(UUID organizationId) {
        return customRuleRepository.findAllByOrganizationIdOrderByRuleIdAsc(organizationId).stream()
                .map(this::toView)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public SqlReviewCustomRuleView get(UUID organizationId, UUID id) {
        return toView(load(organizationId, id));
    }

    @Override
    public SqlReviewCustomRuleView create(UUID organizationId, SqlReviewCustomRuleCommand command) {
        validate(command);
        if (customRuleRepository.countByOrganizationId(organizationId) >= MAX_RULES_PER_ORGANIZATION) {
            throw fail("error.sql_review_rule_limit_reached", MAX_RULES_PER_ORGANIZATION);
        }
        if (customRuleRepository.existsByOrganizationIdAndRuleId(organizationId, command.ruleId())) {
            throw new SqlReviewCustomRuleConflictException(command.ruleId());
        }
        var entity = new SqlReviewCustomRuleEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organizationId);
        entity.setRuleId(command.ruleId());
        apply(entity, command);
        var saved = flushOrConflict(entity);
        eventPublisher.publishEvent(new SqlReviewCustomRuleChangedEvent(organizationId));
        return toView(saved);
    }

    @Override
    public SqlReviewCustomRuleView update(UUID organizationId, UUID id, SqlReviewCustomRuleCommand command) {
        var entity = load(organizationId, id);
        if (!entity.getRuleId().equals(command.ruleId())) {
            throw fail("error.sql_review_rule_id_immutable", entity.getRuleId());
        }
        validate(command);
        apply(entity, command);
        var saved = customRuleRepository.saveAndFlush(entity);
        eventPublisher.publishEvent(new SqlReviewCustomRuleChangedEvent(organizationId));
        return toView(saved);
    }

    @Override
    public void delete(UUID organizationId, UUID id) {
        var entity = load(organizationId, id);
        ruleConfigRepository.deleteAllByOrganizationIdAndRuleId(organizationId, entity.getRuleId());
        customRuleRepository.delete(entity);
        eventPublisher.publishEvent(new SqlReviewCustomRuleChangedEvent(organizationId));
    }

    @Override
    @Transactional(readOnly = true)
    public SqlReviewResult test(SqlReviewCustomRuleCommand draft, String sql, DbType dialect) {
        validate(draft);
        var dbType = dialect == null ? DbType.POSTGRESQL : dialect;
        if (!DefaultSqlReviewService.RELATIONAL_DIALECTS.contains(dbType)) {
            throw fail("error.sql_review_rule_dialect_unsupported", dbType.name());
        }
        var rule = new CustomSqlRule(draft.ruleId(), draft.name().trim(), blankToNull(draft.description()),
                draft.category(), draft.defaultSeverity(), draft.message().trim(), draft.condition());
        var statements = SqlStatementParser.parse(sqlParserService.parse(sql));
        // The evaluator skips OFF rules; a draft saved OFF (to be switched on per ruleset) must still
        // show whether its condition matches, so the test run treats OFF as WARN.
        var severity = draft.defaultSeverity() == SqlReviewSeverity.OFF ? SqlReviewSeverity.WARN : draft.defaultSeverity();
        return evaluator.evaluate(dbType, statements, List.of(new ResolvedRule(rule, severity, Map.of())));
    }

    private void validate(SqlReviewCustomRuleCommand command) {
        if (command.ruleId() == null || !RULE_ID.matcher(command.ruleId()).matches()) {
            throw fail("error.sql_review_rule_id_invalid", String.valueOf(command.ruleId()));
        }
        if (command.name() == null || command.name().isBlank()) {
            throw fail("error.sql_review_rule_name_required");
        }
        if (command.category() == null || command.defaultSeverity() == null) {
            throw fail("error.sql_review_rule_category_severity_required");
        }
        conditionValidator.validate(command.condition(), command.message());
    }

    private void apply(SqlReviewCustomRuleEntity entity, SqlReviewCustomRuleCommand command) {
        entity.setName(command.name().trim());
        entity.setDescription(blankToNull(command.description()));
        entity.setMessage(command.message().trim());
        entity.setCategory(command.category());
        entity.setDefaultSeverity(command.defaultSeverity());
        entity.setEnabled(command.enabled() == null || command.enabled());
        entity.setCondition(conditionCodec.encode(command.condition()));
    }

    private SqlReviewCustomRuleEntity load(UUID organizationId, UUID id) {
        return customRuleRepository.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(() -> new SqlReviewCustomRuleNotFoundException(id));
    }

    private SqlReviewCustomRuleEntity flushOrConflict(SqlReviewCustomRuleEntity entity) {
        try {
            return customRuleRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            // A concurrent create took the rule id between the pre-check and the flush.
            throw new SqlReviewCustomRuleConflictException(entity.getRuleId());
        }
    }

    private SqlReviewCustomRuleView toView(SqlReviewCustomRuleEntity entity) {
        return new SqlReviewCustomRuleView(entity.getId(), entity.getOrganizationId(), entity.getRuleId(),
                entity.getName(), entity.getDescription(), entity.getMessage(), entity.getCategory(),
                entity.getDefaultSeverity(), entity.isEnabled(), conditionCodec.decode(entity.getCondition()),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private IllegalSqlReviewCustomRuleException fail(String key, Object... args) {
        return new IllegalSqlReviewCustomRuleException(
                messageSource.getMessage(key, args, LocaleContextHolder.getLocale()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
