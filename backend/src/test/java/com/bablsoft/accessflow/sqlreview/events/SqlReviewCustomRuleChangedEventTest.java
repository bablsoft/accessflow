package com.bablsoft.accessflow.sqlreview.events;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlReviewCustomRuleChangedEventTest {

    @Test
    void carriesTheOrganizationAndRequiresIt() {
        var org = UUID.randomUUID();

        assertThat(new SqlReviewCustomRuleChangedEvent(org).organizationId()).isEqualTo(org);
        assertThatThrownBy(() -> new SqlReviewCustomRuleChangedEvent(null))
                .isInstanceOf(NullPointerException.class);
    }
}
