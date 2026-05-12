package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.ReviewPlanView;
import com.bablsoft.accessflow.core.api.UserRoleType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewPlanResponseTest {

    @Test
    void fromMapsAllFieldsAndApprovers() {
        var planId = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var view = new ReviewPlanView(
                planId,
                orgId,
                "PII writes",
                "desc",
                true,
                true,
                2,
                48,
                false,
                List.of("ch-1"),
                List.of(
                        new ReviewPlanView.ApproverRule(userId, null, 1),
                        new ReviewPlanView.ApproverRule(null, UserRoleType.REVIEWER, 2)),
                Instant.parse("2026-05-01T00:00:00Z"));

        var response = ReviewPlanResponse.from(view);

        assertThat(response.id()).isEqualTo(planId);
        assertThat(response.organizationId()).isEqualTo(orgId);
        assertThat(response.name()).isEqualTo("PII writes");
        assertThat(response.description()).isEqualTo("desc");
        assertThat(response.requiresAiReview()).isTrue();
        assertThat(response.requiresHumanApproval()).isTrue();
        assertThat(response.minApprovalsRequired()).isEqualTo(2);
        assertThat(response.approvalTimeoutHours()).isEqualTo(48);
        assertThat(response.autoApproveReads()).isFalse();
        assertThat(response.notifyChannels()).containsExactly("ch-1");
        assertThat(response.approvers()).hasSize(2);
        assertThat(response.approvers().get(0).userId()).isEqualTo(userId);
        assertThat(response.approvers().get(0).stage()).isEqualTo(1);
        assertThat(response.approvers().get(1).role()).isEqualTo(UserRoleType.REVIEWER);
        assertThat(response.createdAt()).isEqualTo(Instant.parse("2026-05-01T00:00:00Z"));
    }
}
