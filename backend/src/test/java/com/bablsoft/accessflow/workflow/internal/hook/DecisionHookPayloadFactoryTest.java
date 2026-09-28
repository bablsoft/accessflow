package com.bablsoft.accessflow.workflow.internal.hook;

import com.bablsoft.accessflow.core.api.AiOutcome;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceEnvironment;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.core.api.DatasourceView;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.PrincipalType;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.core.api.QueryShape;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.workflow.api.ConditionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DecisionHookPayloadFactoryTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC);
    private final UUID organizationId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();
    private final UUID onBehalfOf = UUID.randomUUID();
    private final UUID queryId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();
    private UserQueryService userQueryService;
    private DatasourceAdminService datasourceAdminService;
    private DecisionHookPayloadFactory factory;

    @BeforeEach
    void setUp() {
        userQueryService = mock(UserQueryService.class);
        datasourceAdminService = mock(DatasourceAdminService.class);
        factory = new DecisionHookPayloadFactory(userQueryService, datasourceAdminService, mapper,
                clock);
        var user = mock(UserView.class);
        when(user.email()).thenReturn("ana@example.com");
        when(user.displayName()).thenReturn("Ana");
        when(user.principalType()).thenReturn(PrincipalType.HUMAN);
        when(userQueryService.findById(userId)).thenReturn(Optional.of(user));
        var datasource = mock(DatasourceView.class);
        when(datasource.id()).thenReturn(datasourceId);
        when(datasource.name()).thenReturn("prod-orders");
        when(datasource.dbType()).thenReturn(DbType.POSTGRESQL);
        when(datasource.environment()).thenReturn(DatasourceEnvironment.PRODUCTION);
        when(datasourceAdminService.getForAdmin(datasourceId, organizationId)).thenReturn(datasource);
    }

    @Test
    void theQueryPayloadCarriesTheDocumentedFieldsAndNoSql() {
        var json = read(factory.forQuery(requestId, query(), context(), AiOutcome.COMPLETED, false));

        assertThat(json.get("request_id").asString()).isEqualTo(requestId.toString());
        assertThat(json.get("event").asString()).isEqualTo("QUERY_DECISION");
        assertThat(json.get("test").asBoolean()).isFalse();
        assertThat(json.get("timestamp").asString()).isEqualTo("2026-09-28T10:00:00Z");
        assertThat(json.get("organization_id").asString()).isEqualTo(organizationId.toString());
        assertThat(json.get("query_request_id").asString()).isEqualTo(queryId.toString());
        var submitter = json.get("submitter");
        assertThat(submitter.get("email").asString()).isEqualTo("ana@example.com");
        assertThat(submitter.get("display_name").asString()).isEqualTo("Ana");
        assertThat(submitter.get("principal_type").asString()).isEqualTo("HUMAN");
        assertThat(submitter.get("role").asString()).isEqualTo("ANALYST");
        assertThat(submitter.get("group_ids").get(0).asString()).isEqualTo(groupId.toString());
        assertThat(submitter.get("on_behalf_of_user_id").asString())
                .isEqualTo(onBehalfOf.toString());
        var datasource = json.get("datasource");
        assertThat(datasource.get("name").asString()).isEqualTo("prod-orders");
        assertThat(datasource.get("db_type").asString()).isEqualTo("POSTGRESQL");
        assertThat(datasource.get("environment").asString()).isEqualTo("PRODUCTION");
        var query = json.get("query");
        assertThat(query.get("type").asString()).isEqualTo("SELECT");
        assertThat(query.get("referenced_tables").get(0).asString()).isEqualTo("public.orders");
        assertThat(query.get("shapes").get(0).asString()).isEqualTo("JOIN");
        assertThat(query.get("has_where_clause").asBoolean()).isTrue();
        assertThat(query.has("sql")).isFalse();
        assertThat(json.get("ai").get("risk_level").asString()).isEqualTo("LOW");
        assertThat(json.get("ai").get("risk_score").asInt()).isEqualTo(12);
        assertThat(json.get("cost_estimate").get("estimated_rows").asLong()).isEqualTo(1200L);
        assertThat(json.get("cost_estimate").has("estimated_bytes_scanned")).isFalse();
        assertThat(json.get("client").get("ip").asString()).isEqualTo("10.0.0.7");
        assertThat(json.get("client").get("ci_cd_origin").asBoolean()).isFalse();
    }

    @Test
    void theSqlIsIncludedOnlyWhenTheHookOptsIn() {
        var json = read(factory.forQuery(requestId, query(), context(), AiOutcome.COMPLETED, true));

        assertThat(json.get("query").get("sql").asString()).isEqualTo("SELECT * FROM orders");
    }

    @Test
    void unknownValuesAreOmittedRatherThanNull() {
        when(userQueryService.findById(userId)).thenReturn(Optional.empty());
        when(datasourceAdminService.getForAdmin(datasourceId, organizationId))
                .thenThrow(new DatasourceNotFoundException(datasourceId));
        var bare = new ConditionContext(null, null, null, -1, null, null,
                LocalDateTime.now(clock), false, false, false, null, null, false, null, false, null,
                null, Set.of(), false, null, null);
        var plain = new QueryRequestSnapshot(queryId, datasourceId, organizationId, userId, "SELECT 1",
                QueryType.SELECT, false, QueryStatus.PENDING_AI, null, null, null, false);

        var json = read(factory.forQuery(requestId, plain, bare, AiOutcome.SKIPPED, false));

        assertThat(json.has("datasource")).isFalse();
        assertThat(json.get("submitter").has("email")).isFalse();
        assertThat(json.get("submitter").has("on_behalf_of_user_id")).isFalse();
        assertThat(json.get("submitter").get("group_ids").isEmpty()).isTrue();
        assertThat(json.get("query").has("type")).isFalse();
        assertThat(json.get("query").has("shapes")).isFalse();
        assertThat(json.get("ai").has("risk_level")).isFalse();
        assertThat(json.get("ai").has("risk_score")).isFalse();
        assertThat(json.get("client").has("ip")).isFalse();
    }

    @Test
    void theTestPayloadIsMarkedAndCarriesTheBoundDatasource() {
        var json = read(factory.forTest(requestId, organizationId, datasourceId));

        assertThat(json.get("event").asString()).isEqualTo("QUERY_DECISION_TEST");
        assertThat(json.get("test").asBoolean()).isTrue();
        assertThat(json.has("query_request_id")).isFalse();
        assertThat(json.get("datasource").get("name").asString()).isEqualTo("prod-orders");
        assertThat(json.get("ai").get("outcome").asString()).isEqualTo("SKIPPED");
    }

    @Test
    void theTestPayloadOfAnOrganizationDefaultHasNoDatasource() {
        var json = read(factory.forTest(requestId, organizationId, null));

        assertThat(json.has("datasource")).isFalse();
    }

    private JsonNode read(byte[] bytes) {
        return mapper.readTree(bytes);
    }

    private QueryRequestSnapshot query() {
        return new QueryRequestSnapshot(queryId, datasourceId, organizationId, userId,
                "SELECT * FROM orders", QueryType.SELECT, false, QueryStatus.PENDING_AI, null,
                "10.0.0.7", "curl/8", false, null, null, null, null, onBehalfOf);
    }

    private ConditionContext context() {
        return new ConditionContext(QueryType.SELECT, Set.of("public.orders"), RiskLevel.LOW, 12,
                "ANALYST", Set.of(groupId), LocalDateTime.now(clock), true, false, false,
                "10.0.0.7", "curl/8", false, null, false, 1200L, "INDEX", Set.of(QueryShape.JOIN),
                true, null, null);
    }
}
