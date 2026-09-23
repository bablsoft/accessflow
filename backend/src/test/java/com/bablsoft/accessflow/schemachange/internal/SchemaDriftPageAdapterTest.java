package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.SortOrder;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Sort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaDriftPageAdapterTest {

    @Test
    void anUnpagedOrNullRequestBecomesUnpaged() {
        assertThat(SchemaDriftPageAdapter.toSpringPageable(null).isPaged()).isFalse();
    }

    @Test
    void pageAndSizeCarryOver() {
        var pageable = SchemaDriftPageAdapter.toSpringPageable(PageRequest.of(2, 25));

        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(25);
        assertThat(pageable.getSort().isSorted()).isFalse();
    }

    @Test
    void sortOrdersAreTranslatedInBothDirections() {
        var pageable = SchemaDriftPageAdapter.toSpringPageable(PageRequest.of(0, 10,
                new SortOrder("lastSeenAt", SortOrder.Direction.DESC),
                new SortOrder("objectPath", SortOrder.Direction.ASC)));

        assertThat(pageable.getSort().getOrderFor("lastSeenAt").getDirection())
                .isEqualTo(Sort.Direction.DESC);
        assertThat(pageable.getSort().getOrderFor("objectPath").getDirection())
                .isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void aSpringPageBecomesAPageResponse() {
        var page = new PageImpl<>(List.of("a", "b"),
                org.springframework.data.domain.PageRequest.of(1, 2), 6);

        var response = SchemaDriftPageAdapter.toPageResponse(page);

        assertThat(response.content()).containsExactly("a", "b");
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(6);
        assertThat(response.totalPages()).isEqualTo(3);
    }
}
