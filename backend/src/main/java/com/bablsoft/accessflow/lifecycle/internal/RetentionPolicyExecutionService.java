package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SoftDeleteDirective;
import com.bablsoft.accessflow.core.api.UpdateExecutionResult;
import com.bablsoft.accessflow.lifecycle.api.LifecycleAction;
import com.bablsoft.accessflow.lifecycle.api.LifecycleDirectiveResolutionService;
import com.bablsoft.accessflow.lifecycle.api.LifecycleRunKind;
import com.bablsoft.accessflow.lifecycle.api.LifecycleRunStatus;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.LifecycleRunEntity;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.RetentionPolicyEntity;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.LifecycleRunRepository;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.RetentionPolicyRepository;
import com.bablsoft.accessflow.proxy.api.QueryExecutor;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Executes STAGED retention-policy {@code lifecycle_runs} (AF-519 — previously runs were staged but
 * never executed). For each drained run it loads the policy, compiles the age window + structured
 * conditions + raw WHERE via {@link ErasurePredicateCompiler}, and applies the action through the
 * proxy {@link QueryExecutor}:
 *
 * <ul>
 *   <li>{@code HARD_DELETE} → a governed {@code DELETE FROM <table> WHERE …} (physical removal);</li>
 *   <li>{@code SOFT_DELETE} → the same DELETE, which the datasource's soft-delete directives rewrite
 *       into a marker {@code UPDATE};</li>
 *   <li>{@code PSEUDONYMIZE} → no destructive batch write: pseudonymization is enforced continuously
 *       at read time by {@link LifecycleDirectiveResolutionService}, so the run is recorded as
 *       completed (read-time-enforced) for the audit ledger.</li>
 * </ul>
 *
 * Writes a {@code lifecycle_runs} outcome + a {@code RETENTION_POLICY_EXECUTED} audit row. Structured
 * condition values are bound as JDBC parameters — never string-concatenated. Mirrors
 * {@link ErasureExecutionService}.
 */
@Service
@RequiredArgsConstructor
public class RetentionPolicyExecutionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionPolicyExecutionService.class);
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");

    private final LifecycleRunRepository runRepository;
    private final RetentionPolicyRepository policyRepository;
    private final ErasurePredicateCompiler predicateCompiler;
    private final ErasureConditionCodec conditionCodec;
    private final LifecycleDirectiveResolutionService directiveResolutionService;
    private final QueryExecutor queryExecutor;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** @return ids of {@code STAGED} runs of kind {@code RETENTION_POLICY}, awaiting execution. */
    @Transactional(readOnly = true)
    public List<UUID> findStagedRunIds() {
        return runRepository.findIdsByStatusAndKind(
                LifecycleRunStatus.STAGED, LifecycleRunKind.RETENTION_POLICY);
    }

    /** @return {@code true} if the run was executed; {@code false} if it was no longer STAGED. */
    @Transactional
    public boolean execute(UUID runId) {
        var run = runRepository.findByIdForUpdate(runId).orElse(null);
        if (run == null || run.getStatus() != LifecycleRunStatus.STAGED
                || run.getKind() != LifecycleRunKind.RETENTION_POLICY) {
            return false;
        }
        var policy = run.getPolicyId() == null ? null
                : policyRepository.findById(run.getPolicyId()).orElse(null);
        if (policy == null) {
            fail(run, "policy no longer exists", 0);
            return true;
        }

        try {
            if (policy.getAction() == LifecycleAction.PSEUDONYMIZE) {
                // Pseudonymization is read-time enforced; no destructive batch write is issued.
                complete(run, 0, policy.getTargetTable(), "PSEUDONYMIZE(read-time)");
                writeAudit(policy, 0, "PSEUDONYMIZE(read-time)");
                return true;
            }
            long affected = eraseByPolicy(policy);
            complete(run, affected, policy.getTargetTable(), run.getMethod());
            writeAudit(policy, affected, run.getMethod());
        } catch (RuntimeException ex) {
            log.error("Retention run {} (policy {}) failed", runId, policy.getId(), ex);
            fail(run, ex.getMessage(), 0);
        }
        return true;
    }

    private long eraseByPolicy(RetentionPolicyEntity policy) {
        var table = policy.getTargetTable();
        if (table == null || table.isBlank() || !IDENTIFIER.matcher(table).matches()) {
            throw new IllegalStateException("retention policy has no executable target table");
        }
        ZonedDateTime cutoff = RetentionWindow.parse(policy.getRetentionWindow())
                .cutoffFrom(ZonedDateTime.now(clock));
        var compiled = predicateCompiler.compile(policy.getId(), table, null, null,
                conditionCodec.fromJson(policy.getConditions()), policy.getRawWhere(),
                policy.getTimestampColumn(), cutoff);
        String sql = "DELETE FROM " + table
                + (compiled.whereClause() == null ? "" : " WHERE " + compiled.whereClause());
        List<SoftDeleteDirective> softDeletes = policy.getAction() == LifecycleAction.SOFT_DELETE
                ? directiveResolutionService.resolveSoftDeletes(
                        policy.getOrganizationId(), policy.getDatasourceId())
                : List.of();
        var request = new QueryExecutionRequest(policy.getDatasourceId(), sql, QueryType.DELETE,
                null, null, List.of(), List.of(), compiled.directives(), false, null, softDeletes);
        var result = queryExecutor.execute(request);
        return result instanceof UpdateExecutionResult u ? u.rowsAffected() : 0;
    }

    private void complete(LifecycleRunEntity run, long affected, String table, String method) {
        run.setStatus(LifecycleRunStatus.COMPLETED);
        run.setAffectedRows(affected);
        run.setMethod(method);
        run.setMatchedTables(matchedTables(table, affected));
        var now = clock.instant();
        run.setStartedAt(run.getStartedAt() == null ? now : run.getStartedAt());
        run.setFinishedAt(now);
        run.setUpdatedAt(now);
        runRepository.save(run);
    }

    private void fail(LifecycleRunEntity run, String reason, long affected) {
        run.setStatus(LifecycleRunStatus.FAILED);
        run.setAffectedRows(affected);
        run.setFailureReason(reason);
        var now = clock.instant();
        run.setStartedAt(run.getStartedAt() == null ? now : run.getStartedAt());
        run.setFinishedAt(now);
        run.setUpdatedAt(now);
        runRepository.save(run);
    }

    private String matchedTables(String table, long affected) {
        var arr = objectMapper.createArrayNode();
        arr.add(objectMapper.createObjectNode().put("table", table).put("affected_rows", affected));
        return objectMapper.writeValueAsString(arr);
    }

    private void writeAudit(RetentionPolicyEntity policy, long affected, String method) {
        try {
            auditLogService.record(new AuditEntry(
                    AuditAction.RETENTION_POLICY_EXECUTED,
                    AuditResourceType.RETENTION_POLICY,
                    policy.getId(),
                    policy.getOrganizationId(),
                    null,
                    Map.of("affected_rows", affected, "table",
                            policy.getTargetTable() == null ? "" : policy.getTargetTable(),
                            "method", method == null ? "" : method,
                            "action", policy.getAction().name()),
                    null,
                    null));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for retention policy {}", policy.getId(), ex);
        }
    }
}
