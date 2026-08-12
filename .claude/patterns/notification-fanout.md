# Notification fan-out

**When to use:** Adding a `NotificationEventType` (or `NotificationChannelType`) value.
**Canonical example:** `backend/src/main/java/com/bablsoft/accessflow/notifications/api/NotificationEventType.java:3` (23 values)
**Tests:** `backend/src/test/java/com/bablsoft/accessflow/notifications/internal/strategy/**`
**Related:** [engine-fanout.md](engine-fanout.md), [backend-i18n.md](backend-i18n.md), `docs/08-notifications.md`

## Shape

One enum value reaches nine Java switches, a Thymeleaf template, fourteen message files, and the
frontend. **Verified 2026-08-12** (#625) — re-derive rather than trust these line numbers:
`grep -rln NotificationEventType backend/src/main`, then check each switch for a `default`.

| Site | File | Line | On a new value |
|---|---|---|---|
| Context assembly | `notifications/internal/NotificationContextBuilder.java` | 137 | **compile error** |
| Slack Block Kit | `notifications/internal/strategy/SlackBlockKitFactory.java` | 182 | **compile error** |
| Discord | `notifications/internal/strategy/DiscordPayloadFactory.java` | 131 | **compile error** |
| MS Teams | `notifications/internal/strategy/MsTeamsPayloadFactory.java` | 179 | **compile error** |
| Telegram | `notifications/internal/strategy/TelegramMessageFactory.java` | 103 | **compile error** |
| Email — subject key | `notifications/internal/strategy/EmailNotificationStrategy.java` | 248 | **compile error** |
| **PagerDuty** | `notifications/internal/strategy/PagerDutyPayloadFactory.java` | 139 | ⚠️ **silent** — has a `default` |
| **Email — template** | `notifications/internal/strategy/EmailNotificationStrategy.java` | 207 | ⚠️ **silent** — has a `default` |
| **Email — subject args** | `notifications/internal/strategy/EmailNotificationStrategy.java` | 235 | ⚠️ **silent** — has a `default` |
| **Ticket body** | `notifications/internal/strategy/TicketDescriptionBuilder.java` | 21 | ⚠️ **silent** — has a `default` |
| Dispatcher / channel scope | `notifications/internal/NotificationDispatcher.java` | `resolveChannels` | org-wide events must be listed explicitly |
| PagerDuty / ticketing triggers | `internal/codec/{PagerDutyTrigger,TicketingTrigger}.java` | | absent ⇒ never pages / never opens a ticket (usually right) |
| Thymeleaf template | `backend/src/main/resources/templates/email/<event>.html` | | silent |
| i18n subject/body | `i18n/messages.properties` + 6 locales | | `MessagesParityTest` |
| **Frontend — in-app label** | `frontend/src/components/common/NotificationBell.tsx` (`labelFor` switch + `routeForNotification`) | | ⚠️ **silent** — has a `default` returning `notifications.events.fallback` |
| Frontend — union + locales | `frontend/src/types/api.ts` (`UserNotificationEventType`) + `locales/*.json` | | silent |

**Six of the ten Java switches are exhaustive**, so the compiler finds those. **PagerDuty, both
remaining Email switches, and the ticket body are not.**

⚠️ **The frontend bell is the one most often missed** (#625 shipped the locale key and the union but
not the `case`, so every admin would have seen a contentless "New notification"). The backend
records an in-app row for *every* event except `TEST` — `NotificationDispatcher.deliver` always
calls `recordInAppNotifications` — so if your event reaches any recipient, it reaches the bell.
Nothing tests for unused locale keys, and the parity test only compares locales to each other.

## Required (acceptance checklist)

- [ ] All ten Java switch sites handled — **verify PagerDuty, both silent Email switches and
      `TicketDescriptionBuilder` by hand**, the compiler will not flag them.
- [ ] `EmailNotificationStrategy` covered at **all three** sites: the template selector, the
      subject-args switch, and the subject-key switch (only the last is exhaustive).
- [ ] `NotificationBell.labelFor` has a `case` — otherwise the in-app entry silently renders
      "New notification" — and `routeForNotification` sends it somewhere useful.
- [ ] `NotificationDispatcher.resolveChannels` lists the event if it fans out org-wide rather than
      to a review plan's channels.
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
