package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentView;
import com.bablsoft.accessflow.deploygov.api.DeploymentVerificationConfig;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import com.bablsoft.accessflow.deploygov.internal.verification.DeploymentVerificationConfigCodec;
import com.bablsoft.accessflow.deploygov.internal.verification.DeploymentVerificationConfigCodec.VerificationConfigParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The one entity → {@link DeploymentEnvironmentView} mapping, shared by the admin service and both
 * lookup services so a new column (#877's {@code datasourceId}) cannot land in one view and drift
 * out of another. A stored verification config the codec cannot read maps to {@code null} with a
 * WARN, so one corrupt row never breaks an environment listing (#1023). That makes the view's null
 * ambiguous (off vs unreadable) — verification consumers decode the stored blob themselves and fail
 * closed on a parse error.
 */
final class DeploymentEnvironmentViewMapper {

    private static final Logger log = LoggerFactory.getLogger(DeploymentEnvironmentViewMapper.class);

    private DeploymentEnvironmentViewMapper() {
    }

    static DeploymentEnvironmentView toView(DeploymentEnvironmentEntity e,
                                            DeploymentVerificationConfigCodec codec) {
        return new DeploymentEnvironmentView(
                e.getId(), e.getPipelineId(), e.getName(), e.getSortOrder(), e.isRequireReview(),
                e.getRequiredApprovals(), e.getReviewPlanId(), e.isAllowBreakGlass(), e.getCreatedAt(),
                e.getTags() == null ? List.of() : List.of(e.getTags()),
                e.getDatasourceId(), e.isChangeTicketRequired(), verification(e, codec));
    }

    private static DeploymentVerificationConfig verification(DeploymentEnvironmentEntity e,
                                                             DeploymentVerificationConfigCodec codec) {
        if (e.getVerification() == null) {
            return null;
        }
        try {
            return codec.fromJson(e.getVerification());
        } catch (VerificationConfigParseException ex) {
            log.warn("Ignoring unreadable verification config on deployment environment {}: {}",
                    e.getId(), ex.getMessage());
            return null;
        }
    }
}
