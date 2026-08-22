package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.core.api.SortOrder;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;

class SpringPageableAdapterTest {

    @Test
    void nullPageableProducesPageZeroMaxSize() {
        var request = SpringPageableAdapter.toPageRequest(null);
        assertThat(request.page()).isZero();
        assertThat(request.size()).isEqualTo(Integer.MAX_VALUE);
        assertThat(request.sort()).isEmpty();
    }

    @Test
    void unpagedPageableProducesPageZeroMaxSize() {
        var request = SpringPageableAdapter.toPageRequest(Pageable.unpaged());
        assertThat(request.page()).isZero();
        assertThat(request.size()).isEqualTo(Integer.MAX_VALUE);
        assertThat(request.sort()).isEmpty();
    }

    @Test
    void pagedPageableWithoutSortPassesThrough() {
        var request = SpringPageableAdapter.toPageRequest(PageRequest.of(2, 25));
        assertThat(request.page()).isEqualTo(2);
        assertThat(request.size()).isEqualTo(25);
        assertThat(request.sort()).isEmpty();
    }

    @Test
    void mapsAscendingAndDescendingSortOrders() {
        var pageable = PageRequest.of(0, 10,
                Sort.by(Sort.Order.asc("createdAt"), Sort.Order.desc("name")));
        var request = SpringPageableAdapter.toPageRequest(pageable);
        assertThat(request.sort()).hasSize(2);
        assertThat(request.sort().get(0).property()).isEqualTo("createdAt");
        assertThat(request.sort().get(0).direction()).isEqualTo(SortOrder.Direction.ASC);
        assertThat(request.sort().get(1).property()).isEqualTo("name");
        assertThat(request.sort().get(1).direction()).isEqualTo(SortOrder.Direction.DESC);
    }
}
