package com.bablsoft.accessflow.schemachange.internal.web;

import com.bablsoft.accessflow.deploygov.api.FreezeBehavior;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeLadderBlocker;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeLadderRungState;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeLadderRungView;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeLadderView;

import java.util.List;
import java.util.UUID;

public record SchemaChangeLadderResponse(UUID changeSetId, UUID pipelineId, List<Rung> rungs) {

    public record Rung(
            UUID environmentId,
            String environmentName,
            int sortOrder,
            UUID datasourceId,
            SchemaChangePromotionResponse latestPromotion,
            SchemaChangeLadderRungState state,
            SchemaChangeLadderBlocker blocker,
            UUID blockingEnvironmentId,
            String blockingEnvironmentName,
            UUID freezeWindowId,
            FreezeBehavior freezeBehavior,
            String freezeReason) {

        static Rung from(SchemaChangeLadderRungView view) {
            var promotion = view.latestPromotion() == null
                    ? null
                    : SchemaChangePromotionResponse.from(view.latestPromotion());
            return new Rung(view.environmentId(), view.environmentName(), view.sortOrder(), view.datasourceId(),
                    promotion, view.state(), view.blocker(), view.blockingEnvironmentId(),
                    view.blockingEnvironmentName(), view.freezeWindowId(), view.freezeBehavior(),
                    view.freezeReason());
        }
    }

    static SchemaChangeLadderResponse from(SchemaChangeLadderView view) {
        return new SchemaChangeLadderResponse(view.changeSetId(), view.pipelineId(),
                view.rungs().stream().map(Rung::from).toList());
    }
}
