# Backend i18n

**When to use:** Any user-facing string produced by Java — exception detail, Bean Validation
message, notification subject or body.
**Canonical example:** `backend/src/main/resources/i18n/messages.properties:2` (`validation.email.required`); resolution at `backend/src/main/java/com/bablsoft/accessflow/discovery/internal/web/DiscoveryExceptionHandler.java:27`
**Tests:** `backend/src/test/java/com/bablsoft/accessflow/MessagesParityTest.java:20`
**Related:** [rest-controller.md](rest-controller.md), `docs/07-security.md`

## Shape

Seven message files, one baseline plus six locales, all under
`backend/src/main/resources/i18n/`:

```
messages.properties        <- the English baseline (438 error.*, 311 validation.* keys)
messages_de.properties  messages_es.properties  messages_fr.properties
messages_hy.properties  messages_ru.properties  messages_zh_CN.properties
```

Bean Validation references a key — never inline English:

```java
// apigov/internal/web/CreateApiConnectorRequest.java:17
@NotBlank(message = "{validation.api_connector.name.required}")
@Size(min = 3, max = 255, message = "{validation.api_connector.name.size}")
String name
```

Exception handlers resolve through `MessageSource` with the request locale:

```java
// discovery/internal/web/DiscoveryExceptionHandler.java:27
var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
        messageSource.getMessage("error.discovery_scan_already_running", null,
                LocaleContextHolder.getLocale()));
```

A service that throws with a per-call-site message injects `MessageSource` and resolves at the
`throw`, not in the exception constructor.

## Required (acceptance checklist)

- [ ] Key naming: `error.<snake_case>` for exception messages, `validation.<field>.<rule>` for
      constraints. Match the naming of the neighbours — there are 749 existing keys to imitate.
- [ ] Bean Validation `message` is `"{key}"`. Inline English is rejected by
      `backend/checkstyle.xml` on `src/main/java`.
- [ ] Every key added to `messages.properties` is added to **all six** locale files in the same
      commit. Orphans (present in a locale but not the baseline) fail the same test.
- [ ] Handlers use `LocaleContextHolder.getLocale()` — **except `SecurityExceptionHandler`**,
      which writes directly to `HttpServletResponse` outside the locale filter and must use
      `request.getLocale()`.
- [ ] SLF4J log messages stay in English, in code. They are developer-facing and are deliberately
      not translated.
- [ ] `mvn -q -f backend/pom.xml test -Dtest=MessagesParityTest` green.

## Anti-patterns

- **Adding a key to `messages.properties` only** → `MessagesParityTest` iterates every
  non-English `SupportedLanguage` and fails the build. It is a parameterized test, so you get one
  failure per locale, not one overall.
- **`ex.getMessage()` as the `ProblemDetail` detail** → the message was built in the exception
  constructor, in English, before any locale was known. Resolve a key at the handler.
- **Interpolating a value into the key** (`"error.datasource_" + type + "_failed"`) → the key
  becomes invisible to the parity test and to grep. Use one key with `{0}` args:
  `messageSource.getMessage("error.datasource_failed", new Object[]{type}, locale)`.
- **`@NotBlank(message = "Name is required")`** → unlocalizable, and Checkstyle blocks it.
- **Translating log messages** → makes production logs unsearchable across deployments.
- **Deleting a key from a locale file to "fix" a parity failure** → that inverts the fix; the
  baseline is the source of truth.

## Extending

**Adding a language** requires only a new `messages_<locale>.properties` plus the enum value in
`core.api.SupportedLanguage` — no code changes. `MessagesParityTest` picks it up automatically via
`SupportedLanguage.values()`, so the new file must be complete before it is registered.

The frontend has the mirror-image rule and its own parity test
(`frontend/src/locales/__tests__/locales.parity.test.ts` against `en.json`). A change that adds a
user-facing string on both sides touches **fourteen** files; that's expected, and
`.claude/hooks/pre-commit-check.sh` warns when only some of them are staged.
