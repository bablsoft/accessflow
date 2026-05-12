package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.AiAnalysisResult;
import com.bablsoft.accessflow.ai.api.AiAnalyzerService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/queries")
@Tag(name = "Query Analysis", description = "AI-driven SQL risk analysis")
@RequiredArgsConstructor
class QueryAnalysisController {

    private final AiAnalyzerService aiAnalyzerService;

    @PostMapping("/analyze")
    @Operation(summary = "Run AI analysis on the given SQL without persisting or executing it")
    @ApiResponse(responseCode = "200", description = "Analysis result")
    @ApiResponse(responseCode = "404", description = "Datasource not found or not accessible")
    @ApiResponse(responseCode = "422", description = "AI provider returned an unparseable response")
    @ApiResponse(responseCode = "503", description = "AI provider unavailable")
    AiAnalysisResult analyze(@Valid @RequestBody AnalyzeQueryRequest request,
                             Authentication authentication) {
        var caller = (JwtClaims) authentication.getPrincipal();
        return aiAnalyzerService.analyzePreview(
                request.datasourceId(),
                request.sql(),
                caller.userId(),
                caller.organizationId(),
                caller.role() == UserRoleType.ADMIN);
    }
}
