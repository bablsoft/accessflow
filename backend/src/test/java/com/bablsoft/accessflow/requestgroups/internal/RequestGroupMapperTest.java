package com.bablsoft.accessflow.requestgroups.internal;

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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RequestGroupMapperTest {

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
                Map.of(datasourceId, new DatasourceRef(datasourceId, "prod-db")), Map.of(), Map.of());

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
                Map.of(item.getId(), detail));

        assertThat(view.items().get(0).aiAnalysis()).isSameAs(detail);
        assertThat(view.items().get(0).aiAnalysis().summary()).isEqualTo("Reads one row");
    }
}
