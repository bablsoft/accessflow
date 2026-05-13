package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.SortOrder;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Translates Spring MVC's bound {@link Pageable} into the library-agnostic
 * {@link PageRequest} that module {@code api/} services accept.
 */
final class SpringPageableAdapter {

    private SpringPageableAdapter() {}

    static PageRequest toPageRequest(Pageable pageable) {
        if (pageable == null || !pageable.isPaged()) {
            return PageRequest.of(0, Integer.MAX_VALUE);
        }
        var sort = pageable.getSort().stream()
                .map(SpringPageableAdapter::toSortOrder)
                .toList();
        return new PageRequest(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }

    private static SortOrder toSortOrder(Sort.Order order) {
        var direction = order.getDirection() == Sort.Direction.ASC
                ? SortOrder.Direction.ASC : SortOrder.Direction.DESC;
        return new SortOrder(order.getProperty(), direction);
    }
}
