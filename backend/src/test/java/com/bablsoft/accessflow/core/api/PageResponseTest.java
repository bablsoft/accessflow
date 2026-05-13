package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageResponseTest {

    @Test
    void holdsConstructorArgumentsAndCopiesContent() {
        var original = new java.util.ArrayList<>(List.of("a", "b"));
        var response = new PageResponse<>(original, 0, 10, 2L, 1);

        original.add("mutated-after-construction");

        assertThat(response.content()).containsExactly("a", "b");
        assertThat(response.page()).isEqualTo(0);
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.totalElements()).isEqualTo(2L);
        assertThat(response.totalPages()).isEqualTo(1);
    }

    @Test
    void nullContentBecomesEmpty() {
        var response = new PageResponse<String>(null, 0, 10, 0L, 0);
        assertThat(response.content()).isEmpty();
    }

    @Test
    void mapPreservesPaginationMetadataAndAppliesMapper() {
        var response = new PageResponse<>(List.of(1, 2, 3), 1, 3, 6L, 2);

        PageResponse<String> mapped = response.map(i -> "v" + i);

        assertThat(mapped.content()).containsExactly("v1", "v2", "v3");
        assertThat(mapped.page()).isEqualTo(1);
        assertThat(mapped.size()).isEqualTo(3);
        assertThat(mapped.totalElements()).isEqualTo(6L);
        assertThat(mapped.totalPages()).isEqualTo(2);
    }

    @Test
    void emptyProducesZeroTotalsAndContent() {
        var empty = PageResponse.<String>empty(2, 50);
        assertThat(empty.content()).isEmpty();
        assertThat(empty.page()).isEqualTo(2);
        assertThat(empty.size()).isEqualTo(50);
        assertThat(empty.totalElements()).isZero();
        assertThat(empty.totalPages()).isZero();
    }

    @Test
    void rejectsNegativePage() {
        assertThatThrownBy(() -> new PageResponse<>(List.of(), -1, 10, 0L, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsZeroOrNegativeSize() {
        assertThatThrownBy(() -> new PageResponse<>(List.of(), 0, 0, 0L, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeTotals() {
        assertThatThrownBy(() -> new PageResponse<>(List.of(), 0, 10, -1L, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PageResponse<>(List.of(), 0, 10, 0L, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
