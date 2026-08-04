# Modulith module

**When to use:** Adding a new top-level business module under `com.bablsoft.accessflow.<name>`.
**Canonical example:** `backend/src/main/java/com/bablsoft/accessflow/discovery/api/package-info.java:1` (the whole contract is 4 lines); full tree at `backend/src/main/java/com/bablsoft/accessflow/discovery/`.
**Tests:** `backend/src/test/java/com/bablsoft/accessflow/ApplicationModulesTest.java`, `backend/src/test/java/com/bablsoft/accessflow/ApiPackageDependencyTest.java:16`
**Related:** [rest-controller.md](rest-controller.md), [jpa-entity-migration.md](jpa-entity-migration.md), [scheduled-job.md](scheduled-job.md), [backend-test-parity.md](backend-test-parity.md), `docs/02-architecture.md`

## Shape

The backend is a **single Maven module**; boundaries are package conventions enforced by Spring
Modulith. `discovery/` is the reference layout — every directory below is optional except `api/`
and `internal/`:

```
com/bablsoft/accessflow/discovery/
├── package-info.java                 # module marker
├── api/                              # the ONLY package other modules may import
│   ├── package-info.java             # @NamedInterface
│   ├── DiscoveryConfigService.java   # service interfaces
│   ├── DiscoveryFindingStatus.java   # enums
│   ├── DiscoveryFindingView.java     # records (DTOs)
│   └── DiscoveryException.java       # exception hierarchy base
├── events/                           # only if the module publishes domain events
└── internal/
    ├── DefaultDiscoveryConfigService.java     # implementations live at internal root
    ├── config/DiscoveryProperties.java        # @ConfigurationProperties + @Configuration
    ├── persistence/entity/*Entity.java
    ├── persistence/repo/*Repository.java
    ├── scheduled/DiscoveryScanJob.java
    └── web/DiscoveryController.java           # + request/response records, exception handler
```

The `api/` marker is exactly this:

```java
// discovery/api/package-info.java
@NamedInterface
package com.bablsoft.accessflow.discovery.api;

import org.springframework.modulith.NamedInterface;
```

Paginated reads cross the boundary in library-agnostic types, and the *web layer* adapts:

```java
// discovery/internal/web/SpringPageableAdapter.java:12
final class SpringPageableAdapter {
    static PageRequest toPageRequest(Pageable pageable) { ... }   // Spring Pageable -> core.api.PageRequest
}
```

## Required (acceptance checklist)

- [ ] Package tree is `api/` + `internal/` (+ `events/` only if the module publishes events). No
      `@RestController`, `@Entity`, or `@Configuration` at the module root.
- [ ] `<module>/api/package-info.java` carries `@NamedInterface`. That import is the **only**
      third-party reference permitted anywhere under `api/`.
- [ ] Everything in `api/` imports only `java.*`, `javax.*`, and `com.bablsoft.accessflow.*` —
      no Spring, Jackson, Lombok, Hibernate, JSqlParser, or Jakarta, **including in Javadoc**.
- [ ] Paginated `api/` services take `core.api.PageRequest` and return `core.api.PageResponse<T>`;
      the controller adapts via a module-local `internal/web/SpringPageableAdapter`.
- [ ] Cross-module calls go through the other module's `api/` interface or via
      `ApplicationEventPublisher` — never `<other>.internal.*`.
- [ ] Implementations are `Default<Interface>` at `internal/` root, constructor-injected,
      all fields `final`.
- [ ] `mvn -q -f backend/pom.xml test -Dtest='ApplicationModulesTest,ApiPackageDependencyTest'`
      green.

**No registration step.** `ApiPackageDependencyTest` matches
`com.bablsoft.accessflow.*.api..` by wildcard, so a new module is covered automatically. It used
to be a hand-maintained list, which silently stopped covering five modules — don't reintroduce one.

## Anti-patterns

- **Importing `<other>.internal.*`** → `ApplicationModulesTest` fails, and you've coupled to a
  private implementation that is free to change without notice.
- **A Spring type (or Spring Data `Page`, or a Jackson annotation) in `api/`** →
  `ApiPackageDependencyTest` fails. `api/` is a contract other modules compile against; a
  third-party type there makes the whole dependency graph transitive.
- **`@Service` on the interface instead of the `Default*` class** → same purity failure. Annotate
  the implementation.
- **Putting the implementation in `api/` "because it's small"** → other modules can then
  construct it directly, and the interface stops being a seam.
- **A shared `util/` package at the module root** → it becomes a cross-module dumping ground.
  Put helpers in `internal/`, or in `core.api` if genuinely shared.

## Extending

Two modules that need each other is a cycle, and `ApplicationModulesTest` will say so. Resolve it
one of three ways, in order of preference:

1. **Event** — the upstream module publishes; the downstream one consumes with
   `@ApplicationModuleListener`. Default choice for "when X happens, also do Y".
2. **Extract the shared contract into `core.api`** — when both modules need the same *type*.
3. **Invert the dependency** — define the interface in the module that's being called *into*, and
   have the caller depend on it.

Adding a config knob: bind `accessflow.<module>.<kebab-name>` on a `<Module>Properties` record in
`<module>/internal/config/`, use an ISO-8601 `Duration` with the default inline
(`${accessflow.x.y:PT5M}`), and document the row in `docs/09-deployment.md` in the same commit.
Spring's relaxed binding gives you the `UPPER_SNAKE` env form for free — never add an `@Value` alias.
