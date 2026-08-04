# Notification fan-out

**When to use:** Adding a `NotificationEventType` (or `NotificationChannelType`) value.
**Canonical example:** `backend/src/main/java/com/bablsoft/accessflow/notifications/api/NotificationEventType.java:3` (21 values)
**Tests:** `backend/src/test/java/com/bablsoft/accessflow/notifications/internal/strategy/**`
**Related:** [engine-fanout.md](engine-fanout.md), [backend-i18n.md](backend-i18n.md), `docs/08-notifications.md`

## Shape

One enum value reaches seven Java switches, a Thymeleaf template, fourteen message files, and the
frontend union. **Verified 2026-08-04** — re-derive with
`grep -rln NotificationEventType backend/src/main`:

| Site | File | Line | On a new value |
|---|---|---|---|
| Context assembly | `notifications/internal/NotificationContextBuilder.java` | 109 | **compile error** |
| Slack Block Kit | `notifications/internal/strategy/SlackBlockKitFactory.java` | 181 | **compile error** |
| Discord | `notifications/internal/strategy/DiscordPayloadFactory.java` | 130 | **compile error** |
| MS Teams | `notifications/internal/strategy/MsTeamsPayloadFactory.java` | 178 | **compile error** |
| Telegram | `notifications/internal/strategy/TelegramMessageFactory.java` | 102 | **compile error** |
| **PagerDuty** | `notifications/internal/strategy/PagerDutyPayloadFactory.java` | 139 | ⚠️ **silent** — has a `default` |
| **Email — template** | `notifications/internal/strategy/EmailNotificationStrategy.java` | 172 | ⚠️ **silent** — has a `default` |
| Email — subject args | `notifications/internal/strategy/EmailNotificationStrategy.java` | 196 | check both |
| Ticket body (ServiceNow/Jira) | `notifications/internal/strategy/TicketDescriptionBuilder.java` | | check |
| Thymeleaf template | `backend/src/main/resources/templates/email/<event>.html` (12 exist) | | silent |
| i18n subject/body | `i18n/messages.properties` + 6 locales | | `MessagesParityTest` |
| Frontend | `frontend/src/types/api.ts:1737` (`UserNotificationEventType`) + `locales/*.json` | | silent |

**Five of the seven switches are exhaustive**, so the compiler finds those. **PagerDuty and Email
are not** — both have a `default`, so a new event silently produces a generic PagerDuty payload
and falls through to whatever the email default is. Those two are the ones to check by hand.

`NotificationChannelType` has its own comparable surface, including
`notifications/internal/codec/ChannelConfigCodec.java` for the encrypted per-channel config.

## Required (acceptance checklist)

- [ ] All seven Java switch sites handled — **verify PagerDuty and Email by hand**, the compiler
      will not flag them.
- [ ] `EmailNotificationStrategy` covered at **both** sites: the template selector (`:172`) and
      the subject-args switch (`:196`).
- [ ] A Thymeleaf template at `templates/email/<event>.html` if the email strategy routes the
      event to one.
- [ ] Subject and body keys in `messages.properties` **and all six** locale files
      (see [backend-i18n.md](backend-i18n.md)).
- [ ] Frontend `UserNotificationEventType` union + the `enums.*` label in all seven locale JSONs,
      if the event surfaces in the in-app notification feed.
- [ ] A `PagerDutyTrigger` mapping and a listener entry if the event should page.
- [ ] Row added to `docs/08-notifications.md`.
- [ ] Webhook payload shape unchanged — the `X-AccessFlow-Signature` HMAC covers the body, so a
      shape change is a breaking contract change for every subscriber.
- [ ] Per-factory tests extended under `notifications/internal/strategy/`.

## Anti-patterns

- **Trusting the compiler** → PagerDuty and Email have `default` branches. Green build, silently
  wrong notification.
- **Adding the template selector case but not the subject-args case** → the email renders with an
  untranslated or mis-parameterized subject. Both switches are in the same file, ~24 lines apart.
- **A new event that blocks the workflow** → notification delivery is async and non-blocking by
  contract. A failure must never affect query workflow state. Never call the dispatcher
  synchronously from the workflow engine; publish an `ApplicationEvent`.
- **Adding the enum value and the template but no i18n keys** → `MessagesParityTest` fails six
  times over, once per locale.
- **Changing an existing webhook payload's field names** → subscribers verify the HMAC over the
  body; renaming a field breaks them silently at the far end.
- **Putting the secret in the channel config unencrypted** → `smtp_password` and `webhook_secret`
  are AES-256-GCM encrypted before persistence and never returned in GET responses.

## Extending

Adding a whole new **channel type** (a sibling of Slack/Discord/Teams/Telegram/PagerDuty) means a
new `*NotificationStrategy` plus its payload factory, a `ChannelConfigCodec` branch for the
encrypted config fields, the enum value, i18n, the frontend union and config form, and
`docs/08-notifications.md`. Follow `TelegramMessageFactory` as the smallest complete example.

Retry policy is fixed and shared: 1 attempt + 3 retries at +30s / +2m / +10m, configurable via
`accessflow.notifications.retry.{first,second,third}`. Don't add a per-channel retry loop.
