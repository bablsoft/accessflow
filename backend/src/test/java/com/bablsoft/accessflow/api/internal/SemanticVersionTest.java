package com.bablsoft.accessflow.api.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticVersionTest {

    @Test
    void parsesPlainAndPrefixedVersions() {
        assertThat(SemanticVersion.parse("2.4.0")).contains(new SemanticVersion(2, 4, 0, null));
        assertThat(SemanticVersion.parse("v2.4.0")).contains(new SemanticVersion(2, 4, 0, null));
        assertThat(SemanticVersion.parse(" 2.4.0\n")).contains(new SemanticVersion(2, 4, 0, null));
        assertThat(SemanticVersion.parse("2.4.0+build.7")).contains(new SemanticVersion(2, 4, 0, null));
    }

    @Test
    void parsesPreReleaseSuffixes() {
        assertThat(SemanticVersion.parse("1.0.0-SNAPSHOT")).contains(new SemanticVersion(1, 0, 0, "SNAPSHOT"));
        assertThat(SemanticVersion.parse("2.5.0-beta.1")).contains(new SemanticVersion(2, 5, 0, "beta.1"));
        assertThat(SemanticVersion.parse("1.0.0-SNAPSHOT").orElseThrow().isPreRelease()).isTrue();
        assertThat(SemanticVersion.parse("1.0.0").orElseThrow().isPreRelease()).isFalse();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "latest", "2.4", "2.4.0.1", "2.4.x", "2.4.0-", "99999999999.0.0"})
    void rejectsNonSemver(String raw) {
        assertThat(SemanticVersion.parse(raw)).isEmpty();
    }

    @Test
    void comparesNumericallyNotLexically() {
        var v2_9 = SemanticVersion.parse("2.9.0").orElseThrow();
        var v2_10 = SemanticVersion.parse("2.10.0").orElseThrow();

        assertThat(v2_10.isNewerThan(v2_9)).isTrue();
        assertThat(v2_9.isNewerThan(v2_10)).isFalse();
        assertThat("2.10.0".compareTo("2.9.0")).isNegative(); // the trap this guards against
    }

    @Test
    void comparesEachComponentInOrder() {
        var base = SemanticVersion.parse("2.4.1").orElseThrow();

        assertThat(SemanticVersion.parse("3.0.0").orElseThrow().isNewerThan(base)).isTrue();
        assertThat(SemanticVersion.parse("2.5.0").orElseThrow().isNewerThan(base)).isTrue();
        assertThat(SemanticVersion.parse("2.4.2").orElseThrow().isNewerThan(base)).isTrue();
        assertThat(SemanticVersion.parse("2.4.0").orElseThrow().isNewerThan(base)).isFalse();
        assertThat(SemanticVersion.parse("1.9.9").orElseThrow().isNewerThan(base)).isFalse();
    }

    @Test
    void equalVersionsAreNotNewerThanEachOther() {
        var a = SemanticVersion.parse("2.4.0").orElseThrow();
        var b = SemanticVersion.parse("v2.4.0").orElseThrow();

        assertThat(a.compareTo(b)).isZero();
        assertThat(a.isNewerThan(b)).isFalse();
        assertThat(b.isNewerThan(a)).isFalse();
    }

    @Test
    void preReleaseSortsBelowItsRelease() {
        var release = SemanticVersion.parse("2.5.0").orElseThrow();
        var beta = SemanticVersion.parse("2.5.0-beta.1").orElseThrow();
        var beta2 = SemanticVersion.parse("2.5.0-beta.2").orElseThrow();

        assertThat(release.isNewerThan(beta)).isTrue();
        assertThat(beta.isNewerThan(release)).isFalse();
        assertThat(beta2.isNewerThan(beta)).isTrue();
        assertThat(beta.compareTo(beta2)).isNegative();
    }
}
