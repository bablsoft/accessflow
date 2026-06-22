package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.AnomalyBadgeView;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnomalyBadgeResponseTest {

    @Test
    void fromMapsOpenCountAndMaxScore() {
        var response = AnomalyBadgeResponse.from(new AnomalyBadgeView(3, 7.5));
        assertThat(response.openCount()).isEqualTo(3);
        assertThat(response.maxScore()).isEqualTo(7.5);
    }

    @Test
    void fromMapsNoneBadge() {
        var response = AnomalyBadgeResponse.from(AnomalyBadgeView.none());
        assertThat(response.openCount()).isZero();
        assertThat(response.maxScore()).isZero();
    }
}
