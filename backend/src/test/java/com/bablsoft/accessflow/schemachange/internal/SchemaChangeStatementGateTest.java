package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceView;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentLookupService;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentView;
import com.bablsoft.accessflow.proxy.api.QueryParser;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetNoTargetDatasourceException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatementBlockedException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatementInput;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatementInvalidException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatementInvalidException.Reason;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeStatementFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewResult;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewService;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemaChangeStatementGateTest {

    private static final String CREATE = "CREATE TABLE t (id INT)";
    private static final String ALTER = "ALTER TABLE t ADD c INT";

    @Mock
    private DeploymentEnvironmentLookupService environmentLookupService;
    @Mock
    private DatasourceAdminService datasourceAdminService;
    @Mock
    private QueryParser queryParser;
    @Mock
    private SqlReviewService sqlReviewService;
    @InjectMocks
    private SchemaChangeStatementGate gate;

    private final UUID organizationId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();
    private final UUID devDatasourceId = UUID.randomUUID();
    private final UUID prodDatasourceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(environmentLookupService.listByPipeline(pipelineId)).thenReturn(List.of(
                environment(0, devDatasourceId), environment(1, null), environment(2, prodDatasourceId),
                environment(3, devDatasourceId)));
        lenient().when(datasourceAdminService.getForAdmin(devDatasourceId, organizationId))
                .thenReturn(datasource(devDatasourceId, DbType.POSTGRESQL));
        lenient().when(datasourceAdminService.getForAdmin(prodDatasourceId, organizationId))
                .thenReturn(datasource(prodDatasourceId, DbType.POSTGRESQL));
        lenient().when(queryParser.parse(anyString(), any())).thenAnswer(inv ->
                new SqlParseResult(QueryType.DDL, inv.getArgument(0)));
        lenient().when(sqlReviewService.evaluate(eq(organizationId), any(), anyString()))
                .thenReturn(SqlReviewResult.clean());
    }

    @Test
    void emptyInputNeedsNoLookups() {
        assertThat(gate.validate(organizationId, pipelineId, List.of())).isSameAs(SchemaChangeStatementGate.GateResult.EMPTY);
        assertThat(gate.validate(organizationId, pipelineId, null).statements()).isEmpty();

        verifyNoInteractions(environmentLookupService, datasourceAdminService, queryParser, sqlReviewService);
    }

    @Test
    void refusesStatementsWhenNoEnvironmentBindsADatasource() {
        when(environmentLookupService.listByPipeline(pipelineId)).thenReturn(List.of(environment(0, null)));

        assertThatThrownBy(() -> gate.validate(organizationId, pipelineId, inputs(CREATE)))
                .isInstanceOf(SchemaChangeSetNoTargetDatasourceException.class)
                .extracting("pipelineId").isEqualTo(pipelineId);
        verifyNoInteractions(datasourceAdminService, queryParser, sqlReviewService);
    }

    @Test
    void classifiesNormalisesAndReviewsAgainstEveryDistinctTarget() {
        when(queryParser.parse(ALTER, DbType.POSTGRESQL)).thenReturn(new SqlParseResult(QueryType.OTHER, ALTER));

        var result = gate.validate(organizationId, pipelineId, inputs("  " + CREATE + ";\n", ALTER));

        assertThat(result.statements()).extracting("sqlText", "queryType")
                .containsExactly(tuple(CREATE, QueryType.DDL), tuple(ALTER, QueryType.OTHER));
        assertThat(result.warnings()).isEmpty();
        // The dev datasource is bound twice on the ladder; it is resolved and reviewed once per statement.
        verify(datasourceAdminService, times(1)).getForAdmin(devDatasourceId, organizationId);
        verify(datasourceAdminService, times(1)).getForAdmin(prodDatasourceId, organizationId);
        verify(queryParser, times(1)).parse(CREATE, DbType.POSTGRESQL);
        verify(sqlReviewService).evaluate(organizationId, devDatasourceId, CREATE);
        verify(sqlReviewService).evaluate(organizationId, prodDatasourceId, CREATE);
        verify(sqlReviewService).evaluate(organizationId, devDatasourceId, ALTER);
        verify(sqlReviewService).evaluate(organizationId, prodDatasourceId, ALTER);
    }

    @Test
    void parsesOncePerDistinctDbTypeAndKeepsTheFirstClassification() {
        when(datasourceAdminService.getForAdmin(prodDatasourceId, organizationId))
                .thenReturn(datasource(prodDatasourceId, DbType.MYSQL));
        when(queryParser.parse(CREATE, DbType.POSTGRESQL)).thenReturn(new SqlParseResult(QueryType.DDL, CREATE));
        when(queryParser.parse(CREATE, DbType.MYSQL)).thenReturn(new SqlParseResult(QueryType.OTHER, CREATE));

        var result = gate.validate(organizationId, pipelineId, inputs(CREATE));

        assertThat(result.statements()).extracting("queryType").containsExactly(QueryType.DDL);
        verify(queryParser).parse(CREATE, DbType.POSTGRESQL);
        verify(queryParser).parse(CREATE, DbType.MYSQL);
    }

    @Test
    void blankStatementIsUnparseableWithoutAParserCall() {
        assertThatThrownBy(() -> gate.validate(organizationId, pipelineId, inputs(CREATE, "   ")))
                .isInstanceOf(SchemaChangeSetStatementInvalidException.class)
                .satisfies(ex -> {
                    var e = (SchemaChangeSetStatementInvalidException) ex;
                    assertThat(e.statementIndex()).isEqualTo(1);
                    assertThat(e.reason()).isEqualTo(Reason.UNPARSEABLE);
                    assertThat(e.parserMessage()).isNull();
                });
        verify(queryParser, never()).parse(eq(""), any());
    }

    @Test
    void nullInputIsUnparseable() {
        var inputs = new java.util.ArrayList<SchemaChangeSetStatementInput>();
        inputs.add(null);

        assertThatThrownBy(() -> gate.validate(organizationId, pipelineId, inputs))
                .isInstanceOf(SchemaChangeSetStatementInvalidException.class)
                .extracting("reason").isEqualTo(Reason.UNPARSEABLE);
    }

    @Test
    void parserFailureIsUnparseableWithTheParserMessage() {
        when(queryParser.parse("garbage", DbType.POSTGRESQL)).thenThrow(new InvalidSqlException("localized parse detail"));

        assertThatThrownBy(() -> gate.validate(organizationId, pipelineId, inputs("garbage")))
                .isInstanceOf(SchemaChangeSetStatementInvalidException.class)
                .extracting("statementIndex", "reason", "parserMessage")
                .containsExactly(0, Reason.UNPARSEABLE, "localized parse detail");
        verifyNoInteractions(sqlReviewService);
    }

    @Test
    void dmlIsRefusedWithItsClassification() {
        var delete = "DELETE FROM t";
        when(queryParser.parse(delete, DbType.POSTGRESQL)).thenReturn(new SqlParseResult(QueryType.DELETE, delete));

        assertThatThrownBy(() -> gate.validate(organizationId, pipelineId, inputs(CREATE, delete)))
                .isInstanceOf(SchemaChangeSetStatementInvalidException.class)
                .extracting("statementIndex", "reason", "queryType")
                .containsExactly(1, Reason.DML, QueryType.DELETE);
    }

    @Test
    void transactionEnvelopeIsRefusedBeforeParsing() {
        assertThatThrownBy(() -> gate.validate(organizationId, pipelineId, inputs("BEGIN; " + CREATE + "; COMMIT;")))
                .isInstanceOf(SchemaChangeSetStatementInvalidException.class)
                .extracting("statementIndex", "reason").containsExactly(0, Reason.TRANSACTION_ENVELOPE);
        verifyNoInteractions(queryParser, sqlReviewService);
    }

    @Test
    void multipleStatementsAreRefusedBeforeParsing() {
        assertThatThrownBy(() -> gate.validate(organizationId, pipelineId, inputs(CREATE + "; " + ALTER)))
                .isInstanceOf(SchemaChangeSetStatementInvalidException.class)
                .extracting("statementIndex", "reason").containsExactly(0, Reason.MULTIPLE_STATEMENTS);
        verifyNoInteractions(queryParser, sqlReviewService);
    }

    @Test
    void warningsAreCollectedPerStatementAndTarget() {
        var warn = new SqlReviewFinding("ddl_statement", SqlReviewSeverity.WARN, 0, 1, Map.of());
        when(sqlReviewService.evaluate(organizationId, prodDatasourceId, ALTER))
                .thenReturn(new SqlReviewResult(true, List.of(warn)));

        var result = gate.validate(organizationId, pipelineId, inputs(CREATE, ALTER));

        assertThat(result.warnings()).containsExactly(new SchemaChangeStatementFinding(1, prodDatasourceId, warn));
    }

    @Test
    void anyBlockingFindingRefusesTheWholeSetAfterEveryStatementWasChecked() {
        var block = new SqlReviewFinding("drop_statement", SqlReviewSeverity.BLOCK, 0, null, Map.of());
        var warn = new SqlReviewFinding("ddl_statement", SqlReviewSeverity.WARN, 0, null, Map.of());
        when(sqlReviewService.evaluate(organizationId, devDatasourceId, CREATE))
                .thenReturn(new SqlReviewResult(true, List.of(warn)));
        when(sqlReviewService.evaluate(organizationId, prodDatasourceId, CREATE))
                .thenReturn(new SqlReviewResult(true, List.of(block)));
        when(sqlReviewService.evaluate(organizationId, prodDatasourceId, ALTER))
                .thenReturn(new SqlReviewResult(true, List.of(block)));

        assertThatThrownBy(() -> gate.validate(organizationId, pipelineId, inputs(CREATE, ALTER)))
                .isInstanceOf(SchemaChangeSetStatementBlockedException.class)
                .satisfies(ex -> assertThat(((SchemaChangeSetStatementBlockedException) ex).findings())
                        .containsExactly(new SchemaChangeStatementFinding(0, prodDatasourceId, block),
                                new SchemaChangeStatementFinding(1, prodDatasourceId, block)));
        verify(sqlReviewService).evaluate(organizationId, devDatasourceId, ALTER);
    }

    @Test
    void notApplicableReviewYieldsNoFindings() {
        when(sqlReviewService.evaluate(eq(organizationId), any(), anyString())).thenReturn(SqlReviewResult.notApplicable());

        assertThat(gate.validate(organizationId, pipelineId, inputs(CREATE)).warnings()).isEmpty();
    }

    private static List<SchemaChangeSetStatementInput> inputs(String... sql) {
        return java.util.Arrays.stream(sql).map(SchemaChangeSetStatementInput::new).toList();
    }

    private static org.assertj.core.groups.Tuple tuple(Object... values) {
        return org.assertj.core.groups.Tuple.tuple(values);
    }

    private DeploymentEnvironmentView environment(int sortOrder, UUID datasourceId) {
        return new DeploymentEnvironmentView(UUID.randomUUID(), pipelineId, "env-" + sortOrder, sortOrder, true, null,
                null, false, Instant.EPOCH, List.of(), datasourceId);
    }

    private DatasourceView datasource(UUID id, DbType dbType) {
        return new DatasourceView(id, organizationId, "ds", dbType, "nope.invalid", 5432, "db", "u", null, 1, 100,
                true, true, null, false, null, false, null, null, null, List.of(), true, Instant.EPOCH, null, false,
                null, null);
    }
}
