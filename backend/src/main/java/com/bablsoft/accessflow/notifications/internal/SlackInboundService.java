package com.bablsoft.accessflow.notifications.internal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses, verifies, and dispatches inbound Slack callbacks (interactive components and slash
 * commands). The raw request body is preserved exactly so the {@code X-Slack-Signature} HMAC can be
 * recomputed over it; the signing secret comes from the {@code slack_app_config} row matching the
 * payload's {@code api_app_id}. A verified signature is also deduped via {@link SlackReplayGuard}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlackInboundService {

    private final ObjectMapper objectMapper;
    private final DefaultSlackAppConfigService appConfigService;
    private final SlackRequestVerifier verifier;
    private final SlackReplayGuard replayGuard;
    private final SlackInteractionService interactionService;
    private final SlackLinkService linkService;

    /** Result of handling an inbound callback: the HTTP status to return and optional reply text. */
    public record Result(int status, String ephemeralText) {

        static Result unauthorized() {
            return new Result(401, null);
        }

        static Result ok() {
            return new Result(200, null);
        }

        static Result okEphemeral(String text) {
            return new Result(200, text);
        }
    }

    public Result handleActions(String rawBody, String timestamp, String signature) {
        var payloadJson = formValue(rawBody, "payload");
        if (payloadJson == null) {
            return Result.unauthorized();
        }
        JsonNode payload;
        try {
            payload = objectMapper.readTree(payloadJson);
        } catch (RuntimeException ex) {
            return Result.unauthorized();
        }
        var app = appConfigService.findActiveByAppId(text(payload, "api_app_id")).orElse(null);
        if (app == null || !verified(rawBody, timestamp, signature, app.signingSecret())) {
            return Result.unauthorized();
        }
        var firstAction = firstAction(payload);
        if (firstAction == null) {
            return Result.ok();
        }
        interactionService.handleAction(
                app,
                text(payload.get("user"), "id"),
                text(firstAction, "action_id"),
                text(firstAction, "value"),
                text(payload, "response_url"));
        return Result.ok();
    }

    public Result handleCommand(String rawBody, String timestamp, String signature) {
        var fields = parseForm(rawBody);
        var app = appConfigService.findActiveByAppId(fields.get("api_app_id")).orElse(null);
        if (app == null || !verified(rawBody, timestamp, signature, app.signingSecret())) {
            return Result.unauthorized();
        }
        var reply = linkService.completeLink(
                app.organizationId(), fields.get("user_id"), fields.get("text"));
        return Result.okEphemeral(reply);
    }

    private boolean verified(String rawBody, String timestamp, String signature, String signingSecret) {
        if (!verifier.isValid(rawBody, timestamp, signature, signingSecret, Instant.now())) {
            return false;
        }
        return replayGuard.firstSeen(signature);
    }

    private static JsonNode firstAction(JsonNode payload) {
        var actions = payload.get("actions");
        if (actions == null || !actions.isArray() || actions.isEmpty()) {
            return null;
        }
        return actions.get(0);
    }

    private static String text(JsonNode node, String field) {
        var v = node == null ? null : node.get(field);
        return (v == null || v.isNull() || !v.isString()) ? null : v.stringValue();
    }

    private static Map<String, String> parseForm(String rawBody) {
        var map = new LinkedHashMap<String, String>();
        if (rawBody == null || rawBody.isBlank()) {
            return map;
        }
        for (var pair : rawBody.split("&")) {
            var idx = pair.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            var key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
            var value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
            map.putIfAbsent(key, value);
        }
        return map;
    }

    private static String formValue(String rawBody, String key) {
        return parseForm(rawBody).get(key);
    }
}
