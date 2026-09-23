package com.bablsoft.accessflow.schemachange.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftScanView;

import java.util.List;

public record SchemaDriftScanPageResponse(
        List<SchemaDriftScanResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    static SchemaDriftScanPageResponse from(PageResponse<SchemaDriftScanView> page) {
        return new SchemaDriftScanPageResponse(
                page.content().stream().map(SchemaDriftScanResponse::from).toList(),
                page.page(), page.size(), page.totalElements(), page.totalPages());
    }
}
