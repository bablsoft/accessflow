package com.bablsoft.accessflow.security.internal.templates;

import com.bablsoft.accessflow.core.api.UserRoleType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewPlanTemplatesTest {

    @Test
    void allReturnsExpectedKeysInOrder() {
        var keys = ReviewPlanTemplates.all().stream().map(ReviewPlanTemplate::key).toList();

        assertThat(keys).containsExactly(
                "STRICT_WRITES_2_APPROVALS",
                "LENIENT_READS_AUTO_APPROVED",
                "AI_ONLY_NO_HUMAN",
                "STANDARD_AI_PLUS_ONE_REVIEWER");
    }

    @Test
    void everyTemplateHasNameAndDescription() {
        assertThat(ReviewPlanTemplates.all())
                .allSatisfy(t -> {
                    assertThat(t.name()).isNotBlank();
                    assertThat(t.description()).isNotBlank();
                    assertThat(t.defaults()).isNotNull();
                });
    }

    @Test
    void strictTemplateRequiresTwoReviewers() {
        var template = templateByKey("STRICT_WRITES_2_APPROVALS");

        assertThat(template.defaults().requiresAiReview()).isTrue();
        assertThat(template.defaults().requiresHumanApproval()).isTrue();
        assertThat(template.defaults().minApprovalsRequired()).isEqualTo(2);
        assertThat(template.defaults().approvalTimeoutHours()).isEqualTo(24);
        assertThat(template.defaults().autoApproveReads()).isFalse();
        assertThat(template.defaults().approvers())
                .extracting(ReviewPlanTemplate.ApproverDefault::stage)
                .containsExactly(1, 2);
        assertThat(template.defaults().approvers())
                .allSatisfy(a -> assertThat(a.role()).isEqualTo(UserRoleType.REVIEWER));
    }

    @Test
    void lenientTemplateAutoApprovesReadsWithSingleReviewer() {
        var template = templateByKey("LENIENT_READS_AUTO_APPROVED");

        assertThat(template.defaults().requiresHumanApproval()).isTrue();
        assertThat(template.defaults().minApprovalsRequired()).isEqualTo(1);
        assertThat(template.defaults().autoApproveReads()).isTrue();
        assertThat(template.defaults().approvers()).hasSize(1);
        assertThat(template.defaults().approvers().get(0).role()).isEqualTo(UserRoleType.REVIEWER);
        assertThat(template.defaults().approvers().get(0).stage()).isEqualTo(1);
    }

    @Test
    void aiOnlyTemplateHasNoApproversAndNoHumanApproval() {
        var template = templateByKey("AI_ONLY_NO_HUMAN");

        assertThat(template.defaults().requiresAiReview()).isTrue();
        assertThat(template.defaults().requiresHumanApproval()).isFalse();
        assertThat(template.defaults().minApprovalsRequired()).isEqualTo(1);
        assertThat(template.defaults().autoApproveReads()).isFalse();
        assertThat(template.defaults().approvers()).isEmpty();
    }

    @Test
    void standardTemplateHasOneReviewerWithAutoApprove() {
        var template = templateByKey("STANDARD_AI_PLUS_ONE_REVIEWER");

        assertThat(template.defaults().requiresAiReview()).isTrue();
        assertThat(template.defaults().requiresHumanApproval()).isTrue();
        assertThat(template.defaults().minApprovalsRequired()).isEqualTo(1);
        assertThat(template.defaults().autoApproveReads()).isTrue();
        assertThat(template.defaults().approvers()).hasSize(1);
        assertThat(template.defaults().approvers().get(0).role()).isEqualTo(UserRoleType.REVIEWER);
        assertThat(template.defaults().approvers().get(0).stage()).isEqualTo(1);
    }

    @Test
    void allReturnsImmutableList() {
        var list = ReviewPlanTemplates.all();

        assertThat(list).isUnmodifiable();
    }

    private static ReviewPlanTemplate templateByKey(String key) {
        return ReviewPlanTemplates.all().stream()
                .filter(t -> t.key().equals(key))
                .findFirst()
                .orElseThrow();
    }
}
