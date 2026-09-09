package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.DriverResolutionException;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.QueryEngineCatalog;
import com.bablsoft.accessflow.core.api.QueryEngineRowSecurityRequest;
import com.bablsoft.accessflow.core.api.RowSecurityClassification;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.UnrewritableRowSecurityException;
import com.bablsoft.accessflow.proxy.api.RowSecurityClassificationService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Classifies row security without touching the datasource, dispatching exactly as
 * {@code DefaultQueryExecutor} does: engine-managed dialects answer through their plugin, the
 * relational ones through {@link RowSecurityRewriter} — the same pure JSqlParser rewrite that
 * governs real execution, so a classification cannot predict something execution would not do.
 */
@Service
@RequiredArgsConstructor
class DefaultRowSecurityClassificationService implements RowSecurityClassificationService {

    private static final String RELATIONAL_ENGINE_ID = "jdbc";

    private static final Logger log =
            LoggerFactory.getLogger(DefaultRowSecurityClassificationService.class);

    private final QueryEngineCatalog engineCatalog;
    private final RowSecurityRewriter rowSecurityRewriter;
    private final MessageSource messageSource;

    @Override
    public RowSecurityClassification classify(UUID datasourceId, DbType dbType, String sql,
                                              List<RowSecurityDirective> directives) {
        var applicable = directives == null ? List.<RowSecurityDirective>of() : directives;
        if (applicable.isEmpty()) {
            return RowSecurityClassification.notApplicable(engineId(dbType));
        }
        if (engineCatalog.isEngineManaged(dbType)) {
            return classifyThroughEngine(datasourceId, dbType, sql, applicable);
        }
        return classifyRelational(sql, applicable);
    }

    private RowSecurityClassification classifyThroughEngine(UUID datasourceId, DbType dbType,
                                                            String sql,
                                                            List<RowSecurityDirective> directives) {
        try {
            return engineCatalog.engineFor(dbType).classifyRowSecurity(
                    new QueryEngineRowSecurityRequest(datasourceId, sql, directives));
        } catch (DriverResolutionException ex) {
            // The plugin JAR is not resolvable right now (offline host, uncached jar, checksum
            // mismatch). A simulation must degrade, never 500 — and never claim "no impact".
            log.warn("Row-security classification unavailable for {}: {}", dbType, ex.getMessage());
            return RowSecurityClassification.unknown(engineId(dbType),
                    msg("error.policy_simulation.engine_unavailable"));
        } catch (RuntimeException ex) {
            // A plugin runs third-party parsers inside its own shaded classloader. One row that
            // trips an unexpected failure there must degrade to "cannot tell", not abort a
            // 5 000-row simulation with a 500. Deliberate per-row carve-out, as with a scheduled
            // job's per-row guard.
            log.warn("Row-security classification failed for datasource {} on {}", datasourceId,
                    dbType, ex);
            return RowSecurityClassification.unknown(engineId(dbType),
                    msg("error.policy_simulation.engine_unavailable"));
        }
    }

    private RowSecurityClassification classifyRelational(String sql,
                                                          List<RowSecurityDirective> directives) {
        try {
            var rewrite = rowSecurityRewriter.rewrite(sql, directives);
            var applied = rewrite.appliedPolicyIds();
            if (applied.isEmpty()) {
                return RowSecurityClassification.notApplicable(RELATIONAL_ENGINE_ID);
            }
            return deniesEverything(directives, applied)
                    ? RowSecurityClassification.denyAll(RELATIONAL_ENGINE_ID, applied)
                    : RowSecurityClassification.applied(RELATIONAL_ENGINE_ID, applied);
        } catch (UnrewritableRowSecurityException ex) {
            // The message is already localized at the rewriter's throw site — it is the very text
            // the submitter would have seen as a 422.
            return RowSecurityClassification.failClosed(RELATIONAL_ENGINE_ID, ex.getMessage());
        } catch (InvalidSqlException ex) {
            // A stored query that no longer parses cannot be classified.
            return RowSecurityClassification.unknown(RELATIONAL_ENGINE_ID, ex.getMessage());
        }
    }

    /**
     * True when a directive that actually took effect resolved to no values — the rewriter turns
     * that into an always-false predicate, so the submitter would see nothing. {@code IS_NULL} is
     * unary and is excluded: it carries no values by design.
     */
    private static boolean deniesEverything(List<RowSecurityDirective> directives,
                                            Set<UUID> appliedPolicyIds) {
        for (var directive : directives) {
            if (appliedPolicyIds.contains(directive.policyId())
                    && directive.operator() != RowSecurityOperator.IS_NULL
                    && directive.values().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** The relational path has no plugin, so it reports a stable synthetic id. */
    private String engineId(DbType dbType) {
        return engineCatalog.isEngineManaged(dbType)
                ? dbType.name().toLowerCase(Locale.ROOT)
                : RELATIONAL_ENGINE_ID;
    }

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}
