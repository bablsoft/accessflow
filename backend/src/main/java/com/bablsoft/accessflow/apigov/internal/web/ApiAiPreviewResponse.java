package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiAssistService;
import com.bablsoft.accessflow.core.api.RiskLevel;

import java.util.List;

public record ApiAiPreviewResponse(int riskScore, RiskLevel riskLevel, String summary,
                                   List<String> issues) {

    static ApiAiPreviewResponse from(ApiAssistService.ApiAiPreview p) {
        return new ApiAiPreviewResponse(p.riskScore(), p.riskLevel(), p.summary(), p.issues());
    }
}
