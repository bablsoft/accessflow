package com.bablsoft.accessflow.core.internal;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OffsetPageableTest {

    @Test
    void exposesArbitraryOffset() {
        var pageable = new OffsetPageable(7, 3, Sort.by("id"));

        assertThat(pageable.getOffset()).isEqualTo(7);
        assertThat(pageable.getPageSize()).isEqualTo(3);
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getSort()).isEqualTo(Sort.by("id"));
    }

    @Test
    void navigationKeepsLimitAndSort() {
        var pageable = new OffsetPageable(5, 10, Sort.unsorted());

        assertThat(pageable.next().getOffset()).isEqualTo(15);
        assertThat(pageable.previousOrFirst().getOffset()).isZero();
        assertThat(pageable.first().getOffset()).isZero();
        assertThat(pageable.withPage(3).getOffset()).isEqualTo(30);
        assertThat(pageable.hasPrevious()).isTrue();
        assertThat(pageable.first().hasPrevious()).isFalse();
    }

    @Test
    void nullSortBecomesUnsorted() {
        assertThat(new OffsetPageable(0, 1, null).getSort()).isEqualTo(Sort.unsorted());
    }

    @Test
    void rejectsInvalidArguments() {
        assertThatThrownBy(() -> new OffsetPageable(-1, 10, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OffsetPageable(0, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
