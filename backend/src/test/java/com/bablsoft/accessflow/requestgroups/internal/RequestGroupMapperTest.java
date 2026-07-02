package com.bablsoft.accessflow.requestgroups.internal;

import com.bablsoft.accessflow.apigov.api.ApiBodyType;
import com.bablsoft.accessflow.apigov.api.ApiFormField;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.DatasourceRef;
import com.bablsoft.accessflow.core.api.QueryDetailView;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupItemStatus;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupStatus;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupTargetKind;
import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.RequestGroupEntity;
import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.RequestGroupItemEntity;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RequestGroupMapperTest {

    /** Mirrors the app's global SNAKE_CASE naming so stored form-field JSON reads back correctly. */
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    @Test
    void mapsGroupWithEnrichedNames() {
        var datasourceId = UUID.randomUUID();
        var group = new RequestGroupEntity();
        group.setId(UUID.randomUUID());
        group.setOrganizationId(UUID.randomUUID());
        group.setSubmittedBy(UUID.randomUUID());
        group.setName("bundle");
        group.setStatus(RequestGroupStatus.PENDING_REVIEW);
        group.setAiRiskLevel(RiskLevel.HIGH);
        group.setAiRiskScore(72);

        var item = new RequestGroupItemEntity();
        item.setId(UUID.randomUUID());
        item.setSequenceOrder(0);
        item.setTargetKind(RequestGroupTargetKind.QUERY);
        item.setDatasourceId(datasourceId);
        item.setSqlText("SELECT 1");
        item.setQueryType(QueryType.SELECT);
        item.setStatus(RequestGroupItemStatus.PENDING);

        var submitter = new UserView(group.getSubmittedBy(), "u@x.io", "Dana", UserRoleType.ANALYST,
                group.getOrganizationId(), true, null, null, Instant.now(), "en", false, Instant.now());

        var view = RequestGroupMapper.toView(group, List.of(item), submitter,
                Map.of(datasourceId, new DatasourceRef(datasourceId, "prod-db")), Map.of(), Map.of(),
                objectMapper, false);

        assertThat(view.submittedByDisplayName()).isEqualTo("Dana");
        assertThat(view.aiRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(view.items()).hasSize(1);
        assertThat(view.items().get(0).datasourceName()).isEqualTo("prod-db");
        assertThat(view.items().get(0).targetKind()).isEqualTo(RequestGroupTargetKind.QUERY);
        assertThat(view.items().get(0).aiAnalysis()).isNull();
    }

    @Test
    void attachesEmbeddedAnalysisWhenPresentForItem() {
        var group = new RequestGroupEntity();
        group.setId(UUID.randomUUID());
        group.setOrganizationId(UUID.randomUUID());
        group.setSubmittedBy(UUID.randomUUID());
        group.setName("bundle");
        group.setStatus(RequestGroupStatus.PENDING_REVIEW);

        var item = new RequestGroupItemEntity();
        item.setId(UUID.randomUUID());
        item.setSequenceOrder(0);
        item.setTargetKind(RequestGroupTargetKind.QUERY);
        item.setSqlText("SELECT 1");
        item.setQueryType(QueryType.SELECT);
        item.setStatus(RequestGroupItemStatus.PENDING);
        item.setAiAnalysisId(UUID.randomUUID());

        var detail = new QueryDetailView.AiAnalysisDetail(item.getAiAnalysisId(), RiskLevel.MEDIUM, 40,
                "Reads one row", "[]", "[]", false, 1L, AiProviderType.OPENAI, "gpt-4o", 10, 5,
                false, null);

        var view = RequestGroupMapper.toView(group, List.of(item), null, Map.of(), Map.of(),
                Map.of(item.getId(), detail), objectMapper, true);

        assertThat(view.items().get(0).aiAnalysis()).isSameAs(detail);
        assertThat(view.items().get(0).aiAnalysis().summary()).isEqualTo("Reads one row");
    }

    private RequestGroupEntity group() {
        var group = new RequestGroupEntity();
        group.setId(UUID.randomUUID());
        group.setOrganizationId(UUID.randomUUID());
        group.setSubmittedBy(UUID.randomUUID());
        group.setName("bundle");
        group.setStatus(RequestGroupStatus.DRAFT);
        return group;
    }

    private RequestGroupItemEntity apiItem() {
        var item = new RequestGroupItemEntity();
        item.setId(UUID.randomUUID());
        item.setSequenceOrder(0);
        item.setTargetKind(RequestGroupTargetKind.API_CALL);
        item.setApiConnectorId(UUID.randomUUID());
        item.setVerb("POST");
        item.setRequestPath("/v1/tickets");
        item.setRequestHeaders("{\"X-Trace\":\"1\"}");
        item.setQueryParams("{\"dryRun\":\"true\"}");
        item.setBodyType(ApiBodyType.FORM_DATA);
        item.setFormFields("[{\"name\":\"note\",\"value\":\"hello\",\"file\":false},"
                + "{\"name\":\"doc\",\"value\":\"aGk=\",\"file\":true,\"filename\":\"a.txt\","
                + "\"content_type\":\"text/plain\"}]");
        item.setStatus(RequestGroupItemStatus.PENDING);
        return item;
    }

    @Test
    void includesApiCompositionOnDetailViews() {
        var item = apiItem();
        item.setRequestContentType("application/json");
        item.setRequestBody("{\"a\":1}");
        item.setBinaryFilename(null);

        var view = RequestGroupMapper.toView(group(), List.of(item), null, Map.of(), Map.of(),
                Map.of(), objectMapper, true);

        var v = view.items().get(0);
        assertThat(v.requestHeaders()).containsEntry("X-Trace", "1");
        assertThat(v.queryParams()).containsEntry("dryRun", "true");
        assertThat(v.bodyType()).isEqualTo(ApiBodyType.FORM_DATA);
        assertThat(v.requestContentType()).isEqualTo("application/json");
        assertThat(v.requestBody()).isEqualTo("{\"a\":1}");
        assertThat(v.formFields()).containsExactly(
                new ApiFormField("note", ApiFormField.ApiFormFieldType.TEXT, "hello", null, null),
                new ApiFormField("doc", ApiFormField.ApiFormFieldType.FILE, "aGk=", "a.txt",
                        "text/plain"));
    }

    @Test
    void omitsCompositionOnListViews() {
        var view = RequestGroupMapper.toView(group(), List.of(apiItem()), null, Map.of(), Map.of(),
                Map.of(), objectMapper, false);

        var v = view.items().get(0);
        assertThat(v.requestHeaders()).isEmpty();
        assertThat(v.queryParams()).isEmpty();
        assertThat(v.bodyType()).isNull();
        assertThat(v.requestBody()).isNull();
        assertThat(v.formFields()).isEmpty();
    }

    @Test
    void queryItemsCarryNoCompositionEvenOnDetail() {
        var item = new RequestGroupItemEntity();
        item.setId(UUID.randomUUID());
        item.setSequenceOrder(0);
        item.setTargetKind(RequestGroupTargetKind.QUERY);
        item.setDatasourceId(UUID.randomUUID());
        item.setSqlText("SELECT 1");
        item.setQueryType(QueryType.SELECT);
        item.setStatus(RequestGroupItemStatus.PENDING);

        var view = RequestGroupMapper.toView(group(), List.of(item), null, Map.of(), Map.of(),
                Map.of(), objectMapper, true);

        var v = view.items().get(0);
        assertThat(v.requestHeaders()).isEmpty();
        assertThat(v.bodyType()).isNull();
        assertThat(v.formFields()).isEmpty();
    }

    @Test
    void unreadableStoredJsonFailsSoftToEmpty() {
        var item = apiItem();
        item.setRequestHeaders("not json");
        item.setQueryParams(null);
        item.setFormFields("{\"broken\":");

        var view = RequestGroupMapper.toView(group(), List.of(item), null, Map.of(), Map.of(),
                Map.of(), objectMapper, true);

        var v = view.items().get(0);
        assertThat(v.requestHeaders()).isEmpty();
        assertThat(v.queryParams()).isEmpty();
        assertThat(v.formFields()).isEmpty();
        assertThat(v.bodyType()).isEqualTo(ApiBodyType.FORM_DATA);
    }
}
