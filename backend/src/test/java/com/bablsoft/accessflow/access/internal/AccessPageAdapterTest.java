package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.SortOrder;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Sort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AccessPageAdapterTest {

    @Test
    void nullRequestIsUnpaged() {
        assertThat(AccessPageAdapter.toSpringPageable(null).isPaged()).isFalse();
    }

    @Test
    void pagedWithoutSort() {
        var pageable = AccessPageAdapter.toSpringPageable(PageRequest.of(2, 10));
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(10);
        assertThat(pageable.getSort().isSorted()).isFalse();
    }

    @Test
    void pagedWithAscAndDescSort() {
        var pageable = AccessPageAdapter.toSpringPageable(new PageRequest(0, 20,
                List.of(SortOrder.asc("createdAt"), SortOrder.desc("status"))));
        var orders = pageable.getSort().toList();
        assertThat(orders).hasSize(2);
        assertThat(orders.get(0).getProperty()).isEqualTo("createdAt");
        assertThat(orders.get(0).getDirection()).isEqualTo(Sort.Direction.ASC);
        assertThat(orders.get(1).getProperty()).isEqualTo("status");
        assertThat(orders.get(1).getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void toPageResponseCopiesPageMetadata() {
        var spring = new PageImpl<>(List.of("a", "b"),
                org.springframework.data.domain.PageRequest.of(1, 2), 5);
        var response = AccessPageAdapter.toPageResponse(spring);
        assertThat(response.content()).containsExactly("a", "b");
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(5);
        assertThat(response.totalPages()).isEqualTo(3);
    }
}
