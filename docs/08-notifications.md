# 08 — Notification System

## Overview

Notifications are dispatched **asynchronously** by the `NotificationDispatcher` service (`accessflow-notifications` module). The dispatcher subscribes to Spring `ApplicationEvent` objects published by the workflow engine and fanouts to all active channels configured for the triggering review plan.

Delivery is non-blocking — notification failures do not affect the query workflow state.

---

## Event Types

| Event | Trigger | Default Recipients |
|-------|---------|-------------------|
| `QUERY_SUBMITTED` | Query enters `PENDING_REVIEW` | All reviewers in the current stage of the review plan |
| `QUERY_APPROVED` | Query fully approved (all stages complete) | Query submitter |
| `QUERY_REJECTED` | Reviewer rejects query | Query submitter |
| `QUERY_CHANGES_REQUESTED` | Reviewer requests changes | Query submitter |
| `QUERY_EXECUTED` | Execution completes successfully | Query submitter |
| `QUERY_FAILED` | Execution error | Query submitter + all ADMIN users |
| `REVIEW_TIMEOUT` | Query has been `PENDING_REVIEW` past `approval_timeout_hours` | All ADMIN users |
| `AI_HIGH_RISK` | AI analysis returns `risk_level = CRITICAL` | All ADMIN + REVIEWER users on the datasource |

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

Uses **Slack Incoming Webhooks API**.

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

**Retry policy:** On non-2xx response or timeout, retry after 30s, then 2m, then 10m. After 3 failed attempts, mark delivery as failed and write to audit log.

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
