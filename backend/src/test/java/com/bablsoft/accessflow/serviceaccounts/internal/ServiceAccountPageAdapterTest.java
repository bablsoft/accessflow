package com.bablsoft.accessflow.serviceaccounts.internal;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.SortOrder;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Sort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceAccountPageAdapterTest {

    @Test
    void nullRequestIsUnpaged() {
        assertThat(ServiceAccountPageAdapter.toSpringPageable(null).isPaged()).isFalse();
    }

    @Test
    void emptySortDefaultsToNewestFirst() {
        var pageable = ServiceAccountPageAdapter.toSpringPageable(PageRequest.of(2, 5));
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(5);
        assertThat(pageable.getSort().getOrderFor("createdAt"))
                .isNotNull().extracting(Sort.Order::getDirection).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void onlyCreatedAtIsSortableAndDirectionIsKept() {
        var pageable = ServiceAccountPageAdapter.toSpringPageable(PageRequest.of(0, 10,
                new SortOrder("email", SortOrder.Direction.ASC),
                new SortOrder("createdAt", SortOrder.Direction.ASC)));
        assertThat(pageable.getSort().stream().toList()).singleElement().satisfies(order -> {
            assertThat(order.getProperty()).isEqualTo("createdAt");
            assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);
        });

        var descending = ServiceAccountPageAdapter.toSpringPageable(PageRequest.of(0, 10,
                new SortOrder("createdAt", SortOrder.Direction.DESC)));
        assertThat(descending.getSort().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);

        var unsortable = ServiceAccountPageAdapter.toSpringPageable(PageRequest.of(0, 10,
                new SortOrder("email", SortOrder.Direction.ASC)));
        assertThat(unsortable.getSort().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(unsortable.getSort().getOrderFor("email")).isNull();
    }

    @Test
    void pageResponseCopiesTheSpringPageShape() {
        var page = new PageImpl<>(List.of("a", "b"),
                org.springframework.data.domain.PageRequest.of(1, 2), 5);
        var response = ServiceAccountPageAdapter.toPageResponse(page);
        assertThat(response.content()).containsExactly("a", "b");
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(5);
        assertThat(response.totalPages()).isEqualTo(3);
    }
}
