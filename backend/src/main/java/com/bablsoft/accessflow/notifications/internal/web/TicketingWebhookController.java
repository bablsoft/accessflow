package com.bablsoft.accessflow.notifications.internal.web;

import com.bablsoft.accessflow.notifications.api.NotificationChannelType;
import com.bablsoft.accessflow.notifications.internal.TicketingInboundService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Inbound ticket-status callbacks from ServiceNow / Jira (AF-453). These endpoints are JWT-exempt
 * (see {@code SecurityConfiguration}) and authenticated instead by the
 * {@code X-AccessFlow-Signature} HMAC keyed by the target channel's {@code webhook_secret}.
 */
@RestController
@RequestMapping("/api/v1/integrations")
@Tag(name = "Ticketing Integration",
        description = "Inbound ServiceNow / Jira ticket-status webhooks (bi-directional sync)")
@RequiredArgsConstructor
class TicketingWebhookController {

    private static final String HEADER_TIMESTAMP = "X-AccessFlow-Timestamp";
    private static final String HEADER_SIGNATURE = "X-AccessFlow-Signature";

    private final TicketingInboundService inboundService;

    @PostMapping(value = "/servicenow/webhook/{channelId}",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Receive a ServiceNow incident status update",
            description = "Generic status payload sent by a ServiceNow Business Rule. HMAC-signed; "
                    + "not JWT-authenticated.")
    @ApiResponse(responseCode = "200", description = "Processed — result label in the body")
    @ApiResponse(responseCode = "400", description = "Unparseable payload or missing fields")
    @ApiResponse(responseCode = "401", description = "Missing/stale/invalid signature or replay")
    @ApiResponse(responseCode = "404", description = "Unknown, inactive, or non-ServiceNow channel")
    ResponseEntity<Map<String, String>> servicenow(
            @PathVariable UUID channelId,
            @RequestBody(required = false) String body,
            @RequestHeader(value = HEADER_TIMESTAMP, required = false) String timestamp,
            @RequestHeader(value = HEADER_SIGNATURE, required = false) String signature) {
        return respond(inboundService.handle(NotificationChannelType.SERVICENOW, channelId, body,
                timestamp, signature));
    }

    @PostMapping(value = "/jira/webhook/{channelId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Receive a Jira issue status update",
            description = "Generic status payload sent by a Jira Automation rule. HMAC-signed; "
                    + "not JWT-authenticated.")
    @ApiResponse(responseCode = "200", description = "Processed — result label in the body")
    @ApiResponse(responseCode = "400", description = "Unparseable payload or missing fields")
    @ApiResponse(responseCode = "401", description = "Missing/stale/invalid signature or replay")
    @ApiResponse(responseCode = "404", description = "Unknown, inactive, or non-Jira channel")
    ResponseEntity<Map<String, String>> jira(
            @PathVariable UUID channelId,
            @RequestBody(required = false) String body,
            @RequestHeader(value = HEADER_TIMESTAMP, required = false) String timestamp,
            @RequestHeader(value = HEADER_SIGNATURE, required = false) String signature) {
        return respond(inboundService.handle(NotificationChannelType.JIRA, channelId, body,
                timestamp, signature));
    }

    private static ResponseEntity<Map<String, String>> respond(
            TicketingInboundService.InboundResult result) {
        return ResponseEntity.status(result.status()).body(Map.of("result", result.result()));
    }
}
