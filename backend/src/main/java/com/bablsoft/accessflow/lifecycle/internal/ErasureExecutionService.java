package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SoftDeleteDirective;
import com.bablsoft.accessflow.core.api.UpdateExecutionResult;
import com.bablsoft.accessflow.lifecycle.api.ErasureStatus;
import com.bablsoft.accessflow.lifecycle.api.LifecycleAction;
import com.bablsoft.accessflow.lifecycle.api.LifecycleDirectiveResolutionService;
import com.bablsoft.accessflow.lifecycle.api.LifecycleRunKind;
import com.bablsoft.accessflow.lifecycle.api.LifecycleRunStatus;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.DeletionRequestEntity;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.LifecycleRunEntity;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.DeletionRequestRepository;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.LifecycleRunRepository;
import com.bablsoft.accessflow.proxy.api.QueryExecutor;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Executes an APPROVED right-to-erasure request (AF-499): for each table in the immutable scope
 * snapshot, runs a governed, parameter-bound {@code DELETE FROM <table>} scoped to the subject (a
 * bound {@code RowSecurityDirective} — never string-concatenated) through the proxy. The datasource's
 * {@code SOFT_DELETE} policies turn matching DELETEs into marker updates automatically. Writes a
 * {@code lifecycle_runs} record and a tamper-evident {@code DATA_ERASURE_COMPLETED} proof-of-deletion
 * audit row, then transitions the request to {@link ErasureStatus#EXECUTED} (or {@code FAILED}).
 */
@Service
@RequiredArgsConstructor
public class ErasureExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ErasureExecutionService.class);
    // Defense in depth: the table comes from an admin policy, but it is concatenated into the SQL
    // text (the subject value is bound), so restrict it to a simple [schema.]table identifier.
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");

    private final DeletionRequestRepository requestRepository;
    private final LifecycleRunRepository runRepository;
    private final LifecycleDirectiveResolutionService directiveResolutionService;
    private final ErasurePredicateCompiler predicateCompiler;
    private final ErasureConditionCodec conditionCodec;
    private final QueryExecutor queryExecutor;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** @return ids of requests currently in {@link ErasureStatus#APPROVED}, awaiting execution. */
    @Transactional(readOnly = true)
    public List<UUID> findApprovedIds() {
        return requestRepository.findIdsByStatus(ErasureStatus.APPROVED);
    }

    /** @return {@code true} if the request was executed; {@code false} if it was no longer APPROVED. */
    @Transactional
    public boolean execute(UUID requestId) {
        var entity = requestRepository.findByIdForUpdate(requestId).orElse(null);
        if (entity == null || entity.getStatus() != ErasureStatus.APPROVED) {
            return false;
        }
        var tables = scopeTables(entity);
        var softDeletes = directiveResolutionService
                .resolveSoftDeletes(entity.getOrganizationId(), entity.getDatasourceId());

        long totalAffected = 0;
        var perTable = objectMapper.createArrayNode();
        var failures = new ArrayList<String>();
        for (String table : tables) {
            try {
                long affected = eraseTable(entity, table, softDeletes);
                totalAffected += affected;
                perTable.add(objectMapper.createObjectNode().put("table", table)
                        .put("affected_rows", affected));
            } catch (RuntimeException ex) {
                log.error("Erasure of table {} for request {} failed", table, requestId, ex);
                failures.add(table);
            }
        }

        boolean ok = failures.isEmpty();
        recordRun(entity, totalAffected, perTable, ok);
        writeAudit(entity, totalAffected, tables);
        finalizeRequest(entity, totalAffected, ok, failures);
        log.info("Erasure {} executed: {} rows across {} tables ({} failed)", requestId,
                totalAffected, tables.size(), failures.size());
        return true;
    }

    private long eraseTable(DeletionRequestEntity entity, String table,
                            List<SoftDeleteDirective> softDeletes) {
        if (!IDENTIFIER.matcher(table).matches()) {
            throw new IllegalArgumentException("unsafe table identifier: " + table);
        }
        // AF-519: predicates come from the request's own config (subject and/or structured
        // conditions and/or raw WHERE); a subject-only request compiles to exactly the pre-AF-519
        // single subject predicate. Values are bound — only the validated raw WHERE text is inlined.
        var compiled = predicateCompiler.compile(entity.getId(), table, entity.getSubjectType(),
                entity.getSubjectIdentifier(), conditionCodec.fromJson(entity.getConditions()),
                entity.getRawWhere(), null, null);
        String sql = "DELETE FROM " + table
                + (compiled.whereClause() == null ? "" : " WHERE " + compiled.whereClause());
        // referencedTables drives SELECT result-cache invalidation (AF-457) — erased rows must
        // not survive in cached reads.
        var request = new QueryExecutionRequest(entity.getDatasourceId(), sql,
                QueryType.DELETE, null, null, List.of(), List.of(), compiled.directives(),
                false, null, softDeletes, Set.of(table.toLowerCase(Locale.ROOT)));
        var result = queryExecutor.execute(request);
        return result instanceof UpdateExecutionResult u ? u.rowsAffected() : 0;
    }

    private List<String> scopeTables(DeletionRequestEntity entity) {
        var snapshot = entity.getScopeSnapshot();
        if (snapshot == null || snapshot.isBlank()) {
            return List.of();
        }
        try {
            JsonNode tables = objectMapper.readTree(snapshot).path("tables");
            Set<String> out = new LinkedHashSet<>();
            if (tables instanceof ArrayNode arr) {
                for (JsonNode t : arr) {
                    var name = t.asString();
                    if (name != null && !name.isBlank()) {
                        out.add(name);
                    }
                }
            }
            return List.copyOf(out);
        } catch (RuntimeException ex) {
            log.error("Unparseable scope snapshot for erasure {}", entity.getId(), ex);
            return List.of();
        }
    }

    private void recordRun(DeletionRequestEntity entity, long affected, ArrayNode matchedTables,
                           boolean ok) {
        var run = new LifecycleRunEntity();
        run.setId(UUID.randomUUID());
        run.setOrganizationId(entity.getOrganizationId());
        run.setDatasourceId(entity.getDatasourceId());
        run.setKind(LifecycleRunKind.ERASURE_REQUEST);
        run.setDeletionRequestId(entity.getId());
        run.setStatus(ok ? LifecycleRunStatus.COMPLETED : LifecycleRunStatus.FAILED);
        run.setAction(LifecycleAction.HARD_DELETE);
        run.setMethod("ERASURE");
        run.setAffectedRows(affected);
        run.setMatchedTables(objectMapper.writeValueAsString(matchedTables));
        var now = clock.instant();
        run.setStartedAt(now);
        run.setFinishedAt(now);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        runRepository.save(run);
    }

    private void writeAudit(DeletionRequestEntity entity, long affected, List<String> tables) {
        try {
            auditLogService.record(new AuditEntry(
                    AuditAction.DATA_ERASURE_COMPLETED,
                    AuditResourceType.DELETION_REQUEST,
                    entity.getId(),
                    entity.getOrganizationId(),
                    null,
                    Map.of("affected_rows", affected, "tables", tables, "method", "ERASURE",
                            "subject_identifier",
                            entity.getSubjectIdentifier() == null ? "" : entity.getSubjectIdentifier()),
                    null,
                    null));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for erasure {}", entity.getId(), ex);
        }
    }

    private void finalizeRequest(DeletionRequestEntity entity, long affected, boolean ok,
                                 List<String> failures) {
        entity.setAffectedRows(affected);
        entity.setExecutedAt(clock.instant());
        if (ok) {
            entity.setStatus(ErasureStatus.EXECUTED);
        } else {
            entity.setStatus(ErasureStatus.FAILED);
            entity.setFailureReason("Failed tables: " + String.join(", ", failures));
        }
        requestRepository.save(entity);
    }
}
