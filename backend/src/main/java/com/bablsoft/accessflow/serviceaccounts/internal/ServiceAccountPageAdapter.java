package com.bablsoft.accessflow.serviceaccounts.internal;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.SortOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Bridges the library-agnostic {@link PageRequest}/{@link PageResponse} types and Spring Data's
 * {@code Pageable}/{@code Page} inside the serviceaccounts module (core's PageAdapter is
 * module-private). Only {@code createdAt} is sortable — the account's user columns live in another
 * module and are joined after paging — so every other requested property is dropped and an empty
 * sort becomes newest-first.
 */
final class ServiceAccountPageAdapter {

    static final String SORTABLE_PROPERTY = "createdAt";

    private ServiceAccountPageAdapter() {
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
        var orders = request.sort().stream()
                .filter(order -> SORTABLE_PROPERTY.equals(order.property()))
                .map(ServiceAccountPageAdapter::toSpringOrder)
                .toList();
        if (orders.isEmpty()) {
            return Sort.by(Sort.Direction.DESC, SORTABLE_PROPERTY);
        }
        return Sort.by(orders);
    }

    private static Sort.Order toSpringOrder(SortOrder sortOrder) {
        var direction = sortOrder.direction() == SortOrder.Direction.ASC
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        return new Sort.Order(direction, sortOrder.property());
    }
}
