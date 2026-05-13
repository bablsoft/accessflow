package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.SortOrder;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageAdapterTest {

    @Test
    void toSpringPageableHandlesNullAsUnpaged() {
        assertThat(PageAdapter.toSpringPageable(null)).isEqualTo(Pageable.unpaged());
    }

    @Test
    void toSpringPageablePreservesPageAndSize() {
        var pageable = PageAdapter.toSpringPageable(PageRequest.of(3, 25));
        assertThat(pageable.getPageNumber()).isEqualTo(3);
        assertThat(pageable.getPageSize()).isEqualTo(25);
        assertThat(pageable.getSort()).isEqualTo(Sort.unsorted());
    }

    @Test
    void toSpringPageableTranslatesSortOrders() {
        var pageable = PageAdapter.toSpringPageable(PageRequest.of(0, 10,
                SortOrder.asc("createdAt"), SortOrder.desc("id")));
        var sort = pageable.getSort();
        assertThat(sort).hasSize(2);
        assertThat(sort.getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.ASC);
        assertThat(sort.getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void toPageResponseCopiesAllFields() {
        var springPage = new PageImpl<>(List.of("a", "b"),
                org.springframework.data.domain.PageRequest.of(1, 5), 11L);
        var response = PageAdapter.toPageResponse(springPage);
        assertThat(response.content()).containsExactly("a", "b");
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(5);
        assertThat(response.totalElements()).isEqualTo(11L);
        assertThat(response.totalPages()).isEqualTo(springPage.getTotalPages());
    }
}
