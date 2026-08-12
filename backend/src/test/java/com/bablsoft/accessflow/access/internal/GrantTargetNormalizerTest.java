package com.bablsoft.accessflow.access.internal;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GrantTargetNormalizerTest {

    @Test
    void normalizeStripsQuotesTrimsAndLowercases() {
        assertThat(GrantTargetNormalizer.normalize("  \"Public\".\"Users\"  "))
                .isEqualTo("public.users");
        assertThat(GrantTargetNormalizer.normalize("`Orders`")).isEqualTo("orders");
        assertThat(GrantTargetNormalizer.normalize("[dbo].[Items]")).isEqualTo("dbo.items");
        assertThat(GrantTargetNormalizer.normalize(null)).isEmpty();
    }

    @Test
    void normalizeAllDropsBlanksAndDuplicatesPreservingOrder() {
        assertThat(GrantTargetNormalizer.normalizeAll(List.of("Users", "  ", "users", "Orders")))
                .containsExactly("users", "orders");
        assertThat(GrantTargetNormalizer.normalizeAll(null)).isEmpty();
        assertThat(GrantTargetNormalizer.normalizeAll(List.of())).isEmpty();
        assertThat(GrantTargetNormalizer.normalizeAll(Arrays.asList("ok", null))).containsExactly("ok");
    }

    @Test
    void matchesExactlyOrOnTheBareNameWhenEitherSideIsUnqualified() {
        assertThat(GrantTargetNormalizer.matches("public.users", "public.users")).isTrue();
        assertThat(GrantTargetNormalizer.matches("public.users", "users")).isTrue();
        assertThat(GrantTargetNormalizer.matches("users", "public.users")).isTrue();
    }

    /** Two differently-qualified names are different tables — matching them would over-credit use. */
    @Test
    void doesNotMatchAcrossSchemas() {
        assertThat(GrantTargetNormalizer.matches("sales.orders", "public.orders")).isFalse();
    }

    @Test
    void doesNotMatchEmptyIdentifiers() {
        assertThat(GrantTargetNormalizer.matches("", "users")).isFalse();
        assertThat(GrantTargetNormalizer.matches("users", "")).isFalse();
    }

    @Test
    void countsGrantedEntriesExercised() {
        var granted = Set.of("public.users", "public.orders", "public.items");
        assertThat(GrantTargetNormalizer.countGrantedExercised(granted, Set.of("users", "orders")))
                .isEqualTo(2);
        assertThat(GrantTargetNormalizer.countGrantedExercised(granted, Set.of())).isZero();
        assertThat(GrantTargetNormalizer.countGrantedExercised(Set.of(), Set.of("users"))).isZero();
    }

    /**
     * Counting granted entries rather than used ones is what keeps a query against a table outside
     * the allow-list — possible for a QUERY_ADMIN holder — from inflating the exercised count.
     */
    @Test
    void usageOutsideTheAllowListNeverExceedsTheGrantedScope() {
        var granted = Set.of("public.users");
        var used = Set.of("public.users", "public.secrets", "public.audit");
        assertThat(GrantTargetNormalizer.countGrantedExercised(granted, used)).isEqualTo(1);
    }

    /** One used entry matching two granted entries must not be double-counted. */
    @Test
    void countsEachGrantedEntryAtMostOnce() {
        var granted = Set.of("users", "public.users");
        assertThat(GrantTargetNormalizer.countGrantedExercised(granted, Set.of("public.users")))
                .isEqualTo(2);
        assertThat(GrantTargetNormalizer.countGrantedExercised(Set.of("users"),
                Set.of("public.users", "sales.users"))).isEqualTo(1);
    }
}
