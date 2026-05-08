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
| `QUERY_CHANGES_REQUESTED` | Reviewer requests changes | Query submitter | deferred — no event published yet |
| `QUERY_EXECUTED` | Execution completes successfully | Query submitter | deferred — proxy executor not implemented |
| `QUERY_FAILED` | Execution error | Query submitter + all ADMIN users | deferred — proxy executor not implemented |
| `REVIEW_TIMEOUT` | Query has been `PENDING_REVIEW` past `approval_timeout_hours` (auto-rejected by `QueryTimeoutJob`) | Query submitter (admin fan-out tracked under [accessflow#102](https://github.com/partqam/accessflow/issues/102)) | implemented — currently reuses the `QUERY_REJECTED` email/Slack template; a dedicated template is tracked under [accessflow#101](https://github.com/partqam/accessflow/issues/101) |

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

Email bodies are rendered using **Thymeleaf** HTML templates located in `resources/templates/email/`. One template per event type. Templates include:
- Query summary (datasource, query type, SQL preview — first 200 chars)
- AI risk badge (color-coded)
- Direct link to query detail page in the AccessFlow UI
- Approve / Reject action links for review request emails (link to UI, not direct API calls)

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
- For `QUERY_SUBMITTED` events: **Approve** and **Reject** buttons are deep links to the UI (not direct API calls — reviewers must be authenticated)

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

**Retry policy:** One initial attempt followed by up to three scheduled retries at +30 s, +2 min, +10 min — four total attempts. Retries are scheduled on a virtual-thread `TaskScheduler`; each attempt re-fetches the channel and generates a fresh `X-AccessFlow-Delivery` UUID. Per-attempt delays are configurable via `accessflow.notifications.retry.{first,second,third}` (default `PT30S`, `PT2M`, `PT10M`). After exhaustion the dispatcher logs an `ERROR` line including the channel id, event type, and last exception. Audit-log integration is deferred until the audit module ships.

---

## Admin: Testing Channels

`POST /admin/notification-channels/{id}/test` sends a test payload for the configured channel type:

- **Email:** Sends a test email to the configured `from_address` (or an address specified in the request body)
- **Slack:** Posts "✅ AccessFlow notification channel test successful" to the configured channel
- **Webhook:** Posts a `{"event": "TEST", "timestamp": "..."}` payload to the webhook URL

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

The `config.smtp_password_encrypted` and `config.secret` fields are AES-256 encrypted before being stored in the database. They are never returned in GET responses — only a masked placeholder is shown.

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
| `REVIEW_TIMEOUT` | The original submitter |
| `AI_HIGH_RISK` | All active org admins |
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
