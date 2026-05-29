package com.bablsoft.accessflow.notifications.internal.web;

import com.bablsoft.accessflow.notifications.internal.SlackInboundService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Inbound Slack callbacks. These endpoints are JWT-exempt (see {@code SecurityConfiguration}) and
 * authenticated instead by the {@code X-Slack-Signature} HMAC, verified against the signing secret
 * of the {@code slack_app_config} row matching the payload's {@code api_app_id}.
 */
@RestController
@RequestMapping("/api/v1/integrations/slack")
@Tag(name = "Slack Integration", description = "Inbound Slack interactive components and slash commands")
@RequiredArgsConstructor
class SlackInboundController {

    private static final String HEADER_TIMESTAMP = "X-Slack-Request-Timestamp";
    private static final String HEADER_SIGNATURE = "X-Slack-Signature";

    private final SlackInboundService inboundService;

    @PostMapping(value = "/actions", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    @Operation(summary = "Receive a Slack interactive-component (block_actions) callback",
            description = "Approve/Reject button clicks. HMAC-signed; not JWT-authenticated.")
    @ApiResponse(responseCode = "200", description = "Accepted — result delivered via response_url")
    @ApiResponse(responseCode = "401", description = "Missing/stale/invalid signature or replay")
    ResponseEntity<Void> actions(@RequestBody(required = false) String body,
                                 @RequestHeader(value = HEADER_TIMESTAMP, required = false) String timestamp,
                                 @RequestHeader(value = HEADER_SIGNATURE, required = false) String signature) {
        var result = inboundService.handleActions(body, timestamp, signature);
        return ResponseEntity.status(result.status()).build();
    }

    @PostMapping(value = "/commands", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    @Operation(summary = "Receive a Slack slash command (/accessflow link <code>)",
            description = "Links a Slack user to an AccessFlow user. HMAC-signed; not JWT-authenticated.")
    @ApiResponse(responseCode = "200", description = "Handled — ephemeral reply in the body")
    @ApiResponse(responseCode = "401", description = "Missing/stale/invalid signature or replay")
    ResponseEntity<Map<String, Object>> commands(
            @RequestBody(required = false) String body,
            @RequestHeader(value = HEADER_TIMESTAMP, required = false) String timestamp,
            @RequestHeader(value = HEADER_SIGNATURE, required = false) String signature) {
        var result = inboundService.handleCommand(body, timestamp, signature);
        if (result.status() != 200) {
            return ResponseEntity.status(result.status()).build();
        }
        var reply = new LinkedHashMap<String, Object>();
        reply.put("response_type", "ephemeral");
        reply.put("text", result.ephemeralText());
        return ResponseEntity.ok(reply);
    }
}
