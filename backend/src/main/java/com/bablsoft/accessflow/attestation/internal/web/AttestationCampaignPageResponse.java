package com.bablsoft.accessflow.attestation.internal.web;

import com.bablsoft.accessflow.attestation.api.AttestationCampaignView;
import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.List;

public record AttestationCampaignPageResponse(
        List<AttestationCampaignResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static AttestationCampaignPageResponse from(PageResponse<AttestationCampaignView> page) {
        return new AttestationCampaignPageResponse(
                page.content().stream().map(AttestationCampaignResponse::from).toList(),
                page.page(), page.size(), page.totalElements(), page.totalPages());
    }
}
