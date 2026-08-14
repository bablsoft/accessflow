package com.bablsoft.accessflow.core.internal;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * A {@link Pageable} whose window starts at an arbitrary zero-based offset instead of a page
 * boundary (#621) — SCIM's {@code startIndex} is not required to be page-aligned.
 */
final class OffsetPageable implements Pageable {

    private final long offset;
    private final int limit;
    private final Sort sort;

    OffsetPageable(long offset, int limit, Sort sort) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        this.offset = offset;
        this.limit = limit;
        this.sort = sort == null ? Sort.unsorted() : sort;
    }

    @Override
    public int getPageNumber() {
        return (int) (offset / limit);
    }

    @Override
    public int getPageSize() {
        return limit;
    }

    @Override
    public long getOffset() {
        return offset;
    }

    @Override
    public Sort getSort() {
        return sort;
    }

    @Override
    public Pageable next() {
        return new OffsetPageable(offset + limit, limit, sort);
    }

    @Override
    public Pageable previousOrFirst() {
        return hasPrevious() ? new OffsetPageable(Math.max(0, offset - limit), limit, sort) : first();
    }

    @Override
    public Pageable first() {
        return new OffsetPageable(0, limit, sort);
    }

    @Override
    public Pageable withPage(int pageNumber) {
        return new OffsetPageable((long) pageNumber * limit, limit, sort);
    }

    @Override
    public boolean hasPrevious() {
        return offset > 0;
    }
}
