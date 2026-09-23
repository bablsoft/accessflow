package com.bablsoft.accessflow.scheduling.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.scheduling.api.JobExecutionView;

import java.util.List;

public record JobExecutionPageResponse(
        List<JobExecutionResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    static JobExecutionPageResponse from(PageResponse<JobExecutionView> page) {
        return new JobExecutionPageResponse(
                page.content().stream().map(JobExecutionResponse::from).toList(),
                page.page(), page.size(), page.totalElements(), page.totalPages());
    }
}
