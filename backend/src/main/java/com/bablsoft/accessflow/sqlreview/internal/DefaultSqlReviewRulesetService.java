package com.bablsoft.accessflow.sqlreview.internal;

import com.bablsoft.accessflow.core.api.DatasourceEnvironment;
import com.bablsoft.accessflow.sqlreview.api.CreateSqlReviewRulesetCommand;
import com.bablsoft.accessflow.sqlreview.api.IllegalSqlReviewRulesetException;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewRuleConfigView;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewRulesetConflictException;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewRulesetNotFoundException;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewRulesetService;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewRulesetView;
import com.bablsoft.accessflow.sqlreview.api.UpdateSqlReviewRulesetCommand;
import com.bablsoft.accessflow.sqlreview.internal.persistence.entity.SqlReviewRuleConfigEntity;
import com.bablsoft.accessflow.sqlreview.internal.persistence.entity.SqlReviewRulesetEntity;
import com.bablsoft.accessflow.sqlreview.internal.persistence.repo.SqlReviewRuleConfigRepository;
import com.bablsoft.accessflow.sqlreview.internal.persistence.repo.SqlReviewRulesetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Admin CRUD over an organization's SQL review rulesets (#863).
 *
 * <p>Uniqueness — one ruleset per environment per organization plus at most one organization-wide
 * default — is pre-checked so the common case is a clean 409, and the {@code saveAndFlush} is
 * wrapped so a concurrent writer that slips past the pre-check surfaces as the same
 * {@link SqlReviewRulesetConflictException} rather than a raw constraint violation. On update the
 * pre-check runs <em>before</em> the managed entity is touched: Hibernate auto-flushes a dirty
 * entity ahead of the pre-check query, which would otherwise raise the index violation outside the
 * guarded flush.
 *
 * <p>Rule configs are validated by {@link SqlRuleParamsValidator} before anything is written, and a
 * non-null {@code rules} list on update replaces the config set wholesale — bulk delete first, then
 * insert, because the reverse order would flush the new rows straight into the delete.
 */
@Service
@Transactional
public class DefaultSqlReviewRulesetService implements SqlReviewRulesetService {

    private static final Logger log = LoggerFactory.getLogger(DefaultSqlReviewRulesetService.class);

    private final SqlReviewRulesetRepository rulesetRepository;
    private final SqlReviewRuleConfigRepository ruleConfigRepository;
    private final SqlRuleParamsValidator paramsValidator;
    private final SqlRuleParamsCodec paramsCodec;

    public DefaultSqlReviewRulesetService(SqlReviewRulesetRepository rulesetRepository,
                                          SqlReviewRuleConfigRepository ruleConfigRepository,
                                          SqlRuleParamsValidator paramsValidator,
                                          SqlRuleParamsCodec paramsCodec) {
        this.rulesetRepository = rulesetRepository;
        this.ruleConfigRepository = ruleConfigRepository;
        this.paramsValidator = paramsValidator;
        this.paramsCodec = paramsCodec;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SqlReviewRulesetView> list(UUID organizationId) {
        return rulesetRepository.findAllByOrganizationIdOrderByNameAsc(organizationId).stream()
                .map(this::toView)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public SqlReviewRulesetView get(UUID organizationId, UUID rulesetId) {
        return toView(load(organizationId, rulesetId));
    }

    @Override
    public SqlReviewRulesetView create(UUID organizationId, CreateSqlReviewRulesetCommand command) {
        paramsValidator.validate(command.rules());
        requireSlotFree(organizationId, command.environment(), null);

        var entity = new SqlReviewRulesetEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organizationId);
        entity.setName(command.name().trim());
        entity.setDescription(blankToNull(command.description()));
        entity.setEnvironment(command.environment());
        entity.setEnabled(command.enabled() == null || command.enabled());
        var saved = flushOrConflict(entity);
        writeRules(saved, command.rules());
        return toView(saved);
    }

    @Override
    public SqlReviewRulesetView update(UUID organizationId, UUID rulesetId, UpdateSqlReviewRulesetCommand command) {
        var entity = load(organizationId, rulesetId);
        paramsValidator.validate(command.rules());
        var environment = effectiveEnvironment(entity, command);
        if (environment != entity.getEnvironment()) {
            requireSlotFree(organizationId, environment, entity.getId());
        }

        if (command.name() != null) {
            entity.setName(command.name().trim());
        }
        if (command.description() != null) {
            entity.setDescription(blankToNull(command.description()));
        }
        entity.setEnvironment(environment);
        if (command.enabled() != null) {
            entity.setEnabled(command.enabled());
        }
        var saved = flushOrConflict(entity);
        if (command.rules() != null) {
            ruleConfigRepository.deleteAllByRulesetId(saved.getId());
            writeRules(saved, command.rules());
        }
        return toView(saved);
    }

    @Override
    public void delete(UUID organizationId, UUID rulesetId) {
        var entity = load(organizationId, rulesetId);
        ruleConfigRepository.deleteAllByRulesetId(entity.getId());
        rulesetRepository.delete(entity);
    }

    private SqlReviewRulesetEntity load(UUID organizationId, UUID rulesetId) {
        return rulesetRepository.findByIdAndOrganizationId(rulesetId, organizationId)
                .orElseThrow(() -> new SqlReviewRulesetNotFoundException(rulesetId));
    }

    /** A non-null environment wins; else {@code clearEnvironment} makes it the default; else unchanged. */
    private static DatasourceEnvironment effectiveEnvironment(SqlReviewRulesetEntity entity,
                                                              UpdateSqlReviewRulesetCommand command) {
        if (command.environment() != null) {
            return command.environment();
        }
        return Boolean.TRUE.equals(command.clearEnvironment()) ? null : entity.getEnvironment();
    }

    private void requireSlotFree(UUID organizationId, DatasourceEnvironment environment, UUID excludedId) {
        Optional<SqlReviewRulesetEntity> holder = environment == null
                ? rulesetRepository.findByOrganizationIdAndEnvironmentIsNull(organizationId)
                : rulesetRepository.findByOrganizationIdAndEnvironment(organizationId, environment);
        if (holder.isPresent() && !holder.get().getId().equals(excludedId)) {
            throw new SqlReviewRulesetConflictException(environment);
        }
    }

    private SqlReviewRulesetEntity flushOrConflict(SqlReviewRulesetEntity entity) {
        try {
            return rulesetRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            // A concurrent writer took the slot between the pre-check and the flush. The transaction
            // is already rollback-only; rethrowing keeps the HTTP contract (409) intact.
            throw new SqlReviewRulesetConflictException(entity.getEnvironment());
        }
    }

    private void writeRules(SqlReviewRulesetEntity ruleset, List<SqlReviewRuleConfigView> rules) {
        if (rules.isEmpty()) {
            return;
        }
        var rows = rules.stream().map(config -> {
            var row = new SqlReviewRuleConfigEntity();
            row.setId(UUID.randomUUID());
            row.setRuleset(ruleset);
            row.setRuleId(config.ruleId());
            row.setSeverity(config.severity());
            row.setParams(paramsCodec.encode(config.params()));
            return row;
        }).toList();
        ruleConfigRepository.saveAllAndFlush(rows);
    }

    private SqlReviewRulesetView toView(SqlReviewRulesetEntity entity) {
        var rules = ruleConfigRepository.findAllByRuleset_IdOrderByRuleIdAsc(entity.getId()).stream()
                .map(row -> new SqlReviewRuleConfigView(row.getRuleId(), row.getSeverity(), decodeParams(entity, row)))
                .toList();
        return new SqlReviewRulesetView(entity.getId(), entity.getOrganizationId(), entity.getName(),
                entity.getDescription(), entity.getEnvironment(), entity.isEnabled(), rules,
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    /**
     * The validator stops a malformed {@code params} row being written through the API; a row
     * written any other way must not make the whole organization's ruleset list unreadable, so it
     * is shown with no params (and logged), exactly as the evaluation path degrades it.
     */
    private Map<String, List<String>> decodeParams(SqlReviewRulesetEntity ruleset, SqlReviewRuleConfigEntity row) {
        try {
            return paramsCodec.decode(row.getParams());
        } catch (IllegalSqlReviewRulesetException ex) {
            log.warn("SQL review ruleset {} has undecodable params for rule {}; showing it without params",
                    ruleset.getId(), row.getRuleId());
            return Map.of();
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
