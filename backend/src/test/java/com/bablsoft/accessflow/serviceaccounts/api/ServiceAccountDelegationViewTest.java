package com.bablsoft.accessflow.serviceaccounts.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceAccountDelegationViewTest {

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    @Test
    void revokedWinsOverEverything() {
        assertThat(ServiceAccountDelegationView.statusAt(NOW, NOW.plusSeconds(10), NOW))
                .isEqualTo(ServiceAccountDelegationStatus.REVOKED);
    }

    @Test
    void expiredWhenTheExpiryHasPassedOrIsNow() {
        assertThat(ServiceAccountDelegationView.statusAt(null, NOW, NOW))
                .isEqualTo(ServiceAccountDelegationStatus.EXPIRED);
        assertThat(ServiceAccountDelegationView.statusAt(null, NOW.minusSeconds(1), NOW))
                .isEqualTo(ServiceAccountDelegationStatus.EXPIRED);
    }

    @Test
    void activeOtherwise() {
        assertThat(ServiceAccountDelegationView.statusAt(null, null, NOW))
                .isEqualTo(ServiceAccountDelegationStatus.ACTIVE);
        assertThat(ServiceAccountDelegationView.statusAt(null, NOW.plusSeconds(1), NOW))
                .isEqualTo(ServiceAccountDelegationStatus.ACTIVE);
    }
}
