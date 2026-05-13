package com.bablsoft.accessflow.core.api;

import java.util.List;

/**
 * Library-agnostic pagination request used by all module {@code api/} services. The
 * service implementation is responsible for translating this into whatever its persistence
 * layer expects (e.g. Spring Data {@code Pageable}) — see {@code core.internal.PageAdapter}.
 */
public record PageRequest(int page, int size, List<SortOrder> sort) {

    public PageRequest {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be > 0");
        }
        sort = sort == null ? List.of() : List.copyOf(sort);
    }

    public static PageRequest of(int page, int size) {
        return new PageRequest(page, size, List.of());
    }

    public static PageRequest of(int page, int size, SortOrder... sort) {
        return new PageRequest(page, size, List.of(sort));
    }
}
