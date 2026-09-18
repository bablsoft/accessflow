package com.bablsoft.accessflow.serviceaccounts.internal.persistence.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceAccountDelegatedPrincipalEntityTest {

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    @Test
    void createdAtDefaultsToNow() {
        assertThat(new ServiceAccountDelegatedPrincipalEntity().getCreatedAt()).isNotNull();
    }

    @Test
    void liveMeansUnrevokedAndUnexpired() {
        var row = new ServiceAccountDelegatedPrincipalEntity();
        assertThat(row.isLiveAt(NOW)).isTrue();

        row.setExpiresAt(NOW.plusSeconds(1));
        assertThat(row.isLiveAt(NOW)).isTrue();

        row.setExpiresAt(NOW);
        assertThat(row.isLiveAt(NOW)).isFalse();

        row.setExpiresAt(null);
        row.setRevokedAt(NOW);
        assertThat(row.isLiveAt(NOW)).isFalse();
    }
}
