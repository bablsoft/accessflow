package com.bablsoft.accessflow.notifications.internal.web;

import java.time.Instant;

/** One-time code the caller pastes into Slack via {@code /accessflow link <code>}. */
public record SlackLinkCodeResponse(String code, Instant expiresAt) {
}
