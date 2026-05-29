package com.bablsoft.accessflow.notifications.internal.web;

/** Whether the caller's AccessFlow account is linked to a Slack user, and which one. */
public record SlackLinkStatusResponse(boolean linked, String slackUserId) {
}
