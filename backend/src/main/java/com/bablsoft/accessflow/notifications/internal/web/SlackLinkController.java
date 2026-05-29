package com.bablsoft.accessflow.notifications.internal.web;

import com.bablsoft.accessflow.notifications.internal.SlackLinkService;
import com.bablsoft.accessflow.security.api.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service Slack account linking for the authenticated user: issue a one-time link code, check
 * link status, or unlink. The code is redeemed in Slack via {@code /accessflow link <code>}.
 */
@RestController
@RequestMapping("/api/v1/integrations/slack")
@Tag(name = "Slack Integration", description = "Self-service Slack account linking")
@RequiredArgsConstructor
class SlackLinkController {

    private final SlackLinkService linkService;

    @PostMapping("/link-codes")
    @Operation(summary = "Generate a one-time code to link your Slack account")
    @ApiResponse(responseCode = "201", description = "Code issued")
    ResponseEntity<SlackLinkCodeResponse> createLinkCode(Authentication authentication) {
        var issued = linkService.issueCode(currentClaims(authentication).userId());
        return ResponseEntity.status(201)
                .body(new SlackLinkCodeResponse(issued.code(), issued.expiresAt()));
    }

    @GetMapping("/link")
    @Operation(summary = "Whether your account is linked to a Slack user")
    @ApiResponse(responseCode = "200", description = "Link status")
    SlackLinkStatusResponse linkStatus(Authentication authentication) {
        var slackUserId = linkService.linkStatus(currentClaims(authentication).userId()).orElse(null);
        return new SlackLinkStatusResponse(slackUserId != null, slackUserId);
    }

    @DeleteMapping("/link")
    @Operation(summary = "Unlink your Slack account")
    @ApiResponse(responseCode = "204", description = "Unlinked")
    ResponseEntity<Void> unlink(Authentication authentication) {
        linkService.unlink(currentClaims(authentication).userId());
        return ResponseEntity.noContent().build();
    }

    private JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
