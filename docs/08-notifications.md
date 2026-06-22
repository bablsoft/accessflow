# 08 — Notification System

## Overview

Notifications are dispatched **asynchronously** by the `NotificationDispatcher` service (`accessflow-notifications` module). The dispatcher subscribes to Spring `ApplicationEvent` objects published by the workflow engine and fanouts to all active channels configured for the triggering review plan.

Delivery is non-blocking — notification failures do not affect the query workflow state.

The dispatcher runs on virtual-thread executors and consumes events using Spring Modulith's `@ApplicationModuleListener`. The base URL of the AccessFlow UI is configured via `accessflow.notifications.public-base-url` (env: `ACCESSFLOW_PUBLIC_BASE_URL`); review-link buttons use this base.

---

## Event Types

| Event | Trigger | Default Recipients | Status |
|-------|---------|-------------------|--------|
| `QUERY_SUBMITTED` | Query enters `PENDING_REVIEW` | Reviewers eligible at the lowest stage of the review plan (rules with explicit `userId` plus all org users matching `ApproverRule.role`), excluding the submitter | implemented |
| `QUERY_APPROVED` | Query fully approved (all stages complete) — fired for both human approval and auto-approval | Query submitter | implemented |
| `QUERY_REJECTED` | Reviewer rejects query | Query submitter | implemented |
| `AI_HIGH_RISK` | AI analysis returns `risk_level = CRITICAL` | All ADMIN users in the org. Fanned out to **all** active org channels (Email/Slack/Webhook), since per-channel routing rules are not yet modeled. | implemented |
| `ANOMALY_DETECTED` | `BehaviorAnomalyDetectionJob` flags a behavioural anomaly (UBA, AF-383) | All ADMIN users in the org plus the flagged user. Fanned out to **all** active org channels (Email/Slack/Webhook/PagerDuty) mirroring the `AI_HIGH_RISK` fanout. | implemented |
| `BREAK_GLASS_EXECUTED` | A break-glass / emergency-access query executes, bypassing review (AF-385) | All active ADMIN users in the org. Fanned out to **all** active org channels (Email/Slack/Webhook/PagerDuty) mirroring the `AI_HIGH_RISK` fanout. | implemented |
| `QUERY_CHANGES_REQUESTED` | Reviewer requests changes | Query submitter | deferred — no event published yet |
| `QUERY_EXECUTED` | Execution completes successfully | Query submitter | deferred — proxy executor not implemented |
| `QUERY_FAILED` | Execution error | Query submitter + all ADMIN users | deferred — proxy executor not implemented |
| `REVIEW_TIMEOUT` | Query has been `PENDING_REVIEW` past `approval_timeout_hours` (auto-rejected by `QueryTimeoutJob`) | Query submitter and every active ADMIN user in the org (de-duplicated when the submitter is themselves an admin) | implemented |
| `ACCESS_REQUEST_SUBMITTED` | JIT access request enters `PENDING` | Eligible approvers at the lowest stage of the datasource's review plan (excluding the requester); **falls back to all active ADMIN users in the org** when the plan resolves no eligible approver (no plan, empty approver list, or datasource scope filtered everyone out) so the request is never silently orphaned | implemented |
| `ACCESS_REQUEST_APPROVED` | JIT access request fully approved and grant materialised | Requester | implemented |
| `ACCESS_REQUEST_REJECTED` | Reviewer/admin rejects a JIT access request | Requester | implemented |

`AI_HIGH_RISK` only fires for `RiskLevel.CRITICAL`; lower risk levels still surface via the standard `QUERY_SUBMITTED` notification.

The reviewer-on-datasource recipient subset for `AI_HIGH_RISK` is currently restricted to the org-level ADMIN role because datasource-level reviewer membership is not modeled in the data model yet.

---

## Notification Channels

### Email

Uses **Spring Boot Mail** (Jakarta Mail / JavaMail).

**Configuration (in `notification_channels.config` JSONB):**
```json
{
  "smtp_host": "smtp.company.com",
  "smtp_port": 587,
  "smtp_user": "accessflow@company.com",
  "smtp_password_encrypted": "<AES-256 encrypted>",
  "smtp_tls": true,
  "from_address": "accessflow@company.com",
  "from_name": "AccessFlow"
}
```

Email bodies are rendered using **Thymeleaf** HTML templates located in `resources/templates/email/`. One template per event type:

- `email/query-ready-for-review.html` — `QUERY_SUBMITTED` and `AI_HIGH_RISK`
- `email/query-approved.html` — `QUERY_APPROVED`
- `email/query-rejected.html` — `QUERY_REJECTED`
- `email/query-review-timeout.html` — `REVIEW_TIMEOUT` (auto-rejection prompted by `QueryTimeoutJob`; renders an explicit explanatory banner with the configured `approval_timeout_hours` and an amber accent so the submitter can visually distinguish it from a reviewer rejection)
- `email/anomaly-detected.html` — `ANOMALY_DETECTED`
- `email/break-glass-executed.html` — `BREAK_GLASS_EXECUTED` (AF-385; red emergency banner, the executing user, datasource, and SQL preview, with a CTA to the executed query / break-glass log)

Templates include:
- Query summary (datasource, query type, SQL preview — first 200 chars)
- AI risk badge (color-coded)
- Direct link to query detail page in the AccessFlow UI
- Approve / Reject action links for review request emails (link to UI, not direct API calls)

Template labels, subjects, and CTAs are resolved through `i18n/messages.properties` under the `notification.email.*` key family. The render locale is the organization's `localization_config.default_language` (BCP-47), threaded through `NotificationContext.locale` and resolved by `EmailNotificationStrategy.resolveLocale(...)` with a hard fallback to English. Per-recipient locale (`UserPreferenceService.findPreferredLanguage`) is intentionally not consulted yet — tracked as a follow-up.

#### System SMTP fallback

Each organization can also configure a single, separate **system SMTP** under `system_smtp_config` (see [docs/03-data-model.md](03-data-model.md#system_smtp_config)). This is the SMTP used for user-invitation emails, and the dispatcher uses it as a **fallback EMAIL channel** when an event would otherwise have no email delivery path.

Precedence at dispatch time (per event):

1. Per-channel email rows (`notification_channels.channel_type = 'EMAIL'`) tied to the triggering review plan (or org-wide for `AI_HIGH_RISK`) are tried first, exactly as before. Each is independent — failures are logged and do not affect siblings.
2. If the resolved channel set contains **zero** EMAIL rows, the event has an email template, and `ctx.recipients()` is non-empty, the dispatcher converts the org's `system_smtp_config` into an `EmailChannelConfig` and routes through the same `EmailNotificationStrategy.deliverInternal(...)` code path.
3. If no system SMTP row exists either, no email is sent for that event (Slack/Webhook channels still fire normally).

Operators see a **System SMTP** card at the top of `/admin/notifications`; admins can edit it, send a test message, or remove it. Removing it leaves invitations disabled (the invitation endpoint returns `SYSTEM_SMTP_NOT_CONFIGURED_FOR_INVITE`) and the fallback path inert.

---

### Slack

Uses the official **[Slack Java SDK](https://docs.slack.dev/tools/java-slack-sdk/)** (`com.slack.api:slack-api-client`) for incoming-webhook delivery. Block Kit payloads are constructed with the SDK's typed builders so the wire shape is validated by the SDK rather than hand-rolled JSON.

**Configuration:**
```json
{
  "webhook_url": "https://hooks.slack.com/services/T.../B.../...",
  "channel": "#db-reviews",
  "mention_users": ["@alice", "@bob"]
}
```

Messages use **Block Kit** formatting:
- Header block: event type label
- Section block: datasource name, submitter, query type
- Risk badge as emoji + text (`🔴 CRITICAL`, `🟡 MEDIUM`, etc.)
- SQL preview in a `code` block (first 300 chars)
- Action buttons: **View in AccessFlow** (link to UI query detail page)
- For `QUERY_SUBMITTED` events without a Slack app configured (incoming-webhook only): a single **View in AccessFlow** deep link (reviewers approve in the UI).
- For `REVIEW_TIMEOUT` events: the header uses `⌛ Query Auto-Rejected (review timeout)` and the summary section adds an `*Auto-rejected after:*` field showing the configured `approval_timeout_hours` so the submitter can tell the message apart from a reviewer rejection. Slack header text and field labels remain English in this release; localising the Slack channel is tracked as a follow-up.

#### Slack app (interactive Approve / Reject) — AF-362

Beyond the one-way incoming-webhook channel above, an organization can configure a **Slack app** (`slack_app_config`: bot token + signing secret + app id + default channel, all encrypted at rest). When an active Slack app exists, `SlackNotificationStrategy` delivers via the **bot token** (`chat.postMessage`) instead of the webhook, and `QUERY_SUBMITTED` review-request messages carry **Approve** (`action_id=approve`, green) and **Reject** (`action_id=reject`, red) buttons whose `value` is the query request id. Without an app, the original text-only webhook path is used unchanged.

**Linking a reviewer.** A reviewer maps their Slack identity to their AccessFlow account once: `POST /api/v1/integrations/slack/link-codes` issues a short-lived one-time code (Redis, TTL `accessflow.notifications.slack.link-code-ttl`, default `PT10M`); the reviewer runs `/accessflow link <code>` in Slack; the verified slash command persists the `(user_id, slack_user_id)` row in `user_slack_mapping`.

**Handling a click.** Slack POSTs a `block_actions` payload to `POST /api/v1/integrations/slack/actions`. AccessFlow:
1. Parses `api_app_id` → loads that org's `slack_app_config` (and its signing secret).
2. Verifies the `X-Slack-Signature` HMAC over the raw body and rejects stale/replayed requests (see [docs/07-security.md → Slack request verification](07-security.md#slack-request-verification-af-362)).
3. Resolves the Slack user → AccessFlow user via `user_slack_mapping`; an unlinked user gets an ephemeral "not linked" reply.
4. Routes `approve` / `reject` through **the same `ReviewService.approve()` / `reject()`** path as the REST API — so the self-approval block (submitter ≠ reviewer) and RBAC/stage checks apply identically. A blocked decision returns an ephemeral error; a successful one mutates the original message in place via the Slack `response_url` to show the decision and reviewer.

The `/accessflow link <code>` slash command is delivered to `POST /api/v1/integrations/slack/commands` (same signature verification). Outbound interactive messages keep English Block Kit text (consistent with the existing webhook path); inbound ephemeral replies are localized to the org's default language.

**Example Slack Block Kit payload:**
```json
{
  "blocks": [
    {
      "type": "header",
      "text": { "type": "plain_text", "text": "🔍 New Query Awaiting Review" }
    },
    {
      "type": "section",
      "fields": [
        { "type": "mrkdwn", "text": "*Datasource:*\nProduction PostgreSQL" },
        { "type": "mrkdwn", "text": "*Submitted by:*\nalice@company.com" },
        { "type": "mrkdwn", "text": "*Query Type:*\nUPDATE" },
        { "type": "mrkdwn", "text": "*Risk Level:*\n🟡 MEDIUM (score: 42)" }
      ]
    },
    {
      "type": "section",
      "text": {
        "type": "mrkdwn",
        "text": "*SQL Preview:*\n```UPDATE orders SET status = 'shipped' WHERE id = 123```"
      }
    },
    {
      "type": "actions",
      "elements": [
        {
          "type": "button",
          "text": { "type": "plain_text", "text": "View & Review" },
          "url": "https://accessflow.company.com/queries/uuid",
          "style": "primary"
        }
      ]
    }
  ]
}
```

---

### Webhooks

Generic **HTTP POST** to any URL. Designed for integration with custom systems, PagerDuty, Jira, etc.

**Configuration:**
```json
{
  "url": "https://hooks.company.com/accessflow",
  "secret": "hmac_secret_token",
  "timeout_seconds": 10,
  "retry_attempts": 3
}
```

**Payload structure:**
```json
{
  "event": "QUERY_SUBMITTED",
  "timestamp": "2025-01-15T10:30:00Z",
  "organization_id": "uuid",
  "query_request": {
    "id": "uuid",
    "sql_preview": "UPDATE orders SET status = 'shipped' WHERE...",
    "query_type": "UPDATE",
    "risk_level": "MEDIUM",
    "risk_score": 42,
    "submitter_email": "alice@company.com",
    "datasource_name": "Production PostgreSQL",
    "justification": "Ticket #8821",
    "review_url": "https://accessflow.company.com/queries/uuid"
  }
}
```

**Request headers:**
```
Content-Type: application/json
X-AccessFlow-Event: QUERY_SUBMITTED
X-AccessFlow-Signature: sha256=<HMAC-SHA256 hex of body using webhook secret>
X-AccessFlow-Delivery: <UUID of this delivery attempt>
```

**Signature verification (receiver side):**
```python
import hmac, hashlib
expected = hmac.new(secret.encode(), body, hashlib.sha256).hexdigest()
assert hmac.compare_digest(f"sha256={expected}", received_signature)
```

**Retry policy:** One initial attempt followed by up to three scheduled retries at +30 s, +2 min, +10 min — four total attempts. Retries are scheduled on a virtual-thread `TaskScheduler`; each attempt re-fetches the channel and generates a fresh `X-AccessFlow-Delivery` UUID. Per-attempt delays are configurable via `accessflow.notifications.retry.{first,second,third}` (default `PT30S`, `PT2M`, `PT10M`). After exhaustion the dispatcher logs an `ERROR` line and publishes a `NotificationDeliveryExhaustedEvent`; the audit module's `AuditEventListener` consumes it and writes a `NOTIFICATION_DELIVERY_EXHAUSTED` audit row (resource `notification_channel`, `actor_id = NULL`) carrying `source: "DISPATCHER"`, `channel_id`, `channel_type`, `event_type`, `attempt_count`, optional `last_http_status`, optional `last_error` (truncated to 500 chars). Other channels (Slack/Discord/Teams/Telegram/Email) have different retry semantics and are not yet audited on exhaustion.

---

### Discord

Generic **HTTP POST** to a Discord [Incoming Webhook](https://discord.com/developers/docs/resources/webhook#execute-webhook). AccessFlow emits a single rich embed mirroring the Slack Block Kit layout (header, summary fields, SQL preview, review URL).

**Configuration:**
```json
{
  "webhook_url": "https://discord.com/api/webhooks/123/abc",
  "username": "AccessFlow",
  "avatar_url": "https://accessflow.example.com/logo.png"
}
```

- `webhook_url` (required) — Discord channel webhook URL.
- `username` (optional) — overrides the bot's display name on a per-message basis.
- `avatar_url` (optional) — overrides the bot's avatar on a per-message basis.

The embed colour reflects the AI risk level (`LOW` green, `MEDIUM` yellow, `HIGH` orange, `CRITICAL` red). For `REVIEW_TIMEOUT` events the embed adds an `Auto-rejected after` field showing the `approval_timeout_hours` so submitters can tell the message apart from a reviewer rejection.

Non-2xx Discord responses raise `NotificationDeliveryException`; the dispatcher logs the failure and moves on to the next channel. Discord does not currently use the webhook retry scheduler that the generic `WEBHOOK` channel uses.

---

### Telegram

Posts to the Telegram Bot API's [`sendMessage`](https://core.telegram.org/bots/api#sendmessage) endpoint as MarkdownV2. Operators create a bot via [@BotFather](https://core.telegram.org/bots/tutorial), add it to the target chat/channel, and record the chat ID (groups & channels are negative integers).

**Configuration:**
```json
{
  "bot_token": "123456:ABC-DEF...",
  "chat_id": "-1001234567890"
}
```

- `bot_token` (required, AES-encrypted at rest, masked on read).
- `chat_id` (required) — numeric chat/channel ID or a `@channelname` mention.

The message uses MarkdownV2 formatting with the SQL preview rendered inside a fenced code block. The Telegram API base URL is configurable via `accessflow.notifications.telegram-api-base-url` (default `https://api.telegram.org/`) so air-gapped installs can route through an internal proxy.

---

### Microsoft Teams

Posts an [Adaptive Card](https://learn.microsoft.com/en-us/adaptive-cards/) (schema 1.5) wrapped in the `attachments` envelope expected by Teams Incoming Webhooks and the newer Power Automate "Post to a channel when a webhook request is received" workflow.

**Configuration:**
```json
{
  "webhook_url": "https://example.webhook.office.com/webhookb2/..."
}
```

- `webhook_url` (required) — Teams Incoming Webhook URL (legacy O365 connector or Power Automate flow URL).

The card includes a header (with risk-coloured accent), a `FactSet` summary, a monospace SQL preview, and an `Action.OpenUrl` button linking back to the AccessFlow query detail page. For `REVIEW_TIMEOUT` the fact set adds an `Auto-rejected after` row showing the configured `approval_timeout_hours`.

---

### PagerDuty

Pages an on-call responder via the [PagerDuty Events API v2](https://developer.pagerduty.com/docs/events-api-v2/overview/). AccessFlow `POST`s a `trigger` event to `https://events.pagerduty.com/v2/enqueue` scoped by the integration **routing key** (an "Events API v2" integration on a PagerDuty service).

**Configuration:**
```json
{
  "routing_key": "R0ABCD1234567890ABCDEF",
  "default_severity": "critical",
  "triggers": ["CRITICAL_RISK", "REVIEW_TIMEOUT", "ANOMALY", "BREAK_GLASS"]
}
```

- `routing_key` (required, AES-256-GCM encrypted at rest, masked on read) — the Events API v2 integration routing key.
- `default_severity` (required) — PagerDuty `payload.severity` for every event from this channel; one of `critical`, `error`, `warning`, `info`.
- `triggers` (required, at least one) — which events page this channel. A **trigger filter** runs before any HTTP call, so unlike the chat channels a PagerDuty channel only fires for the events it opts into:
  - `CRITICAL_RISK` → the `AI_HIGH_RISK` event (raised only when the AI analysis returns `CRITICAL` risk).
  - `REVIEW_TIMEOUT` → the `REVIEW_TIMEOUT` event (a query auto-rejected past its `approval_timeout_hours`).
  - `ANOMALY` → the `ANOMALY_DETECTED` event (a behavioural anomaly flagged by `BehaviorAnomalyDetectionJob`, UBA, AF-383).
  - `BREAK_GLASS` → the `BREAK_GLASS_EXECUTED` event (an emergency-access query executed, bypassing review, AF-385).
  Events with no matching trigger (and every other event type, e.g. `QUERY_SUBMITTED`) are dropped silently.

The event body carries a stable `dedup_key` of `accessflow-<organizationId>-<queryRequestId>` so re-triggers for the same query collapse into a single PagerDuty incident, a `summary`, `source` (the datasource name), and a `custom_details` block mirroring the webhook payload (query id, risk, submitter, justification, review URL). A deep link back to the AccessFlow review page is sent as `client_url`.

PagerDuty delivery uses the **same async retry scheduler as the generic `WEBHOOK` channel** — one initial attempt plus up to three retries at `accessflow.notifications.retry.{first,second,third}` (default +30s / +2m / +10m). On exhaustion the dispatcher logs `ERROR` and publishes a `NotificationDeliveryExhaustedEvent` to the audit log. The Events API base URL is configurable via `accessflow.notifications.pagerduty-api-base-url` (default `https://events.pagerduty.com/`) for air-gapped installs that route through an internal proxy.

> The PagerDuty `resolve` action is intentionally out of scope: AccessFlow's `TIMED_OUT` state is terminal, so no event currently un-resolves an incident. The `dedup_key` is query-stable so a future `resolve` can target the same incident without a config change.

---

### Web Push (PWA one-tap approve/reject) — AF-444

Unlike the channels above (org-admin-configured, one delivery target each), Web Push is a **per-user
subscription path**: a reviewer installs the PWA, opts in, and their browser registers a W3C Push API
subscription (`push_subscriptions`). It is **not** a `notification_channel_type` — modelling it as one
would not fit the per-device subscription shape and would needlessly extend the `NotificationEventType`
switch fan-out. Instead a dedicated `WebPushNotificationListener` (in `notifications.internal`) runs
**alongside** the channel-based `NotificationDispatcher`.

**Trigger.** On `QueryReadyForReviewEvent` (a query entering `PENDING_REVIEW`), the listener resolves
the same eligible reviewers the `QUERY_SUBMITTED` channel notification would target (reusing
`NotificationContextBuilder`), looks up each recipient's stored subscriptions, and pushes a one-tap
message. Best-effort: any failure is logged and never affects the workflow transition.

**Delivery.** `WebPushSender` implements the W3C Web Push protocol with **pure JDK crypto** — no
`web-push` library, no BouncyCastle, no Netty:
- RFC 8291 message encryption (Content-Encoding `aes128gcm`): ECDH on P-256, two HKDF-SHA256
  derivations, AES-128-GCM — produces the request body.
- RFC 8292 VAPID: an ES256-signed JWT yielding the `Authorization: vapid t=…, k=…` header.

The deployment VAPID keypair (`PushVapidKeyProvider`) is auto-generated and persisted on first use
(private key encrypted with `ENCRYPTION_KEY`, in `push_vapid_config`), or supplied via
`ACCESSFLOW_PUSH_VAPID_PUBLIC_KEY` / `ACCESSFLOW_PUSH_VAPID_PRIVATE_KEY` / `ACCESSFLOW_PUSH_VAPID_SUBJECT`.
A `404`/`410` from the push service prunes the now-invalid subscription.

**Payload.** A JSON document the service worker renders: `title`, `body` (datasource + submitter +
SQL preview), `data.url` (a deep link to `/reviews/{id}/decide`), and `approve` / `reject` action
buttons.

**One-tap is gated by step-up auth.** Tapping an action opens the PWA at `/reviews/{id}/decide`, where
the reviewer re-verifies (password, or TOTP when 2FA is enrolled) via `POST /auth/step-up`; the
returned single-use token is presented to `POST /reviews/{id}/decide`. A single tap never commits a
decision, and the **self-approval guard is enforced server-side regardless of channel** — the
push-decide path routes through the same `ReviewService.approve()` / `reject()` as the REST and Slack
flows.

---

## Admin: Testing Channels

`POST /admin/notification-channels/{id}/test` sends a test payload for the configured channel type:

- **Email:** Sends a test email to the configured `from_address` (or an address specified in the request body)
- **Slack:** Posts "✅ AccessFlow notification channel test successful" to the configured channel
- **Webhook:** Posts a `{"event": "TEST", "timestamp": "..."}` payload to the webhook URL
- **Discord / MS Teams:** Posts a one-line confirmation embed/card to the configured webhook URL
- **Telegram:** Posts a one-line MarkdownV2 confirmation to the configured chat ID
- **PagerDuty:** Posts a `trigger` event with a fixed `accessflow-test` dedup key and `info` severity (the trigger filter is bypassed for tests)

---

## Channel Configuration via API

```http
POST /api/v1/admin/notification-channels
{
  "name": "Engineering Slack",
  "channel_type": "SLACK",
  "config": {
    "webhook_url": "https://hooks.slack.com/services/...",
    "channel": "#db-reviews"
  }
}
```

Sensitive `config` fields — `smtp_password`, `secret`, `bot_token`, and `routing_key` — are AES-256-GCM encrypted before being stored in the database. They are never returned in GET responses — only a masked placeholder is shown.

---

## In-app Inbox

In addition to fanning the same domain events out to admin-configured email/Slack/webhook
channels, the dispatcher persists one row per recipient in the [`user_notifications`](03-data-model.md#user_notifications)
table so the bell-icon UI in the topbar can render history, unread counts, and act on
individual entries. This persistence pipeline is independent of channel configuration —
users still receive in-app notifications when no external channel is set up.

**Recipients** are resolved per event type and mirror the channel-routing logic already
in `NotificationContextBuilder`:

| Event | Recipients |
|-------|-----------|
| `QUERY_SUBMITTED` | Eligible reviewers at the lowest stage of the datasource's review plan, excluding the submitter |
| `QUERY_APPROVED` | The original submitter |
| `QUERY_REJECTED` | The original submitter |
| `REVIEW_TIMEOUT` | The original submitter and every active org admin (de-duplicated) |
| `AI_HIGH_RISK` | All active org admins |
| `ANOMALY_DETECTED` | All active org admins plus the flagged user |
| `ACCESS_REQUEST_SUBMITTED` | Eligible plan approvers at the lowest stage (excluding the requester), falling back to all active org admins when the plan resolves no one |
| `ACCESS_REQUEST_APPROVED` / `ACCESS_REQUEST_REJECTED` | The requester |
| `TEST` | Skipped — never persisted to the inbox |

**Persistence flow.** `NotificationDispatcher` first calls `userNotificationService.recordForUsers(...)`
with the recipients pulled from `NotificationContext.recipients()`, then continues with
the existing channel fan-out. Each persisted row publishes a `UserNotificationCreatedEvent`,
which `realtime/internal/RealtimeEventDispatcher` translates into a `notification.created`
WebSocket envelope sent to the recipient's open sessions. Persistence failures are
logged and swallowed so they cannot affect the workflow state machine or external channel
delivery.

**REST contract** (full details in [`docs/04-api-spec.md`](04-api-spec.md#notification-endpoints)):

- `GET /api/v1/notifications` — paginated inbox for the caller
- `GET /api/v1/notifications/unread-count` — bell badge count
- `POST /api/v1/notifications/{id}/read` — mark single notification as read
- `POST /api/v1/notifications/read-all` — mark all unread as read
- `DELETE /api/v1/notifications/{id}` — delete a notification

The frontend (`NotificationBell` in `frontend/src/components/common/`) consumes these
endpoints via TanStack Query and invalidates the `['notifications', 'list']` and
`['notifications', 'unread-count']` keys on every `notification.created` WS event.
