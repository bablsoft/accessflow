package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.SortOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Bridges the library-agnostic {@link PageRequest}/{@link PageResponse} types and Spring Data's
 * {@code Pageable}/{@code Page} inside the access module (core's PageAdapter is module-private).
 */
final class AccessPageAdapter {

    private AccessPageAdapter() {
    }

    static Pageable toSpringPageable(PageRequest request) {
        if (request == null) {
            return Pageable.unpaged();
        }
        return org.springframework.data.domain.PageRequest.of(request.page(), request.size(),
                toSpringSort(request));
    }

    static <T> PageResponse<T> toPageResponse(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    private static Sort toSpringSort(PageRequest request) {
        if (request.sort().isEmpty()) {
            return Sort.unsorted();
        }
        return Sort.by(request.sort().stream().map(AccessPageAdapter::toSpringOrder).toList());
    }

    private static Sort.Order toSpringOrder(SortOrder sortOrder) {
        var direction = sortOrder.direction() == SortOrder.Direction.ASC
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        return new Sort.Order(direction, sortOrder.property());
    }
}
