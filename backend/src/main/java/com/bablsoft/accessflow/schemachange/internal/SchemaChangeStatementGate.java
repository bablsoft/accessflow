package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentLookupService;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentView;
import com.bablsoft.accessflow.proxy.api.QueryParser;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetNoTargetDatasourceException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatementBlockedException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatementInput;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatementInvalidException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeStatementFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The authoring validation gate (#879, epic #870). Runs every statement of a change set, in order,
 * through: shape checks (no transaction envelope, exactly one statement), the engine-aware parser
 * for the {@link DbType} of every distinct target datasource, the "not DML" classification rule
 * (DDL <em>and</em> OTHER are admitted — see docs/20-schema-change-governance.md for the
 * rationale), and the deterministic SQL review ruleset of every target. A {@code BLOCK} finding on
 * any statement against any target refuses the whole set; {@code WARN} findings are returned so the
 * write response can surface them.
 *
 * <p>The targets are the pipeline's environments that bind a datasource, in ladder order. A set
 * that walks the whole ladder must satisfy every rung, so all of them are consulted at authoring
 * time rather than only the entry rung.
 */
@Component
@RequiredArgsConstructor
class SchemaChangeStatementGate {

    private static final Set<QueryType> DML = EnumSet.of(
            QueryType.SELECT, QueryType.INSERT, QueryType.UPDATE, QueryType.DELETE);

    private final DeploymentEnvironmentLookupService environmentLookupService;
    private final DatasourceAdminService datasourceAdminService;
    private final QueryParser queryParser;
    private final SqlReviewService sqlReviewService;

    record ClassifiedStatement(String sqlText, QueryType queryType) {
    }

    record GateResult(List<ClassifiedStatement> statements, List<SchemaChangeStatementFinding> warnings) {
        static final GateResult EMPTY = new GateResult(List.of(), List.of());
    }

    private record Target(UUID datasourceId, DbType dbType) {
    }

    /**
     * @throws SchemaChangeSetNoTargetDatasourceException statements were supplied and no
     *         environment of the pipeline binds a datasource
     * @throws SchemaChangeSetStatementInvalidException the first statement that fails a shape,
     *         parse or classification check
     * @throws SchemaChangeSetStatementBlockedException any {@code BLOCK} finding, after every
     *         statement passed the checks above
     */
    GateResult validate(UUID organizationId, UUID pipelineId, List<SchemaChangeSetStatementInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return GateResult.EMPTY;
        }
        var targets = resolveTargets(organizationId, pipelineId);
        if (targets.isEmpty()) {
            throw new SchemaChangeSetNoTargetDatasourceException(pipelineId);
        }
        var dbTypes = new LinkedHashSet<DbType>();
        targets.forEach(t -> dbTypes.add(t.dbType()));

        var statements = new ArrayList<ClassifiedStatement>(inputs.size());
        var warnings = new ArrayList<SchemaChangeStatementFinding>();
        var blockers = new ArrayList<SchemaChangeStatementFinding>();
        for (var i = 0; i < inputs.size(); i++) {
            var input = inputs.get(i);
            var text = SchemaChangeChecksum.normalize(input == null ? null : input.sqlText());
            statements.add(new ClassifiedStatement(text, classify(i, text, dbTypes)));
            for (var target : targets) {
                var result = sqlReviewService.evaluate(organizationId, target.datasourceId(), text);
                for (var finding : result.findings()) {
                    var wrapped = new SchemaChangeStatementFinding(i, target.datasourceId(), finding);
                    (wrapped.isBlocking() ? blockers : warnings).add(wrapped);
                }
            }
        }
        if (!blockers.isEmpty()) {
            throw new SchemaChangeSetStatementBlockedException(blockers);
        }
        return new GateResult(List.copyOf(statements), List.copyOf(warnings));
    }

    private List<Target> resolveTargets(UUID organizationId, UUID pipelineId) {
        var datasourceIds = new LinkedHashSet<UUID>();
        environmentLookupService.listByPipeline(pipelineId).stream()
                .map(DeploymentEnvironmentView::datasourceId)
                .filter(Objects::nonNull)
                .forEach(datasourceIds::add);
        return datasourceIds.stream()
                .map(id -> new Target(id, datasourceAdminService.getForAdmin(id, organizationId).dbType()))
                .toList();
    }

    private QueryType classify(int index, String text, Set<DbType> dbTypes) {
        if (text.isEmpty()) {
            throw SchemaChangeSetStatementInvalidException.unparseable(index, null);
        }
        if (SchemaChangeStatementScanner.startsWithTransactionMarker(text)) {
            throw SchemaChangeSetStatementInvalidException.transactionEnvelope(index);
        }
        if (SchemaChangeStatementScanner.containsStatementSeparator(text)) {
            throw SchemaChangeSetStatementInvalidException.multipleStatements(index);
        }
        QueryType classification = null;
        for (var dbType : dbTypes) {
            QueryType type;
            try {
                type = queryParser.parse(text, dbType).type();
            } catch (InvalidSqlException ex) {
                throw SchemaChangeSetStatementInvalidException.unparseable(index, ex.getMessage());
            }
            if (DML.contains(type)) {
                throw SchemaChangeSetStatementInvalidException.dml(index, type);
            }
            if (classification == null) {
                classification = type;
            }
        }
        return classification;
    }
}
