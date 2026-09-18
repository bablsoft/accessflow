package com.bablsoft.accessflow.serviceaccounts.internal.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceAccountsPropertiesTest {

    @Test
    void nullGraceFallsBackToTwentyFourHours() {
        assertThat(new ServiceAccountsProperties(null).rotationGrace()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void nonPositiveGraceFallsBackToTheDefault() {
        assertThat(new ServiceAccountsProperties(Duration.ZERO).rotationGrace())
                .isEqualTo(ServiceAccountsProperties.DEFAULT_ROTATION_GRACE);
        assertThat(new ServiceAccountsProperties(Duration.ofMinutes(-1)).rotationGrace())
                .isEqualTo(ServiceAccountsProperties.DEFAULT_ROTATION_GRACE);
    }

    @Test
    void explicitGraceIsKept() {
        assertThat(new ServiceAccountsProperties(Duration.ofMinutes(30)).rotationGrace())
                .isEqualTo(Duration.ofMinutes(30));
    }
}
