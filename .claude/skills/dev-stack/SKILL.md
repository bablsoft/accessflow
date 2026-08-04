---
name: dev-stack
description: Bring up, inspect, or tear down an AccessFlow local stack — the infra-only dev loop, the zero-config demo stack, one of the three e2e stacks, or the website. Handles the port collisions, tells you the seeded credentials, and never tears down a stack you did not name. Trigger when the user says "start the app", "run it locally", "spin up the stack", "restart the backend", "boot the e2e stack", "why is 5173 taken", or "give me a working login".
---

# dev-stack

## Inputs

- **target** — `dev` (default) | `demo` | `e2e` | `e2e-setup` | `e2e-sso` | `website`
- **action** — `up` (default) | `status` | `logs` | `down`

If the user says "start the app" with no qualifier, that is `dev up`. If they mention a spec or
Playwright, it is one of the e2e targets. If they want to *look at the product* with no setup, it
is `demo`.

## The stacks

| Target | What runs | Ports |
|---|---|---|
| `dev` | `backend/docker-compose-dev.yml` (Postgres + Redis + Mailcrab) **+** `mvn -f backend/pom.xml spring-boot:run` **+** the `frontend` launch config | API 8080 · SPA 5173 · Mailcrab UI 1080 · PG 5432 · Redis 6379 |
| `demo` | root `docker-compose.yml` — the whole product, zero config | API 8080 · SPA 5173 |
| `e2e` | `e2e/docker-compose.e2e.yml`, images built from the working tree, admin seeded via `bootstrap` | API 8080 · SPA 5173 |
| `e2e-setup` | `e2e/docker-compose.e2e.setup.yml`, **no admin seeded** (first-run wizard) | API 8081 · SPA 5174 |
| `e2e-sso` | `e2e/docker-compose.e2e.sso.yml` + a mock SimpleSAMLphp IdP | API 8082 · SPA 5175 · IdP 8085 |
| `website` | the `website` launch config (`python3 -m http.server`, no build step) | 8090 |

## Workflow

### 1. Preflight

```bash
docker info >/dev/null || echo "Docker is not running"
lsof -i :5173 -i :8080 -sTCP:LISTEN
```

**The 5173 collision is the single most common trap.** The `dev`, `demo` and `e2e` targets all
bind host port 5173, and the user's own local app often already holds it. If it is taken, say so
and offer the choice — free the port, or set `E2E_BASE_URL` / `E2E_API_BASE` for the e2e targets.
Never silently kill the process holding it.

### 2. Bring it up

- **Infra / compose**: `docker compose -f <file> up -d --wait` (the `--wait` is what makes the
  next step reliable). For the e2e targets prefer the packaged scripts, which already carry the
  right flags: `cd e2e && npm run stack:up` / `stack:setup:up` / `stack:sso:up`.
- **Frontend and website**: always use `preview_start` with the `.claude/launch.json` name
  (`frontend`, `website`). **Never run `npm run dev` through Bash** — it holds the shell and the
  output is not surfaced.
- **Backend in `dev`**: `mvn -f backend/pom.xml spring-boot:run`, backgrounded. It needs env vars
  (`DB_PASSWORD`, `ENCRYPTION_KEY`, `JWT_PRIVATE_KEY`, …); the reference is
  `docs/09-deployment.md`. If they are not set, say so rather than inventing values — or suggest
  `demo`, which ships committed insecure keys for exactly this reason.

### 3. Wait and report

```bash
curl -fsS http://localhost:<api-port>/actuator/health
```

Then tell the user, in one block: the SPA URL, the API URL, the Mailcrab URL when it is running,
and the login. For the **e2e** stack the seeded admin is:

```
e2e@accessflow.test / E2ePassword!123      (org "E2E Test Org", role ADMIN)
```

`e2e-setup` seeds **nothing** on purpose — the wizard at `/setup` is the point.

### 4. `status` / `logs` / `down`

- `status` — container health per stack plus which stack owns 5173/8080/8081/8082.
- `logs` — `npm run stack:logs` (or `docker compose -f <file> logs --tail=200`).
- `down` — `docker compose -f <file> down -v`. **Only the stack the user named.**

## Definition of done

- [ ] `/actuator/health` returns `UP` on the target's API port.
- [ ] The SPA responds on its port (verify with `preview_start` + a read, not by asking the user).
- [ ] The seeded credentials and every URL were printed.
- [ ] `status` shows no orphaned stack still holding a port.

## Out of scope — do not do these

- **Never** `docker compose down -v` a stack the user did not name. `-v` drops volumes, so it
  destroys their local data.
- **Never** edit the committed demo keys in the root `docker-compose.yml`. They are deliberately
  insecure and deliberately committed so `docker compose up` works on a fresh clone
  (`CLAUDE.md` → Repository Layout).
- **Never** kill a process holding a port without asking.
- Don't run the Playwright suite from here — that is the e2e workflow; this skill only gets the
  stack up.
