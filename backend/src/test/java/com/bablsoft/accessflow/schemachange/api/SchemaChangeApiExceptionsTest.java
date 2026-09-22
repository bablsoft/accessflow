package com.bablsoft.accessflow.schemachange.api;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaChangeApiExceptionsTest {

    @Test
    void changeSetNotFoundCarriesTheId() {
        var id = UUID.randomUUID();

        var ex = new SchemaChangeSetNotFoundException(id);

        assertThat(ex).isInstanceOf(SchemaChangeException.class);
        assertThat(ex.changeSetId()).isEqualTo(id);
        assertThat(ex.getMessage()).contains(id.toString());
    }

    @Test
    void promotionNotFoundCarriesTheId() {
        var id = UUID.randomUUID();

        var ex = new SchemaChangePromotionNotFoundException(id);

        assertThat(ex).isInstanceOf(SchemaChangeException.class);
        assertThat(ex.promotionId()).isEqualTo(id);
        assertThat(ex.getMessage()).contains(id.toString());
    }

    @Test
    void driftFindingNotFoundCarriesTheId() {
        var id = UUID.randomUUID();

        var ex = new SchemaDriftFindingNotFoundException(id);

        assertThat(ex).isInstanceOf(SchemaChangeException.class);
        assertThat(ex.findingId()).isEqualTo(id);
        assertThat(ex.getMessage()).contains(id.toString());
    }

    @Test
    void nameConflictCarriesPipelineAndName() {
        var pipelineId = UUID.randomUUID();

        var ex = new SchemaChangeSetNameConflictException(pipelineId, "orders-v2");

        assertThat(ex).isInstanceOf(SchemaChangeException.class);
        assertThat(ex.pipelineId()).isEqualTo(pipelineId);
        assertThat(ex.name()).isEqualTo("orders-v2");
        assertThat(ex.getMessage()).contains(pipelineId.toString()).contains("orders-v2");
    }

    @Test
    void promotionConflictCarriesSetAndEnvironment() {
        var changeSetId = UUID.randomUUID();
        var environmentId = UUID.randomUUID();

        var ex = new SchemaChangePromotionConflictException(changeSetId, environmentId);

        assertThat(ex).isInstanceOf(SchemaChangeException.class);
        assertThat(ex.changeSetId()).isEqualTo(changeSetId);
        assertThat(ex.environmentId()).isEqualTo(environmentId);
        assertThat(ex.getMessage()).contains(changeSetId.toString()).contains(environmentId.toString());
    }
    @Test
    void pipelineNotFoundCarriesThePipelineId() {
        var id = UUID.randomUUID();

        var ex = new SchemaChangePipelineNotFoundException(id);

        assertThat(ex).isInstanceOf(SchemaChangeException.class);
        assertThat(ex.pipelineId()).isEqualTo(id);
        assertThat(ex.getMessage()).contains(id.toString());
    }

    @Test
    void noTargetDatasourceCarriesThePipelineId() {
        var id = UUID.randomUUID();

        var ex = new SchemaChangeSetNoTargetDatasourceException(id);

        assertThat(ex).isInstanceOf(SchemaChangeException.class);
        assertThat(ex.pipelineId()).isEqualTo(id);
        assertThat(ex.getMessage()).contains(id.toString());
    }

    @Test
    void statementLimitCarriesLimitAndActual() {
        var ex = new SchemaChangeSetStatementLimitException(50, 51);

        assertThat(ex).isInstanceOf(SchemaChangeException.class);
        assertThat(ex.limit()).isEqualTo(50);
        assertThat(ex.actual()).isEqualTo(51);
        assertThat(ex.getMessage()).contains("51").contains("50");
    }

    @Test
    void statementInvalidFactoriesSetReasonAndOptionalFields() {
        var unparseable = SchemaChangeSetStatementInvalidException.unparseable(3, "boom");
        var dml = SchemaChangeSetStatementInvalidException.dml(1, QueryType.DELETE);
        var envelope = SchemaChangeSetStatementInvalidException.transactionEnvelope(0);
        var multiple = SchemaChangeSetStatementInvalidException.multipleStatements(2);

        assertThat(unparseable).isInstanceOf(SchemaChangeException.class);
        assertThat(unparseable.statementIndex()).isEqualTo(3);
        assertThat(unparseable.reason()).isEqualTo(SchemaChangeSetStatementInvalidException.Reason.UNPARSEABLE);
        assertThat(unparseable.parserMessage()).isEqualTo("boom");
        assertThat(unparseable.queryType()).isNull();
        assertThat(unparseable.getMessage()).contains("3").contains("UNPARSEABLE");

        assertThat(dml.reason()).isEqualTo(SchemaChangeSetStatementInvalidException.Reason.DML);
        assertThat(dml.queryType()).isEqualTo(QueryType.DELETE);
        assertThat(dml.parserMessage()).isNull();

        assertThat(envelope.reason()).isEqualTo(SchemaChangeSetStatementInvalidException.Reason.TRANSACTION_ENVELOPE);
        assertThat(envelope.statementIndex()).isZero();
        assertThat(multiple.reason()).isEqualTo(SchemaChangeSetStatementInvalidException.Reason.MULTIPLE_STATEMENTS);
        assertThat(multiple.statementIndex()).isEqualTo(2);
    }

    @Test
    void statementBlockedCopiesFindingsAndTreatsNullAsEmpty() {
        var finding = new SchemaChangeStatementFinding(0, UUID.randomUUID(),
                new SqlReviewFinding("drop_statement", SqlReviewSeverity.BLOCK, 0, null, Map.of()));
        var findings = new ArrayList<>(List.of(finding));

        var ex = new SchemaChangeSetStatementBlockedException(findings);
        findings.clear();

        assertThat(ex).isInstanceOf(SchemaChangeException.class);
        assertThat(ex.findings()).containsExactly(finding);
        assertThat(ex.getMessage()).contains("1 blocking");
        assertThat(new SchemaChangeSetStatementBlockedException(null).findings()).isEmpty();
    }

    @Test
    void frozenAndArchivedCarryTheChangeSetId() {
        var id = UUID.randomUUID();

        var frozen = new SchemaChangeSetFrozenException(id);
        var archived = new SchemaChangeSetArchivedException(id);

        assertThat(frozen).isInstanceOf(SchemaChangeException.class);
        assertThat(frozen.changeSetId()).isEqualTo(id);
        assertThat(frozen.getMessage()).contains(id.toString());
        assertThat(archived).isInstanceOf(SchemaChangeException.class);
        assertThat(archived.changeSetId()).isEqualTo(id);
        assertThat(archived.getMessage()).contains(id.toString());
    }

    @Test
    void statusTransitionCarriesBothStatuses() {
        var id = UUID.randomUUID();

        var ex = new SchemaChangeSetStatusTransitionException(id, SchemaChangeSetStatus.DRAFT,
                SchemaChangeSetStatus.ACTIVE);

        assertThat(ex).isInstanceOf(SchemaChangeException.class);
        assertThat(ex.changeSetId()).isEqualTo(id);
        assertThat(ex.currentStatus()).isEqualTo(SchemaChangeSetStatus.DRAFT);
        assertThat(ex.requestedStatus()).isEqualTo(SchemaChangeSetStatus.ACTIVE);
        assertThat(ex.getMessage()).contains("DRAFT").contains("ACTIVE");
    }
}
