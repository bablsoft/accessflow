package com.partqam.accessflow.ai.internal.web;

import com.partqam.accessflow.ai.api.AiAnalyzerStrategy;
import com.partqam.accessflow.ai.api.AiConfigService;
import com.partqam.accessflow.core.api.DbType;
import com.partqam.accessflow.security.api.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/ai-config")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin AI Config", description = "Per-organization AI analyzer settings (ADMIN only)")
@RequiredArgsConstructor
@Slf4j
class AdminAiConfigController {

    private static final String TEST_SQL = "SELECT 1";

    private final AiConfigService aiConfigService;
    private final AiAnalyzerStrategy aiAnalyzerStrategy;

    @GetMapping
    @Operation(summary = "Read the current AI analyzer configuration for the caller's organization")
    @ApiResponse(responseCode = "200", description = "Current configuration (API key masked)")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    AiConfigResponse get(Authentication authentication) {
        var caller = currentClaims(authentication);
        return AiConfigResponse.from(aiConfigService.getOrDefault(caller.organizationId()));
    }

    @PutMapping
    @Operation(summary = "Update the AI analyzer configuration for the caller's organization")
    @ApiResponse(responseCode = "200", description = "Updated configuration (API key masked)")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    AiConfigResponse update(@Valid @RequestBody UpdateAiConfigRequest body, Authentication authentication) {
        var caller = currentClaims(authentication);
        return AiConfigResponse.from(aiConfigService.update(caller.organizationId(), body.toCommand()));
    }

    @PostMapping("/test")
    @Operation(summary = "Send a synthetic prompt to the active AI provider and report back")
    @ApiResponse(responseCode = "200", description = "Provider responded — see status")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    TestAiConfigResponse test() {
        try {
            var result = aiAnalyzerStrategy.analyze(TEST_SQL, DbType.POSTGRESQL, null);
            return TestAiConfigResponse.ok("AI provider responded with risk_level=" + result.riskLevel());
        } catch (RuntimeException ex) {
            log.warn("AI test prompt failed", ex);
            return TestAiConfigResponse.error(ex.getMessage());
        }
    }

    private JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
