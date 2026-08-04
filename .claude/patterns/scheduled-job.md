# Scheduled job

**When to use:** Any `@Scheduled` method.
**Canonical example:** `backend/src/main/java/com/bablsoft/accessflow/discovery/internal/scheduled/DiscoveryScanJob.java:30`
**Second reference:** `backend/src/main/java/com/bablsoft/accessflow/attestation/internal/scheduled/AttestationCampaignOpenJob.java`
**Tests:** `backend/src/test/java/com/bablsoft/accessflow/discovery/internal/scheduled/DiscoveryScanJobTest.java`
**Related:** [modulith-module.md](modulith-module.md), `docs/05-backend.md` → "Scheduled jobs and clustering"

## Shape

```java
// discovery/internal/scheduled/DiscoveryScanJob.java:27
@Component
@RequiredArgsConstructor
@Slf4j
public class DiscoveryScanJob {

    private final DiscoveryScanConfigRepository configRepository;
    private final DiscoveryScanService scanService;
    private final Clock clock;                       // injected, never Instant.now()

    @Scheduled(fixedDelayString = "${accessflow.discovery.scan-poll-interval:PT15M}")
    @SchedulerLock(name = "discoveryScanJob", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        var now = clock.instant();
        var due = configRepository.findAllByEnabledTrue().stream()
                .filter(config -> isDue(config, now))
                .toList();
        if (due.isEmpty()) {
            log.debug("No discovery scans due");
            return;
        }
        var scanned = 0;
        for (var config : due) {
            try {
                scanService.scan(config.getDatasourceId(), config.getOrganizationId(), null);
                scanned++;
            } catch (DiscoveryScanAlreadyRunningException ex) {   // expected "skip" -> info
                log.info("Skipping discovery scan for datasource {} — already running",
                        config.getDatasourceId());
            } catch (RuntimeException ex) {                        // one bad row must not
                log.error("Discovery scan failed for datasource {}",  // abort the batch
                        config.getDatasourceId(), ex);
            }
        }
        log.info("Completed {} discovery scans (due {})", scanned, due.size());
    }
}
```

## Required (acceptance checklist)

- [ ] Class in `<module>/internal/scheduled/`, `@Component @RequiredArgsConstructor @Slf4j`.
- [ ] **`@Scheduled` and `@SchedulerLock` on the same method.** `name` is a unique camelCase
      identifier; `lockAtMostFor` exceeds the worst-case runtime; `lockAtLeastFor` is at least
      one tick.
- [ ] Cadence is `fixedDelayString = "${accessflow.<module>.<kebab-knob>:PT5M}"` — an ISO-8601
      `Duration` with the default inline. Never a hard-coded number of seconds.
- [ ] Inject `java.time.Clock`; never call `Instant.now()`/`LocalDate.now()` directly. Only one
      (UTC) `Clock` bean exists, and the tests depend on being able to substitute it.
- [ ] Per-item `try { … } catch (RuntimeException ex) { log.error(…, ex); }` so one bad row can't
      abort the batch. Give expected domain "skip" exceptions their own `catch` at `log.info`.
- [ ] Any long-running mutation goes through a `core.api`/`<other>.api` interface — a job may not
      reach into another module's `internal`.
- [ ] Row added to `docs/05-backend.md` → "Scheduled jobs and clustering" (job, lock name, cadence
      property, default) **and** the knob documented in `docs/09-deployment.md`.

## Anti-patterns

- **`@Scheduled` without `@SchedulerLock`** → in a multi-replica deployment the job runs *once per
  replica per tick*. For `ErasureExecutionJob` and `RetentionPolicyExecutionJob` that means the
  same deletion applied N times. This is the one scheduling mistake that is silent in dev (single
  replica) and destructive in production — `.claude/hooks/backend-conventions.sh` hard-blocks it.
- **`@Scheduled(fixedDelay = 300000)`** → un-tunable per environment, and the unit is invisible at
  the call site. Use `fixedDelayString` with an ISO-8601 default.
- **`lockAtMostFor` shorter than the real runtime** → the lock expires mid-run and a second
  replica starts a concurrent pass. Size it generously; it only matters when a node dies.
- **No `lockAtLeastFor`** → a job that finishes in milliseconds can be picked up again by another
  node within the same tick, because clocks differ slightly across replicas.
- **`Instant.now()` inside the job** → the test cannot control time, so "is it due yet?" logic
  becomes untestable and you end up asserting on `Thread.sleep`.
- **A single `try` around the whole loop** → the first failing row aborts every remaining one, and
  the batch silently under-processes until someone reads the logs.
- **Catching `Exception`** → swallows `InterruptedException` and programming errors alongside the
  row failure you meant to tolerate. `RuntimeException` is the documented carve-out here.

## Extending

**One-shot cluster-wide locks** (startup reconciliation, a manual trigger) can't use
`@SchedulerLock` — it is annotation-only and tied to `@Scheduled`. Inject
`scheduling.api.DistributedLockService` and call
`runLocked(name, lockAtMostFor, action)`; it returns `true` if the action ran, `false` if another
node held the lock. Same Redis backend, same `accessflow:shedlock:` key prefix, and the ShedLock
types stay inside `scheduling.internal/` so callers need no third-party import.

`@EnableScheduling` already lives in `workflow/internal/config/WorkflowConfiguration` — don't add
a second one. A new module's own `@Configuration` toggles belong in its `internal/config/`.
