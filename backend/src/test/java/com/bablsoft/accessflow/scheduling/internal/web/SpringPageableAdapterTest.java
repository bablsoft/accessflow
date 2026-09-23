package com.bablsoft.accessflow.scheduling.internal.web;

import com.bablsoft.accessflow.core.api.SortOrder;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;

class SpringPageableAdapterTest {

    @Test
    void mapsPageSizeAndSort() {
        var request = SpringPageableAdapter.toPageRequest(PageRequest.of(2, 15,
                Sort.by(Sort.Order.asc("jobName"), Sort.Order.desc("startedAt"))));

        assertThat(request.page()).isEqualTo(2);
        assertThat(request.size()).isEqualTo(15);
        assertThat(request.sort()).containsExactly(
                new SortOrder("jobName", SortOrder.Direction.ASC),
                new SortOrder("startedAt", SortOrder.Direction.DESC));
    }

    @Test
    void unpagedAndNullBecomeOneUnboundedPage() {
        assertThat(SpringPageableAdapter.toPageRequest(Pageable.unpaged()).size()).isEqualTo(Integer.MAX_VALUE);
        assertThat(SpringPageableAdapter.toPageRequest(null).page()).isZero();
    }
}
