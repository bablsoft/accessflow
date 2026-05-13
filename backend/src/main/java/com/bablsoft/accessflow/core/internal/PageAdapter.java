package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.SortOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Bridges AccessFlow's library-agnostic {@link PageRequest}/{@link PageResponse} types and
 * Spring Data's {@code Pageable}/{@code Page}. Lives in {@code core/internal} so the
 * {@code core/api} package stays free of Spring Data imports.
 */
public final class PageAdapter {

    private PageAdapter() {}

    public static Pageable toSpringPageable(PageRequest request) {
        if (request == null) {
            return Pageable.unpaged();
        }
        var sort = toSpringSort(request);
        return org.springframework.data.domain.PageRequest.of(request.page(), request.size(), sort);
    }

    public static <T> PageResponse<T> toPageResponse(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    private static Sort toSpringSort(PageRequest request) {
        if (request.sort().isEmpty()) {
            return Sort.unsorted();
        }
        var orders = request.sort().stream()
                .map(PageAdapter::toSpringOrder)
                .toList();
        return Sort.by(orders);
    }

    private static Sort.Order toSpringOrder(SortOrder sortOrder) {
        var direction = sortOrder.direction() == SortOrder.Direction.ASC
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        return new Sort.Order(direction, sortOrder.property());
    }
}
