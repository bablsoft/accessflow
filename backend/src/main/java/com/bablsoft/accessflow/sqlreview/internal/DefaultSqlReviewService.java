package com.bablsoft.accessflow.sqlreview.internal;

import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceEnvironment;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.proxy.api.SqlParserService;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewResult;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewService;
import com.bablsoft.accessflow.sqlreview.internal.persistence.entity.SqlReviewRuleConfigEntity;
import com.bablsoft.accessflow.sqlreview.internal.persistence.entity.SqlReviewRulesetEntity;
import com.bablsoft.accessflow.sqlreview.internal.persistence.repo.SqlReviewRuleConfigRepository;
import com.bablsoft.accessflow.sqlreview.internal.persistence.repo.SqlReviewRulesetRepository;
import com.bablsoft.accessflow.sqlreview.internal.rules.SqlRule;
import com.bablsoft.accessflow.sqlreview.internal.rules.SqlRuleCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves the ruleset for a datasource and evaluates it (#862).
 *
 * <p><b>Applicability.</b> The catalog is derived from the JSqlParser AST, so only the in-process
 * relational dialects are evaluated. Every engine-plugin {@link DbType} — including a value added
 * later — returns {@link SqlReviewResult#notApplicable()} before anything is parsed or loaded. It
 * never fails closed: an engine without rule support must not make its queries harder to approve.
 *
 * <p><b>Resolution.</b> datasource → its {@code environment} → the ruleset bound to it → else the
 * organization-wide default ({@code environment IS NULL}) → else no rules. The bound ruleset is
 * picked whether or not it is enabled; a disabled one resolves to <em>no rules</em> and does not
 * fall through to the default, so disabling the production ruleset never silently re-enables the
 * default on production. Every catalog rule is evaluated: at its config row's severity and params
 * when the ruleset has one, at its built-in default severity otherwise. {@code OFF} rules are
 * skipped by the evaluator.
 */
@Service
@Transactional(readOnly = true)
public class DefaultSqlReviewService implements SqlReviewService {

    /** The engines the rule catalog covers; everything else is an engine plugin. */
    static final Set<DbType> RELATIONAL_DIALECTS = EnumSet.of(
            DbType.POSTGRESQL, DbType.MYSQL, DbType.MARIADB, DbType.ORACLE, DbType.MSSQL, DbType.CUSTOM);

    private static final Logger log = LoggerFactory.getLogger(DefaultSqlReviewService.class);

    private final DatasourceAdminService datasourceAdminService;
    private final SqlParserService sqlParserService;
    private final SqlReviewRulesetRepository rulesetRepository;
    private final SqlReviewRuleConfigRepository ruleConfigRepository;
    private final SqlRuleCatalog catalog;
    private final SqlRuleParamsCodec paramsCodec;
    private final SqlReviewEvaluator evaluator;

    public DefaultSqlReviewService(DatasourceAdminService datasourceAdminService,
                                   SqlParserService sqlParserService,
                                   SqlReviewRulesetRepository rulesetRepository,
                                   SqlReviewRuleConfigRepository ruleConfigRepository,
                                   SqlRuleCatalog catalog,
                                   SqlRuleParamsCodec paramsCodec) {
        this.datasourceAdminService = datasourceAdminService;
        this.sqlParserService = sqlParserService;
        this.rulesetRepository = rulesetRepository;
        this.ruleConfigRepository = ruleConfigRepository;
        this.catalog = catalog;
        this.paramsCodec = paramsCodec;
        this.evaluator = new SqlReviewEvaluator();
    }

    @Override
    public SqlReviewResult evaluate(UUID organizationId, UUID datasourceId, String sql) {
        var datasource = datasourceAdminService.getForAdmin(datasourceId, organizationId);
        if (!RELATIONAL_DIALECTS.contains(datasource.dbType())) {
            return SqlReviewResult.notApplicable();
        }
        var statements = SqlStatementParser.parse(sqlParserService.parse(sql));
        var ruleset = resolveRuleset(organizationId, datasource.environment());
        if (ruleset.isEmpty() || !ruleset.get().isEnabled()) {
            return SqlReviewResult.clean();
        }
        return evaluator.evaluate(statements, resolveRules(ruleset.get()));
    }

    private Optional<SqlReviewRulesetEntity> resolveRuleset(UUID organizationId,
                                                            DatasourceEnvironment environment) {
        if (environment != null) {
            var bound = rulesetRepository.findByOrganizationIdAndEnvironment(organizationId, environment);
            if (bound.isPresent()) {
                return bound;
            }
        }
        return rulesetRepository.findByOrganizationIdAndEnvironmentIsNull(organizationId);
    }

    private List<ResolvedRule> resolveRules(SqlReviewRulesetEntity ruleset) {
        var configured = new HashMap<String, SqlReviewRuleConfigEntity>();
        for (SqlReviewRuleConfigEntity config : ruleConfigRepository.findAllByRuleset_IdOrderByRuleIdAsc(ruleset.getId())) {
            if (catalog.byId(config.getRuleId()).isEmpty()) {
                log.warn("SQL review ruleset {} configures unknown rule {}; ignoring it",
                        ruleset.getId(), config.getRuleId());
                continue;
            }
            configured.put(config.getRuleId(), config);
        }
        var resolved = new ArrayList<ResolvedRule>(catalog.rules().size());
        for (SqlRule rule : catalog.rules()) {
            var config = configured.get(rule.ruleId());
            resolved.add(config == null
                    ? ResolvedRule.defaults(rule)
                    : new ResolvedRule(rule, config.getSeverity(), paramsCodec.decode(config.getParams())));
        }
        return resolved;
    }
}
