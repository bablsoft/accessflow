package com.bablsoft.accessflow.attestation.internal.web;

import com.bablsoft.accessflow.attestation.api.AttestationItemView;
import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.List;

public record AttestationItemPageResponse(
        List<AttestationItemResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static AttestationItemPageResponse from(PageResponse<AttestationItemView> page) {
        return new AttestationItemPageResponse(
                page.content().stream().map(AttestationItemResponse::from).toList(),
                page.page(), page.size(), page.totalElements(), page.totalPages());
    }
}
