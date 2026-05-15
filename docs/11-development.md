# 11 — Development Guidelines

## Repository Structure

```
accessflow/                         # Monorepo root
├── backend/                        # Java / Spring Boot (Maven multi-module)
│   ├── accessflow-parent/
│   ├── accessflow-api/
│   ├── accessflow-core/
│   ├── accessflow-proxy/
│   ├── accessflow-workflow/
│   ├── accessflow-ai/
│   ├── accessflow-security/
│   ├── accessflow-notifications/
│   ├── accessflow-audit/
│   └── accessflow-app/
├── frontend/                       # React / Vite / TypeScript
├── charts/accessflow/              # Helm chart
├── docker/                         # Dockerfiles, docker-compose.yml
├── docs/                           # Markdown documentation (this folder)
├── scripts/
│   ├── generate-dev-secrets.sh     # Generates .env with dev-safe keys
│   └── seed-dev-data.sh            # Seeds demo data into local DB
├── .github/
│   └── workflows/
│       ├── ci.yml                  # Build + test on every PR
│       └── release.yml             # Build + push Docker images on tag push
└── README.md
```

---

## Local Development Setup

### Prerequisites

| Tool | Version | Install |
|------|---------|---------|
| Java JDK | 21 | `sdk install java 21-tem` (SDKMAN) |
| Maven | 3.9+ | Bundled via `./mvnw` wrapper |
| Node.js | 20 LTS | `nvm install 20` |
| Docker Desktop | Latest | docker.com |

### Steps

```bash
# 1. Clone
git clone https://github.com/accessflow/accessflow.git
cd accessflow

# 2. Start infrastructure (Postgres + Redis only)
cd docker
docker compose up postgres redis -d
cd ..

# 3. Generate dev secrets (creates .env in project root)
./scripts/generate-dev-secrets.sh

# 4. Start backend
cd backend
./mvnw spring-boot:run -pl accessflow-app
# API available at http://localhost:8080

# 5. Start frontend (new terminal)
cd frontend
npm install
npm run dev
# UI available at http://localhost:5173

# 6. Seed demo data (optional)
./scripts/seed-dev-data.sh
# Creates: admin@local / changeme
#          analyst@local / changeme
#          reviewer@local / changeme
#          1 sample datasource (points to local Postgres)
#          2 sample review plans
```

---

## Testing Strategy

### Backend

| Layer | Framework | What to Test |
|-------|-----------|-------------|
| Unit | JUnit 5 + Mockito | Service classes, domain logic, state machine transitions, AI prompt building |
| Integration | Spring Boot Test + Testcontainers | Repository queries, proxy engine with real PostgreSQL/MySQL, workflow end-to-end |
| API | RestAssured + Spring Boot Test | All REST endpoints, auth enforcement, permission checks, error responses |
| Security | Custom tests | JWT forgery, permission boundary violations, SQL injection attempts |

**Coverage target:** ≥80% line coverage on `accessflow-core`, `accessflow-proxy`, `accessflow-workflow`.

```bash
# Run all backend tests
cd backend && ./mvnw verify

# Run with coverage report
cd backend && ./mvnw verify -Pcoverage
# Report: backend/accessflow-app/target/site/jacoco/index.html
```

### Frontend

| Layer | Framework | What to Test |
|-------|-----------|-------------|
| Unit | Vitest | Utility functions, store logic, API client helpers |
| Component | React Testing Library | Key components (SqlEditor, ReviewCard, PermissionMatrix) |
| E2E | Playwright | Login flow, submit query, review queue approval flow |

```bash
cd frontend
npm run test          # Vitest unit + component tests
npm run test:e2e      # Playwright E2E (requires running backend)
npm run test:coverage # Coverage report
```

### Helm chart

```bash
helm dependency update charts/accessflow
helm lint charts/accessflow
helm template accessflow charts/accessflow > /dev/null
```

CI runs the same checks in [`.github/workflows/helm-ci.yml`](../.github/workflows/helm-ci.yml).
The chart is published to the helm repo at `https://bablsoft.github.io/accessflow` by
[`release.yml`](../.github/workflows/release.yml) on every tagged release — see
[`docs/09-deployment.md` → "Chart development" / "Releasing the chart"](09-deployment.md#chart-development).

### Testcontainers Setup

```java
@SpringBootTest
@Testcontainers
class QueryProxyServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
        .withDatabaseName("testdb")
        .withUsername("test")
        .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
}
```

---

## Coding Standards

### Java

- **Style guide:** Google Java Style Guide
- **Enforcement:** Checkstyle plugin in Maven build (`./mvnw checkstyle:check`)
- **Formatting:** Spotless with Google Java Format (`./mvnw spotless:apply`)
- **No** `@Autowired` field injection — use constructor injection only
- **No** `@Transactional` on controllers — only on service methods
- Service interfaces in `accessflow-core`; implementations in their respective modules
- All API responses use explicit DTO classes — never return JPA entities directly

### TypeScript / Frontend

- **Linting:** ESLint with `@typescript-eslint` strict rules
- **Formatting:** Prettier (runs on save via VS Code config)
- `strict: true` in `tsconfig.json` — no implicit `any`
- All API response shapes defined in `src/types/api.ts`
- React Query for all server state — no `useEffect` for data fetching
- Zustand stores for UI/auth state only (not server data)

### Database / Migrations

- All schema changes via Flyway: `V{n}__{Snake_case_description}.sql`
- **Never** modify an existing migration file
- **Never** use `spring.jpa.hibernate.ddl-auto=create` or `update` in any environment except local tests with Testcontainers
- All new columns must have a default value or be nullable (for zero-downtime deploys)

### Git Workflow

```
main                → production-ready, tagged releases
develop             → integration branch
feature/AF-{n}-description   → feature branches (from develop)
fix/AF-{n}-description       → bug fix branches (from develop)
hotfix/AF-{n}-description    → critical fixes (from main, merge back to both)
```

**PR requirements:**
- Passing CI (build + tests + linting)
- At least 1 reviewer approval
- No merge without passing Checkstyle and Spotless checks
- PR description must reference the issue number

---

## CI/CD Pipelines

The repository ships four GitHub Actions workflows:

| Workflow | Trigger | Purpose |
|----------|---------|---------|
| [`.github/workflows/ci.yml`](../.github/workflows/ci.yml) | Push / PR to `main` touching `backend/**` | Java 25 + Maven build, JaCoCo coverage, JUnit test report |
| [`.github/workflows/frontend-ci.yml`](../.github/workflows/frontend-ci.yml) | Push / PR to `main` touching `frontend/**` | Node 24 + npm: lint, typecheck, Vitest coverage, Vite build |
| [`.github/workflows/helm-ci.yml`](../.github/workflows/helm-ci.yml) | Push / PR to `main` touching `charts/**` | `helm lint` + `helm template` (default + external-services paths) on the AccessFlow chart |
| [`.github/workflows/release.yml`](../.github/workflows/release.yml) | `workflow_dispatch` (manual, with `version` input) | Tags `vX.Y.Z`, builds & pushes multi-arch Docker images to GHCR, publishes the Helm chart to `gh-pages`, and creates a GitHub Release with auto-generated notes |

### Release pipeline (`release.yml`)

Triggered manually from the Actions tab. The maintainer provides a semver `version` input (e.g. `1.2.3`, without the leading `v`). The workflow:

1. **Validates** the version against `^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$`.
2. Checks out `main`.
3. **Bumps the Maven version** in `backend/pom.xml` via `mvn versions:set -DnewVersion=${INPUT}`.
4. **Bumps the frontend version** in `frontend/package.json` via `npm version ${INPUT} --no-git-tag-version`.
5. **Detaches HEAD**, commits the two bumps as `chore(release): v${VERSION}`, and pushes the commit *as a tag* (`git push origin HEAD:refs/tags/v${VERSION}`) — `main` is **never modified**. Checking out the tag shows pom.xml / package.json at the bumped version; checking out `main` keeps the SNAPSHOT.
6. Builds and pushes **multi-arch** (`linux/amd64`, `linux/arm64`) Docker images via `docker/build-push-action@v6`:
   - `ghcr.io/<owner>/accessflow-backend:${VERSION}` + `:latest`
   - `ghcr.io/<owner>/accessflow-frontend:${VERSION}` + `:latest`
   The frontend image receives `APP_VERSION` as a `--build-arg`, which Vite injects as `__APP_VERSION__` into the bundle (see `frontend/vite.config.ts`).
7. **Publishes a GitHub Release** via `softprops/action-gh-release@v2` with `generate_release_notes: true`. The workflow resolves the previous semver tag (`git tag -l 'v*' --sort=-v:refname`, filtered to strict `vX.Y.Z[-suffix]`) and passes it as `previous_tag` so the changelog covers PRs merged since that tag — or every PR, when no prior tag exists (first release).
8. **Publishes the Helm chart** — rewrites `charts/accessflow/Chart.yaml` so `version` and `appVersion` both equal `${VERSION}`, runs `helm dependency update`, then `helm/chart-releaser-action@v1.7.0` packages the chart and pushes the `.tgz` plus updated `index.yaml` to the `gh-pages` branch (served at `https://<owner>.github.io/accessflow`). GitHub Pages must be enabled once in **Repo Settings → Pages → Source = `gh-pages`** before the chart repo is reachable; after that, every release adds a new version automatically.

### Version surfacing

- **Backend**: the `spring-boot-maven-plugin` `build-info` goal generates `META-INF/build-info.properties` at build time. Spring Boot's actuator auto-publishes this as `info.build.*` on `/actuator/info` (which is `permitAll()` in `SecurityConfiguration`, alongside `/actuator/health/**`).
- **Frontend**: `vite.config.ts` reads `process.env.VITE_APP_VERSION` (set by the release workflow as a build-arg) and falls back to `package.json#version` for local `npm run dev`. The value is exposed as `APP_VERSION` from `src/config/version.ts` and rendered under the brand mark in the Sidebar.

---

## Dockerfiles

The actual Dockerfiles live at [`backend/Dockerfile`](../backend/Dockerfile) and [`frontend/Dockerfile`](../frontend/Dockerfile). Highlights:

### Backend ([`backend/Dockerfile`](../backend/Dockerfile))

Multi-stage build using the official Maven image for compilation and the Temurin JRE for the runtime layer:

```dockerfile
FROM maven:3-eclipse-temurin-25-alpine AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -q -DskipTests dependency:go-offline
COPY src src
RUN mvn -B -DskipTests package && mv target/*.jar target/app.jar

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build --chown=app:app /workspace/target/app.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

### Frontend ([`frontend/Dockerfile`](../frontend/Dockerfile))

Multi-stage Node 24 build → nginx:alpine runtime. The `APP_VERSION` build-arg is forwarded to Vite via `VITE_APP_VERSION`:

```dockerfile
FROM node:24-alpine AS build
WORKDIR /app
ARG APP_VERSION=0.0.0
ENV VITE_APP_VERSION=$APP_VERSION
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
```

The companion [`frontend/nginx.conf`](../frontend/nginx.conf) sets `Cache-Control: no-store` on `runtime-config.js` and `index.html`, caches hashed asset bundles for 7 days, and falls back to `index.html` for client-side routes.

---

## IDE Setup

### VS Code Extensions (recommended)

```json
{
  "recommendations": [
    "vscjava.vscode-java-pack",
    "pivotal.vscode-spring-boot",
    "dbaeumer.vscode-eslint",
    "esbenp.prettier-vscode",
    "bradlc.vscode-tailwindcss",
    "ms-azuretools.vscode-docker",
    "redhat.vscode-yaml"
  ]
}
```

### IntelliJ IDEA

- Import as Maven project (root `pom.xml`)
- Enable annotation processing (for Lombok if used)
- Install `google-java-format` plugin and enable auto-format on save
- Run configuration: `accessflow-app` main class with env vars from `.env`
