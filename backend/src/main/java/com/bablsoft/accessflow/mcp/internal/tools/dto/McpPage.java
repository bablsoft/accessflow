package com.bablsoft.accessflow.mcp.internal.tools.dto;

import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.List;
import java.util.function.Function;

/** Paginated MCP response — mirrors the shape of {@link PageResponse} but flattened for clients. */
public record McpPage<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

    public static <S, T> McpPage<T> from(PageResponse<S> page, Function<S, T> mapper) {
        return new McpPage<>(
                page.content().stream().map(mapper).toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages()
        );
    }
}
