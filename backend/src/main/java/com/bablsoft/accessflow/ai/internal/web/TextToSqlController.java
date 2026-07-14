package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.ai.api.TextToSqlService;
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
@Tag(name = "Text to SQL", description = "AI-driven natural-language → SQL generation")
@RequiredArgsConstructor
class TextToSqlController {

    private final TextToSqlService textToSqlService;

    @PostMapping("/generate-sql")
    @Operation(summary = "Translate a natural-language prompt into a draft SQL statement",
            description = "Returns a SQL draft for the editor; nothing is persisted or executed. The "
                    + "user reviews/edits the SQL and submits it through POST /api/v1/queries, where "
                    + "the full governance pipeline still applies.")
    @ApiResponse(responseCode = "200", description = "Generated SQL draft")
    @ApiResponse(responseCode = "400", description = "Validation error, or no AI configuration is bound")
    @ApiResponse(responseCode = "409", description = "Text-to-SQL is disabled for this datasource")
    @ApiResponse(responseCode = "422", description = "AI provider returned no usable SQL")
    @ApiResponse(responseCode = "503", description = "AI provider unavailable")
    GeneratedSqlResponse generateSql(@Valid @RequestBody GenerateSqlRequest request,
                                     Authentication authentication) {
        var caller = (JwtClaims) authentication.getPrincipal();
        var result = textToSqlService.generateSql(
                request.datasourceId(),
                request.prompt(),
                caller.userId(),
                caller.organizationId(),
                caller.has(Permission.QUERY_ADMIN));
        return GeneratedSqlResponse.from(result);
    }
}
