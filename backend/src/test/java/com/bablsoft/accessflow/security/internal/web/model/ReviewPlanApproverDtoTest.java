package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.ReviewPlanView;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewPlanApproverDtoTest {

    @Test
    void fromMapsRule() {
        var userId = UUID.randomUUID();
        var rule = new ReviewPlanView.ApproverRule(userId, "REVIEWER", 2);

        var dto = ReviewPlanApproverDto.from(rule);

        assertThat(dto.userId()).isEqualTo(userId);
        assertThat(dto.role()).isEqualTo("REVIEWER");
        assertThat(dto.stage()).isEqualTo(2);
    }

    @Test
    void toRuleRoundTrips() {
        var dto = new ReviewPlanApproverDto(null, "ADMIN", 1);

        var rule = dto.toRule();

        assertThat(rule.userId()).isNull();
        assertThat(rule.role()).isEqualTo("ADMIN");
        assertThat(rule.stage()).isEqualTo(1);
    }
}
