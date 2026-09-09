package com.bablsoft.accessflow.proxy.api;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.RowSecurityClassification;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;

import java.util.List;
import java.util.UUID;

/**
 * Answers, <em>offline</em>, what row security would do to a query — without connecting to the
 * datasource and without executing anything (issue AF-630). The policy simulator uses it to show an
 * admin which historical query shapes a draft predicate would filter, deny outright, or reject as
 * unrewritable, before the policy is saved.
 *
 * <p>Dispatch mirrors execution: relational datasources go through the same JSqlParser rewriter the
 * proxy uses, engine-managed ones through the plugin's own
 * {@code QueryEngine.classifyRowSecurity}. Any failure to decide is reported as
 * {@link com.bablsoft.accessflow.core.api.RowSecurityOutcome#UNKNOWN} — never as "no impact".
 */
public interface RowSecurityClassificationService {

    RowSecurityClassification classify(UUID datasourceId, DbType dbType, String sql,
                                       List<RowSecurityDirective> directives);
}
