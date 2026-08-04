# REST controller

**When to use:** Any new endpoint under `/api/v1/`.
**Canonical example:** `backend/src/main/java/com/bablsoft/accessflow/discovery/internal/web/DiscoveryController.java:40`
**Companion:** `backend/src/main/java/com/bablsoft/accessflow/discovery/internal/web/DiscoveryExceptionHandler.java:21`
**Tests:** `backend/src/test/java/com/bablsoft/accessflow/discovery/internal/web/DiscoveryControllerIntegrationTest.java`, `.../DiscoveryWebModelsTest.java`
**Related:** [modulith-module.md](modulith-module.md), [backend-i18n.md](backend-i18n.md), [backend-test-parity.md](backend-test-parity.md), `docs/04-api-spec.md`

## Shape

Package-private class in `<module>/internal/web/`, constructor-injected, one `@Operation` plus one
`@ApiResponse` per status the method can actually return:

```java
// discovery/internal/web/DiscoveryController.java:34
@RestController
@RequestMapping("/api/v1/datasources/{datasourceId}/discovery")
@Tag(name = "Sensitive-Data Discovery", description = "...")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('PERM_DATA_CLASSIFICATION_MANAGE')")
class DiscoveryController {

    private final DiscoveryConfigService configService;   // an api/ interface, always final

    @PutMapping("/config")
    @Operation(summary = "Create or update the datasource's discovery settings")
    @ApiResponse(responseCode = "200", description = "Updated settings")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "404", description = "Datasource not found")
    DiscoveryConfigResponse updateConfig(@PathVariable UUID datasourceId,
                                         @Valid @RequestBody UpdateDiscoveryConfigRequest body,
                                         Authentication authentication) {
        var caller = currentClaims(authentication);
        return DiscoveryConfigResponse.from(
                configService.upsert(datasourceId, caller.organizationId(), body.toCommand()));
    }
}
```

`ResponseEntity` **only** for a non-default status or custom headers — everything else returns the
concrete type:

```java
// :105 — 202 Accepted for an async trigger
ResponseEntity<Void> triggerScan(...) {
    scanTriggerService.requestScan(...);
    return ResponseEntity.status(HttpStatus.ACCEPTED).build();
}
```

Web models are records in `internal/web/` with a static mapper — never an `api/` DTO, never an entity:

```java
// discovery/internal/web/DiscoveryConfigResponse.java:8
public record DiscoveryConfigResponse(UUID datasourceId, boolean enabled, ...) {
    public static DiscoveryConfigResponse from(DiscoveryScanConfigView view) { ... }
}
```

Each module owns a `@RestControllerAdvice`. **`@Order(Ordered.HIGHEST_PRECEDENCE)` is required** —
without it the `security` module's `Exception.class` catch-all wins the resolution race and your
domain exception becomes a generic 500:

```java
// discovery/internal/web/DiscoveryExceptionHandler.java:18
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
class DiscoveryExceptionHandler {
    private final MessageSource messageSource;

    @ExceptionHandler(DiscoveryScanAlreadyRunningException.class)
    ProblemDetail handleScanAlreadyRunning(DiscoveryScanAlreadyRunningException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                messageSource.getMessage("error.discovery_scan_already_running", null,
                        LocaleContextHolder.getLocale()));
        pd.setProperty("error", "DISCOVERY_SCAN_ALREADY_RUNNING");
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }
}
```

## Required (acceptance checklist)

- [ ] Endpoint is documented in `docs/04-api-spec.md` **before** it is written. Never add an
      endpoint that isn't in the spec.
- [ ] Controller is package-private, lives in `<module>/internal/web/`, `@RequiredArgsConstructor`,
      all dependencies `final` and typed as `api/` interfaces.
- [ ] Every method has `@Operation` + one `@ApiResponse` per reachable status.
- [ ] Path is `kebab-case` under `/api/v1/`. Correct statuses: 201 create, 202 async accept,
      204 delete, 422 SQL parse error.
- [ ] Request/response models are records in `internal/web/` with a static `from(...)`/`toCommand()`.
- [ ] Every Bean Validation constraint on the request record has a matching `Form.Item` rule in the
      frontend form that posts to it — and vice versa. Same commit, both sides.
- [ ] Errors return `ProblemDetail` with `setProperty("error", "<SCREAMING_SNAKE>")` and
      `"timestamp"`; detail resolved through `MessageSource`, never `ex.getMessage()`.
- [ ] Module advice carries `@Order(Ordered.HIGHEST_PRECEDENCE)`.
- [ ] **No business logic.** See below for where the line is.

## Anti-patterns

- **Business logic in the controller** → the service is what tests exercise; logic here is
  effectively untested, because controller integration tests `@MockitoBean` the service.
  Concretely: a `StringWriter`, a `DateTimeFormatter`, CSV/PDF assembly, a per-row `Consumer`,
  encryption, retry loops, JSON-tree rewriting, event publishing, or a `for` loop over domain
  entities. If you reach for one, introduce or extend a service.
  *The ceiling is `DiscoveryController.recordDecisionAudits` (`:112`)* — pure fan-out over an
  outcome the service already computed.
- **Returning an entity or an `api/` DTO** → leaks persistence/internal shape into the wire contract and
  will serialize `@JsonIgnore`-adjacent fields the moment someone removes the annotation.
- **`ex.getMessage()` as the ProblemDetail detail** → unlocalized, and it leaks internals to the
  client. Resolve a key through `MessageSource`.
- **`@Transactional` on a controller** → the transaction spans view rendering and swallows the
  service's own boundary.
- **Omitting `@Order` on the module advice** → your handler silently never fires.
- **`ResponseEntity<Foo>` for a plain 200** → noise; return `Foo`.

## Extending

A new permission means a new `@PreAuthorize("hasAuthority('PERM_…')")` — prefer class level when
every method needs it, method level when they differ. The authority string must exist in the
permission enum; grep an existing `PERM_` value to find it.

For a paginated endpoint, accept Spring's `Pageable`, convert with the module's
`internal/web/SpringPageableAdapter`, and return a `*PageResponse` record wrapping
`core.api.PageResponse<T>` — see [modulith-module.md](modulith-module.md).
