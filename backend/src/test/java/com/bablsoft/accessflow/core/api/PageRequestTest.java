package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageRequestTest {

    @Test
    void ofTwoArgsBuildsUnsortedPageRequest() {
        var req = PageRequest.of(2, 50);
        assertThat(req.page()).isEqualTo(2);
        assertThat(req.size()).isEqualTo(50);
        assertThat(req.sort()).isEmpty();
    }

    @Test
    void ofVarargsCopiesSortIntoImmutableList() {
        var orders = new SortOrder[] {
                SortOrder.asc("createdAt"), SortOrder.desc("id")
        };
        var req = PageRequest.of(0, 20, orders);
        assertThat(req.sort()).containsExactly(orders[0], orders[1]);
        assertThatThrownBy(() -> req.sort().add(SortOrder.asc("x")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullSortListBecomesEmpty() {
        var req = new PageRequest(0, 10, null);
        assertThat(req.sort()).isEmpty();
    }

    @Test
    void rejectsNegativePage() {
        assertThatThrownBy(() -> new PageRequest(-1, 10, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsZeroOrNegativeSize() {
        assertThatThrownBy(() -> new PageRequest(0, 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PageRequest(0, -5, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
