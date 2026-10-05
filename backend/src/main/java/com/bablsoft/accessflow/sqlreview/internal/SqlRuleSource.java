package com.bablsoft.accessflow.sqlreview.internal;

import com.bablsoft.accessflow.sqlreview.api.IllegalSqlReviewCustomRuleException;
import com.bablsoft.accessflow.sqlreview.events.SqlReviewCustomRuleChangedEvent;
import com.bablsoft.accessflow.sqlreview.internal.config.SqlReviewProperties;
import com.bablsoft.accessflow.sqlreview.internal.persistence.entity.SqlReviewCustomRuleEntity;
import com.bablsoft.accessflow.sqlreview.internal.persistence.repo.SqlReviewCustomRuleRepository;
import com.bablsoft.accessflow.sqlreview.internal.rules.SqlRule;
import com.bablsoft.accessflow.sqlreview.internal.rules.SqlRuleCatalog;
import com.bablsoft.accessflow.sqlreview.internal.rules.condition.CustomSqlRule;
import com.bablsoft.accessflow.sqlreview.internal.rules.condition.SqlRuleConditionCodec;
import com.bablsoft.accessflow.sqlreview.internal.rules.condition.SqlRuleConditionValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The organization-scoped rule catalog (#1009): the built-ins of {@link SqlRuleCatalog} followed by
 * the organization's enabled custom rules in rule-id order. Every consumer that used to read
 * {@link SqlRuleCatalog} directly — evaluation, the catalog endpoint, the params validator — reads
 * this instead, so a stored custom rule is indistinguishable from a built-in downstream.
 *
 * <p>Custom rules are cached per organization, evicted after commit on
 * {@link SqlReviewCustomRuleChangedEvent} and otherwise re-read after
 * {@link SqlReviewProperties#customRuleCacheTtl()} — the event is JVM-local, so the TTL bounds how
 * long another replica keeps the old rules. A stored row whose condition no longer decodes or
 * validates is logged and skipped: a data problem never makes a query harder to approve.
 */
@Component
public class SqlRuleSource {

    private static final Logger log = LoggerFactory.getLogger(SqlRuleSource.class);

    private final SqlRuleCatalog catalog;
    private final SqlReviewCustomRuleRepository customRuleRepository;
    private final SqlRuleConditionCodec conditionCodec;
    private final SqlRuleConditionValidator conditionValidator;
    private final Clock clock;
    private final Duration ttl;
    private final Map<UUID, CachedRules> cache = new ConcurrentHashMap<>();
    /** Bumped on every eviction, so a load that raced one is never cached. */
    private final Map<UUID, AtomicLong> generations = new ConcurrentHashMap<>();

    public SqlRuleSource(SqlRuleCatalog catalog, SqlReviewCustomRuleRepository customRuleRepository,
                         SqlRuleConditionCodec conditionCodec, SqlRuleConditionValidator conditionValidator,
                         Clock clock, SqlReviewProperties properties) {
        this.catalog = catalog;
        this.customRuleRepository = customRuleRepository;
        this.conditionCodec = conditionCodec;
        this.conditionValidator = conditionValidator;
        this.clock = clock;
        this.ttl = properties.customRuleCacheTtl();
    }

    /** Built-ins in catalog order, then the organization's enabled custom rules by rule id. */
    public List<SqlRule> rules(UUID organizationId) {
        var custom = customRules(organizationId);
        var all = new ArrayList<SqlRule>(catalog.rules().size() + custom.size());
        all.addAll(catalog.rules());
        all.addAll(custom.values());
        return List.copyOf(all);
    }

    /** A built-in by id, else one of the organization's enabled custom rules. */
    public Optional<SqlRule> byId(UUID organizationId, String ruleId) {
        if (ruleId == null) {
            return Optional.empty();
        }
        if (!CustomSqlRule.isCustomId(ruleId)) {
            return catalog.byId(ruleId);
        }
        return Optional.ofNullable(customRules(organizationId).get(ruleId));
    }

    /**
     * Whether the organization has a custom rule {@code ruleId}, enabled or not. A ruleset may keep
     * configuring a disabled custom rule — disabling removes it from evaluation, not from rulesets.
     */
    public boolean customRuleExists(UUID organizationId, String ruleId) {
        return CustomSqlRule.isCustomId(ruleId)
                && customRuleRepository.existsByOrganizationIdAndRuleId(organizationId, ruleId);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onCustomRuleChanged(SqlReviewCustomRuleChangedEvent event) {
        generation(event.organizationId()).incrementAndGet();
        cache.remove(event.organizationId());
    }

    private Map<String, SqlRule> customRules(UUID organizationId) {
        var now = clock.instant();
        var cached = cache.get(organizationId);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.rules();
        }
        var generation = generation(organizationId);
        long before = generation.get();
        var loaded = new CachedRules(load(organizationId), now.plus(ttl));
        // An eviction that landed while the rows were read may have committed newer rules than the
        // ones just loaded: serve them to this caller, but never cache them.
        cache.compute(organizationId, (id, current) -> generation.get() == before ? loaded : current);
        return loaded.rules();
    }

    private AtomicLong generation(UUID organizationId) {
        return generations.computeIfAbsent(organizationId, id -> new AtomicLong());
    }

    private Map<String, SqlRule> load(UUID organizationId) {
        var rules = new LinkedHashMap<String, SqlRule>();
        for (SqlReviewCustomRuleEntity row
                : customRuleRepository.findAllByOrganizationIdAndEnabledTrueOrderByRuleIdAsc(organizationId)) {
            if (!row.isEnabled() || !CustomSqlRule.isCustomId(row.getRuleId())) {
                continue;
            }
            toRule(row).ifPresent(rule -> rules.put(rule.ruleId(), rule));
        }
        return Collections.unmodifiableMap(rules);
    }

    private Optional<SqlRule> toRule(SqlReviewCustomRuleEntity row) {
        try {
            var condition = conditionCodec.decode(row.getCondition());
            conditionValidator.validateCondition(condition);
            return Optional.of(new CustomSqlRule(row.getRuleId(), row.getName(), row.getDescription(),
                    row.getCategory(), row.getDefaultSeverity(), row.getMessage(), condition));
        } catch (IllegalSqlReviewCustomRuleException | IllegalArgumentException ex) {
            log.warn("SQL review custom rule {} of organization {} is malformed and is not evaluated: {}",
                    row.getRuleId(), row.getOrganizationId(), ex.getMessage());
            return Optional.empty();
        }
    }

    private record CachedRules(Map<String, SqlRule> rules, Instant expiresAt) {
    }
}
