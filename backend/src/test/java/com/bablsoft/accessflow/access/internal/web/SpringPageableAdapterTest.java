package com.bablsoft.accessflow.access.internal.web;

import com.bablsoft.accessflow.core.api.SortOrder;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;

class SpringPageableAdapterTest {

    @Test
    void nullPageableYieldsUnpagedDefault() {
        var pr = SpringPageableAdapter.toPageRequest(null);
        assertThat(pr.page()).isZero();
        assertThat(pr.size()).isEqualTo(Integer.MAX_VALUE);
        assertThat(pr.sort()).isEmpty();
    }

    @Test
    void unpagedYieldsDefault() {
        var pr = SpringPageableAdapter.toPageRequest(Pageable.unpaged());
        assertThat(pr.page()).isZero();
        assertThat(pr.size()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void pagedWithAscAndDescSortIsTranslated() {
        var pageable = org.springframework.data.domain.PageRequest.of(2, 15,
                Sort.by(Sort.Order.asc("createdAt"), Sort.Order.desc("status")));
        var pr = SpringPageableAdapter.toPageRequest(pageable);
        assertThat(pr.page()).isEqualTo(2);
        assertThat(pr.size()).isEqualTo(15);
        assertThat(pr.sort()).hasSize(2);
        assertThat(pr.sort().get(0).property()).isEqualTo("createdAt");
        assertThat(pr.sort().get(0).direction()).isEqualTo(SortOrder.Direction.ASC);
        assertThat(pr.sort().get(1).property()).isEqualTo("status");
        assertThat(pr.sort().get(1).direction()).isEqualTo(SortOrder.Direction.DESC);
    }
}
