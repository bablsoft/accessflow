package com.bablsoft.accessflow.core.internal;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AppliesToMatcherTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();

    @Test
    void emptyScopeMatchesEveryone() {
        assertThat(AppliesToMatcher.matches(null, null, null, userId, null, Set.of())).isTrue();
        assertThat(AppliesToMatcher.matches(new String[0], new UUID[0], new UUID[0], userId, null,
                Set.of())).isTrue();
    }

    @Test
    void matchesOnAnyOneList() {
        assertThat(AppliesToMatcher.matches(new String[]{" analyst "}, null, null, userId, "ANALYST",
                Set.of())).isTrue();
        assertThat(AppliesToMatcher.matches(null, null, new UUID[]{userId}, userId, null, Set.of()))
                .isTrue();
        assertThat(AppliesToMatcher.matches(null, new UUID[]{groupId}, null, userId, null,
                Set.of(groupId))).isTrue();
    }

    @Test
    void rejectsWhenNoListMatches() {
        assertThat(AppliesToMatcher.matches(new String[]{"REVIEWER", null}, new UUID[]{groupId},
                new UUID[]{UUID.randomUUID()}, userId, "ANALYST", Set.of())).isFalse();
        assertThat(AppliesToMatcher.matches(new String[]{"ANALYST"}, null, null, userId, null,
                Set.of())).isFalse();
    }
}
