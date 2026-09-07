package com.bablsoft.accessflow.ai.internal.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Relaxed binding leaves every unset property null, and a nonsense value (a zero lock, a batch of 0)
 * would be a silent misconfiguration rather than a startup failure: a zero batch size loops forever
 * adding nothing, and a zero lock duration expires before the pass it guards. The compact constructor
 * is the only place that can catch either.
 */
class HelpAgentPropertiesTest {

    @Test
    void keepsExplicitValues() {
        var properties = new HelpAgentProperties(false, 128, Duration.ofHours(1));

        assertThat(properties.indexOnStartup()).isFalse();
        assertThat(properties.indexBatchSize()).isEqualTo(128);
        assertThat(properties.indexLockAtMostFor()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void suppliesDefaultsForUnsetProperties() {
        var properties = new HelpAgentProperties(null, null, null);

        assertThat(properties.indexOnStartup()).isTrue();
        assertThat(properties.indexBatchSize()).isEqualTo(64);
        assertThat(properties.indexLockAtMostFor()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void rejectsABatchSizeThatWouldNeverAddAnything() {
        assertThat(new HelpAgentProperties(true, 0, null).indexBatchSize()).isEqualTo(64);
        assertThat(new HelpAgentProperties(true, -5, null).indexBatchSize()).isEqualTo(64);
    }

    @Test
    void rejectsALockThatWouldExpireBeforeThePassItGuards() {
        assertThat(new HelpAgentProperties(true, 64, Duration.ZERO).indexLockAtMostFor())
                .isEqualTo(Duration.ofMinutes(30));
        assertThat(new HelpAgentProperties(true, 64, Duration.ofSeconds(-1)).indexLockAtMostFor())
                .isEqualTo(Duration.ofMinutes(30));
    }
}
