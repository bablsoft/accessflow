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

### `.github/workflows/ci.yml` (on every PR)

```yaml
jobs:
  backend:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '21', distribution: 'temurin' }
      - run: cd backend && ./mvnw verify -Pcoverage
      - uses: codecov/codecov-action@v4

  frontend:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: '20' }
      - run: cd frontend && npm ci && npm run test:coverage && npm run build
```

### `.github/workflows/release.yml` (on tag `v*`)

```yaml
jobs:
  build-and-push:
    steps:
      - name: Build backend JAR
        run: cd backend && ./mvnw package -DskipTests
      - name: Build and push backend image
        uses: docker/build-push-action@v5
        with:
          context: ./backend
          push: true
          tags: accessflow/backend:${{ github.ref_name }}
      - name: Build and push frontend image
        uses: docker/build-push-action@v5
        with:
          context: ./frontend
          push: true
          tags: accessflow/frontend:${{ github.ref_name }}
      - name: Package and push Helm chart
        run: helm package charts/accessflow && helm push ...
```

---

## Dockerfiles

### Backend Dockerfile

```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY accessflow-app/target/accessflow-app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Frontend Dockerfile

```dockerfile
FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
```

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
