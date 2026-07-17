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
| `QUERY_ESCALATED` | A routing policy escalated the query (`ESCALATE` / `REQUIRE_APPROVALS` raised the approval bar, AF-446) as it entered `PENDING_REVIEW` — fired **in addition to** `QUERY_SUBMITTED` (AF-453) | Reviewers eligible at the lowest stage of the review plan (same set as `QUERY_SUBMITTED`) | implemented |
| `AI_HIGH_RISK` | AI analysis returns `risk_level = CRITICAL` | All ADMIN users in the org. Fanned out to **all** active org channels (Email/Slack/Webhook), since per-channel routing rules are not yet modeled. | implemented |
| `ANOMALY_DETECTED` | `BehaviorAnomalyDetectionJob` flags a behavioural anomaly (UBA, AF-383) | All ADMIN users in the org plus the flagged user. Fanned out to **all** active org channels (Email/Slack/Webhook/PagerDuty) mirroring the `AI_HIGH_RISK` fanout. | implemented |
| `BREAK_GLASS_EXECUTED` | A break-glass / emergency-access query executes, bypassing review (AF-385) | All active ADMIN users in the org. Fanned out to **all** active org channels (Email/Slack/Webhook/PagerDuty) mirroring the `AI_HIGH_RISK` fanout. | implemented |
| `WEEKLY_DIGEST` | `WeeklyDigestJob` fires for a user opted into the weekly dashboard digest (AF-498) | The single opted-in user (digest owner). Delivered via the user's email + active org chat channels (Email/Slack/Discord/Teams/Telegram); PagerDuty treats it as not-applicable (never pages). | implemented |
| `ATTESTATION_CAMPAIGN_OPENED` | An access-recertification campaign opens (AF-384) | The campaign's eligible reviewers (datasource reviewers) plus active org admins. Fanned out to **all** active org channels (Email/Slack/Discord/Teams/Telegram) mirroring the `ANOMALY_DETECTED` fanout; PagerDuty treats it as not-applicable (never pages). | implemented |
| `ERASURE_APPROVED` | A right-to-erasure request is approved (AF-499) | The request's **submitter** (so they learn it was approved). Delivered to the submitter's active chat channels (Slack/Discord/Teams/Telegram) + in-app; no email template (uses the fallback subject); PagerDuty not-applicable. | implemented |
| `API_CONNECTOR_OAUTH2_TOKEN_FAILED` | An API connector's outbound OAuth2 token fetch has failed repeatedly (consecutive-failure counter crossed `accessflow.apigov.oauth2-token-failure-alert-threshold`); the connector is effectively down (AF-500 / #506) | All active org admins. Fanned out to **all** active org channels (Email/Slack/Discord/Teams/Telegram/PagerDuty) mirroring the `ANOMALY_DETECTED` fanout. Never includes the token/secret. | implemented |
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
- `email/weekly-digest.html` — `WEEKLY_DIGEST` (AF-498; the week range, the four headline metrics — queries submitted, pending approvals, open anomalies, open suggestions — and a CTA to the dashboard)
- `email/attestation-campaign-opened.html` — `ATTESTATION_CAMPAIGN_OPENED` (AF-384; the campaign name, due date, and a CTA to the recertification queue)
- `email/api-connector-token-failed.html` — `API_CONNECTOR_OAUTH2_TOKEN_FAILED` (AF-500 / #506; red alert banner, the connector name, and a CTA to the connector settings — never the token/secret)

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
  - `ESCALATION` → the `QUERY_ESCALATED` event (a routing policy escalated the query, AF-453).
  Events with no matching trigger (and every other event type, e.g. `QUERY_SUBMITTED`) are dropped silently.

The event body carries a stable `dedup_key` of `accessflow-<organizationId>-<queryRequestId>` so re-triggers for the same query collapse into a single PagerDuty incident, a `summary`, `source` (the datasource name), and a `custom_details` block mirroring the webhook payload (query id, risk, submitter, justification, review URL). A deep link back to the AccessFlow review page is sent as `client_url`.

PagerDuty delivery uses the **same async retry scheduler as the generic `WEBHOOK` channel** — one initial attempt plus up to three retries at `accessflow.notifications.retry.{first,second,third}` (default +30s / +2m / +10m). On exhaustion the dispatcher logs `ERROR` and publishes a `NotificationDeliveryExhaustedEvent` to the audit log. The Events API base URL is configurable via `accessflow.notifications.pagerduty-api-base-url` (default `https://events.pagerduty.com/`) for air-gapped installs that route through an internal proxy.

> The PagerDuty `resolve` action is intentionally out of scope: AccessFlow's `TIMED_OUT` state is terminal, so no event currently un-resolves an incident. The `dedup_key` is query-stable so a future `resolve` can target the same incident without a config change.

---

### ServiceNow (ticketing, AF-453)

Auto-creates a ServiceNow **incident** through the [Table API](https://docs.servicenow.com/bundle/latest/page/integrate/inbound-rest/concept/c_TableAPI.html) (`POST {instance_url}/api/now/table/incident`, Basic auth) when a selected workflow event fires. Like PagerDuty, a ServiceNow channel only reacts to the **triggers** it opts into; every other event is dropped before any HTTP call. Each created incident is persisted as a `query_tickets` link (sys_id + incident number + deep link) and surfaces on the query detail page.

**Configuration:**
```json
{
  "instance_url": "https://company.service-now.com",
  "username": "accessflow.integration",
  "password": "…",
  "assignment_group": "Database Operations",
  "urgency": 2,
  "triggers": ["QUERY_REJECTED", "REVIEW_TIMEOUT", "QUERY_ESCALATED"],
  "bidirectional_sync": true,
  "webhook_secret": "…",
  "approve_statuses": ["resolved", "closed"],
  "reject_statuses": ["rejected", "cancelled"]
}
```

- `instance_url` (required) — the ServiceNow instance base URL.
- `username` / `password` (required; password AES-256-GCM encrypted at rest, masked on read) — a ServiceNow account allowed to create incidents via the Table API.
- `assignment_group` (optional) — group name or sys_id stamped on created incidents.
- `urgency` (optional, 1–3) — ServiceNow urgency for created incidents; omitted = instance default.
- `triggers` (required, at least one) — which events open a ticket: `QUERY_REJECTED` (manual or routing-policy rejection), `REVIEW_TIMEOUT` (auto-reject past `approval_timeout_hours`), `QUERY_ESCALATED` (routing-policy escalation).
- `bidirectional_sync` / `webhook_secret` / `approve_statuses` / `reject_statuses` — see [Ticketing inbound webhooks](#ticketing-inbound-webhooks--bi-directional-sync-af-453) below. `webhook_secret` is required when `bidirectional_sync` is enabled (AES-256-GCM encrypted at rest, masked on read).

The incident body carries a `short_description` ("[AccessFlow] Query rejected on <datasource>"), a plain-text `description` (event, submitter, AI risk, justification, reviewer comment, SQL preview, review deep link), and `correlation_id=accessflow-<queryRequestId>`. **Create-once dedupe:** at most one ticket per `(channel, query, event)` — retries and event redeliveries never open duplicates. Delivery reuses the standard notification retry scheduler (+30s / +2m / +10m; `NotificationDeliveryExhaustedEvent` on exhaustion). A successful create writes a `TICKET_CREATED` audit row.

---

### Jira (ticketing, AF-453)

Auto-creates a Jira **issue** through the REST v2 API (`POST {base_url}/rest/api/2/issue`, Basic auth `user_email:api_token`). v2 is used deliberately — its plain-text `description` avoids the Atlassian Document Format that v3 requires; it is supported by both Jira Cloud and Data Center. Trigger filtering, create-once dedupe, retry, `query_tickets` persistence, and audit are identical to the ServiceNow channel.

**Configuration:**
```json
{
  "base_url": "https://company.atlassian.net",
  "user_email": "accessflow-bot@company.com",
  "api_token": "…",
  "project_key": "SEC",
  "issue_type": "Task",
  "triggers": ["QUERY_REJECTED", "QUERY_ESCALATED"],
  "bidirectional_sync": false
}
```

- `base_url` (required) — the Jira site URL.
- `user_email` / `api_token` (required; token AES-256-GCM encrypted at rest, masked on read) — an Atlassian account email + API token.
- `project_key` (required) — target project for created issues.
- `issue_type` (optional, default `Task`) — issue type name.
- `triggers` / `bidirectional_sync` / `webhook_secret` / `approve_statuses` / `reject_statuses` — same semantics as ServiceNow.

Created issues get the `accessflow` label, a summary/description identical in shape to the ServiceNow incident, and a `{base_url}/browse/{key}` deep link on the ticket record.

---

### Ticketing inbound webhooks & bi-directional sync (AF-453)

Both ticketing channels can accept **signed status callbacks** so the linked ticket's state stays current in AccessFlow — and, when the channel opts in, so a ticket resolution can decide a query still in review.

**Endpoints** (JWT-exempt; authenticated by HMAC):

```
POST /api/v1/integrations/servicenow/webhook/{channelId}
POST /api/v1/integrations/jira/webhook/{channelId}
```

**Payload** — a deliberately generic JSON contract that a ServiceNow Business Rule or Jira Automation rule assembles (AccessFlow does not parse native webhook shapes):

```json
{
  "external_id": "abc123",        // ServiceNow sys_id / Jira issue id — required
  "status": "Resolved",           // new status label — required
  "resolution": "Done",            // optional resolution
  "actor": "jdoe"                  // optional display name of who changed the ticket
}
```

**Signature.** Two headers are required: `X-AccessFlow-Timestamp` (Unix seconds) and `X-AccessFlow-Signature: sha256=<hex>`, where the hex value is `HMAC-SHA256(webhook_secret, "v1:{timestamp}:{rawBody}")`. Verification is constant-time; timestamps outside `accessflow.notifications.ticketing.signature-tolerance` (default `PT5M`) are rejected, and a Redis replay guard rejects a second sighting of the same signature within that window. Responses: `401` bad/stale/replayed signature, `404` unknown/inactive/type-mismatched channel, `400` unparseable payload, `200` with `{"result": "ignored" | "synced" | "decision_applied"}`.

**Sync semantics.** A verified update always refreshes the linked `query_tickets` row (status + resolution) and writes a `TICKET_STATUS_SYNCED` audit row. When the channel has `bidirectional_sync=true` and the inbound `resolution`/`status` matches (case-insensitively) the channel's `reject_statuses` (checked first) or `approve_statuses`, and the query is still `PENDING_REVIEW`, the workflow's `ExternalDecisionService` force-applies the decision: `PENDING_REVIEW → APPROVED` or `→ REJECTED`, attributed to the external system (no `review_decisions` row — mirroring the review-timeout path) with the ticket key and actor recorded in the audit reason. Defaults when the lists are omitted: approve = `resolved, closed, done, approved, complete`; reject = `rejected, declined, cancelled, canceled, won't do`. Queries already in a terminal state only get the ticket-metadata update — a late ticket change never reopens or flips a decided query.

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
- **ServiceNow:** Read-only auth probe — `GET /api/now/table/incident?sysparm_limit=1` with the configured credentials; **no test incident is created**
- **Jira:** Read-only auth probe — `GET /rest/api/2/myself` with the configured credentials; **no test issue is created**

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

Sensitive `config` fields — `smtp_password`, `secret`, `bot_token`, `routing_key`, `password` (ServiceNow), `api_token` (Jira), and `webhook_secret` (ticketing sync) — are AES-256-GCM encrypted before being stored in the database. They are never returned in GET responses — only a masked placeholder is shown.

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
| `ATTESTATION_CAMPAIGN_OPENED` | The campaign's eligible reviewers (datasource reviewers) plus all active org admins |
| `API_CONNECTOR_OAUTH2_TOKEN_FAILED` | All active org admins |
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
