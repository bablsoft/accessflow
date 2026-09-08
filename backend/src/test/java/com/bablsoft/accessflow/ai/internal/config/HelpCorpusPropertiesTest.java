package com.bablsoft.accessflow.ai.internal.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guard against the two-constructor trap: a second constructor on a bound record silently
 * unbinds every property, and the failure names pre-existing fields rather than the constructor that
 * caused it. Relaxed binding also leaves every unset property null, so the compact constructor is the
 * only place a default can come from — and the one that matters most is {@code remoteRefreshEnabled},
 * where a null read as anything but "off" would start making outbound requests nobody asked for.
 */
class HelpCorpusPropertiesTest {

    @Test
    void suppliesEveryDefaultForAnUnconfiguredInstall() {
        var properties = new HelpCorpusProperties(null, null, null, null);

        assertThat(properties.remoteRefreshEnabled()).isFalse();
        assertThat(properties.indexUrl()).isEqualTo(HelpCorpusProperties.DEFAULT_INDEX_URL);
        assertThat(properties.cacheDir()).isEqualTo(
                Paths.get(System.getProperty("user.home"), ".accessflow", "help-corpus"));
        assertThat(properties.offline()).isFalse();
    }

    @Test
    void keepsExplicitValues() {
        var properties = new HelpCorpusProperties(true, "https://mirror.internal/index.json",
                Path.of("/var/lib/accessflow/drivers/help-corpus"), true);

        assertThat(properties.remoteRefreshEnabled()).isTrue();
        assertThat(properties.indexUrl()).isEqualTo("https://mirror.internal/index.json");
        assertThat(properties.cacheDir())
                .isEqualTo(Path.of("/var/lib/accessflow/drivers/help-corpus"));
        assertThat(properties.offline()).isTrue();
    }

    @Test
    void treatsABlankIndexUrlAsUnset() {
        // An empty env var is how a Helm values override that was left blank arrives.
        assertThat(new HelpCorpusProperties(true, "   ", null, null).indexUrl())
                .isEqualTo(HelpCorpusProperties.DEFAULT_INDEX_URL);
    }
}
