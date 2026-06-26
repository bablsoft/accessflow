package com.bablsoft.accessflow.attestation.internal;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.SortOrder;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AttestationPageAdapterTest {

    @Test
    void toSpringPageableUnpagedForNull() {
        assertThat(AttestationPageAdapter.toSpringPageable(null)).isEqualTo(Pageable.unpaged());
    }

    @Test
    void toSpringPageableMapsPageSizeAndSort() {
        var request = new PageRequest(2, 25,
                List.of(new SortOrder("createdAt", SortOrder.Direction.DESC)));
        var pageable = AttestationPageAdapter.toSpringPageable(request);
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(25);
        assertThat(pageable.getSort().getOrderFor("createdAt").getDirection())
                .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void toSpringPageableUnsortedWhenNoSort() {
        var pageable = AttestationPageAdapter.toSpringPageable(PageRequest.of(0, 10));
        assertThat(pageable.getSort().isSorted()).isFalse();
    }

    @Test
    void toPageResponseCopiesPaginationMetadata() {
        var page = new PageImpl<>(List.of("a", "b"),
                org.springframework.data.domain.PageRequest.of(0, 2), 5);
        var response = AttestationPageAdapter.toPageResponse(page);
        assertThat(response.content()).containsExactly("a", "b");
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(5);
        assertThat(response.totalPages()).isEqualTo(3);
    }
}
