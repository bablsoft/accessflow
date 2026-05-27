package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.internal.templates.ReviewPlanTemplate;
import com.bablsoft.accessflow.security.internal.templates.ReviewPlanTemplate.ApproverDefault;
import com.bablsoft.accessflow.security.internal.templates.ReviewPlanTemplate.Defaults;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewPlanTemplateResponseTest {

    @Test
    void fromMapsAllFields() {
        var template = new ReviewPlanTemplate(
                "MY_TEMPLATE",
                "My template",
                "A test template",
                new Defaults(
                        true,
                        false,
                        3,
                        48,
                        true,
                        List.of(new ApproverDefault(UserRoleType.ADMIN, 1),
                                new ApproverDefault(UserRoleType.REVIEWER, 2))
                )
        );

        var response = ReviewPlanTemplateResponse.from(template);

        assertThat(response.key()).isEqualTo("MY_TEMPLATE");
        assertThat(response.name()).isEqualTo("My template");
        assertThat(response.description()).isEqualTo("A test template");
        assertThat(response.defaults().requiresAiReview()).isTrue();
        assertThat(response.defaults().requiresHumanApproval()).isFalse();
        assertThat(response.defaults().minApprovalsRequired()).isEqualTo(3);
        assertThat(response.defaults().approvalTimeoutHours()).isEqualTo(48);
        assertThat(response.defaults().autoApproveReads()).isTrue();
        assertThat(response.defaults().approvers())
                .extracting(ReviewPlanTemplateResponse.TemplateApprover::role,
                        ReviewPlanTemplateResponse.TemplateApprover::stage)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(UserRoleType.ADMIN, 1),
                        org.assertj.core.groups.Tuple.tuple(UserRoleType.REVIEWER, 2));
    }

    @Test
    void fromHandlesTemplateWithNoApprovers() {
        var template = new ReviewPlanTemplate(
                "EMPTY",
                "Empty",
                "",
                new Defaults(true, false, 1, 24, false, List.of())
        );

        var response = ReviewPlanTemplateResponse.from(template);

        assertThat(response.defaults().approvers()).isEmpty();
    }
}
