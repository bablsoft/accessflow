# 09 — Deployment

## Deployment Options

| Option | Best For | Effort |
|--------|---------|--------|
| Docker Compose | Local dev, small teams, evaluation | Low |
| Kubernetes / Helm | Production, enterprise, high availability | Medium |

---

## Docker Compose

### Full `docker-compose.yml`

```yaml
version: '3.9'

services:
  postgres:
    image: postgres:15-alpine
    environment:
      POSTGRES_DB: accessflow
      POSTGRES_USER: accessflow
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ['CMD-SHELL', 'pg_isready -U accessflow']
      interval: 10s
      timeout: 5s
      retries: 5
    restart: unless-stopped

  redis:
    image: redis:7-alpine
    volumes:
      - redis_data:/data
    restart: unless-stopped

  backend:
    image: accessflow/backend:latest
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_started
    environment:
      DB_URL: jdbc:postgresql://postgres:5432/accessflow
      DB_USER: accessflow
      DB_PASSWORD: ${DB_PASSWORD}
      ENCRYPTION_KEY: ${ENCRYPTION_KEY}
      JWT_PRIVATE_KEY: ${JWT_PRIVATE_KEY}
      AI_PROVIDER: ${AI_PROVIDER:-anthropic}
      AI_API_KEY: ${AI_API_KEY}
      REDIS_URL: redis://redis:6379
      CORS_ALLOWED_ORIGIN: http://localhost:3000
    ports:
      - '8080:8080'
    restart: unless-stopped

  frontend:
    image: accessflow/frontend:latest
    # The frontend bundle is built with Vite — env vars are *not* read at container
    # runtime. To point the same image at a different backend, mount your own
    # runtime-config.js over the one shipped in the image:
    volumes:
      - ./runtime-config.js:/usr/share/nginx/html/runtime-config.js:ro
    ports:
      - '3000:80'
    depends_on:
      - backend
    restart: unless-stopped

  # Optional: self-hosted AI (if AI_PROVIDER=ollama)
  ollama:
    image: ollama/ollama:latest
    profiles: ['ollama']
    volumes:
      - ollama_data:/root/.ollama
    ports:
      - '11434:11434'

volumes:
  postgres_data:
  redis_data:
  ollama_data:
```

### `.env` file for Docker Compose

```env
DB_PASSWORD=change_me_strong_password
ENCRYPTION_KEY=0123456789abcdef0123456789abcdef  # 32 hex chars = 16 bytes
JWT_PRIVATE_KEY="-----BEGIN RSA PRIVATE KEY-----\n...\n-----END RSA PRIVATE KEY-----"
AI_PROVIDER=anthropic
AI_API_KEY=sk-ant-...
```

### Quick Start

```bash
# 1. Clone the repo
git clone https://github.com/accessflow/accessflow.git
cd accessflow

# 2. Generate secrets (helper script)
./scripts/generate-dev-secrets.sh   # writes .env

# 3. Start all services
docker compose up -d

# 4. Open the UI
open http://localhost:3000
# Default admin: admin@local / changeme  (seeded by Flyway dev migration)
```

### With Self-Hosted Ollama

```bash
docker compose --profile ollama up -d
# Then pull a model:
docker exec -it accessflow-ollama-1 ollama pull llama3.2
# Set in .env:  AI_PROVIDER=ollama  (no AI_API_KEY needed)
```

---

## Kubernetes / Helm

### Install

```bash
helm repo add accessflow https://charts.accessflow.io
helm repo update

helm install accessflow accessflow/accessflow \
  --namespace accessflow \
  --create-namespace \
  --values my-values.yaml
```

### Full `values.yaml`

```yaml
replicaCount:
  backend: 3
  frontend: 2

image:
  backend:
    repository: accessflow/backend
    tag: "1.0.0"
    pullPolicy: IfNotPresent
  frontend:
    repository: accessflow/frontend
    tag: "1.0.0"
    pullPolicy: IfNotPresent

# Internal PostgreSQL (set enabled: false to use external)
postgresql:
  enabled: true
  auth:
    database: accessflow
    username: accessflow
    existingSecret: accessflow-pg-secret  # key: password
  primary:
    persistence:
      size: 20Gi

# Internal Redis
redis:
  enabled: true
  architecture: standalone

# Application config
config:
  encryptionKeySecret: accessflow-encryption-key   # key: value
  jwtPrivateKeySecret: accessflow-jwt-key          # key: value
  aiProvider: anthropic
  aiApiKeySecret: accessflow-ai-key                # key: value
  corsAllowedOrigin: https://accessflow.company.com
  # Frontend runtime config — rendered into a ConfigMap, mounted as
  # /usr/share/nginx/html/runtime-config.js inside the frontend pod.
  frontend:
    apiBaseUrl: https://accessflow.company.com
    wsUrl: wss://accessflow.company.com/ws

# External DB (when postgresql.enabled=false)
externalDatabase:
  host: ""
  port: 5432
  database: accessflow
  username: accessflow
  existingSecret: ""

# Ingress
ingress:
  enabled: true
  className: nginx
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt-prod
  hosts:
    - host: accessflow.company.com
      paths:
        - path: /api
          pathType: Prefix
          service: backend
        - path: /ws
          pathType: Prefix
          service: backend
        - path: /
          pathType: Prefix
          service: frontend
  tls:
    - secretName: accessflow-tls
      hosts:
        - accessflow.company.com

# Resource limits
resources:
  backend:
    requests:
      cpu: 500m
      memory: 512Mi
    limits:
      cpu: 2000m
      memory: 2Gi
  frontend:
    requests:
      cpu: 100m
      memory: 128Mi
    limits:
      cpu: 500m
      memory: 256Mi

# Horizontal Pod Autoscaler
autoscaling:
  backend:
    enabled: true
    minReplicas: 2
    maxReplicas: 10
    targetCPUUtilizationPercentage: 70

# Pod disruption budget (ensure HA during rolling updates)
podDisruptionBudget:
  backend:
    enabled: true
    minAvailable: 1

# SAML 2.0 SSO (optional)
saml:
  enabled: false
  spEntityId: ""
  idpMetadataUrl: ""
  keystoreSecret: ""
```

### Kubernetes Secrets Setup

```bash
# PostgreSQL password
kubectl create secret generic accessflow-pg-secret \
  --from-literal=password='strong_db_password' \
  -n accessflow

# Encryption key (32 hex bytes)
kubectl create secret generic accessflow-encryption-key \
  --from-literal=value='0123456789abcdef0123456789abcdef' \
  -n accessflow

# JWT RSA private key
kubectl create secret generic accessflow-jwt-key \
  --from-file=value=./jwt_private_key.pem \
  -n accessflow

# AI API key
kubectl create secret generic accessflow-ai-key \
  --from-literal=value='sk-ant-...' \
  -n accessflow
```

---

## Configuration Reference

AccessFlow follows the [12-factor](https://12factor.net/config) approach: every config value
is overrideable via an environment variable, with safe defaults baked into the application so
a fresh container starts on `localhost` without additional config.

Two layers exist:

- **Backend** (`backend/src/main/resources/application.yml`) — Spring Boot reads YAML and
  resolves `${ENV:default}` placeholders against the process environment at startup. Any
  property below is also overrideable via its UPPER_SNAKE_CASE env-var name thanks to Spring
  Boot's [relaxed binding](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.relaxed-binding.environment-variables),
  even if it isn't in the explicit table below. The table lists the values we expect
  operators to tune; everything else stays internal.
- **Frontend** (`frontend/public/runtime-config.js`) — read at page load, *not* at build
  time. See [Frontend Runtime Configuration](#frontend-runtime-configuration) below.

### Backend Environment Variables

#### Server

| Variable | Required | Default | Description |
|----------|---------|---------|-------------|
| `SERVER_PORT` | Optional | `8080` | Backend HTTP port |

#### Database

| Variable | Required | Default | Description |
|----------|---------|---------|-------------|
| `DB_URL` | ✓ | `jdbc:postgresql://localhost:5432/accessflow` | JDBC URL for AccessFlow internal PostgreSQL |
| `DB_USER` | ✓ | `accessflow` | PostgreSQL username |
| `DB_PASSWORD` | ✓ | `accessflow` | PostgreSQL password |
| `REDIS_URL` | Optional | `redis://localhost:6379` | Redis URL for token revocation, async events, and ShedLock scheduler locks |

#### Security & Auth

| Variable | Required | Default | Description |
|----------|---------|---------|-------------|
| `ENCRYPTION_KEY` | ✓ | — | 32-byte hex AES-256-GCM key for datasource credential encryption |
| `JWT_PRIVATE_KEY` | ✓ | — | RSA-2048 PEM private key for JWT RS256 signing |
| `ACCESSFLOW_JWT_ACCESS_TOKEN_EXPIRY` | Optional | `PT15M` | ISO-8601 duration for the access-token TTL |
| `ACCESSFLOW_JWT_REFRESH_TOKEN_EXPIRY` | Optional | `P7D` | ISO-8601 duration for the refresh-token TTL (`HttpOnly` cookie) |
| `AUDIT_HMAC_KEY` | Optional | derived | Hex-encoded HMAC-SHA256 key (≥ 32 bytes) used to chain `audit_log` rows. When unset, the audit module derives a per-deployment key from `ENCRYPTION_KEY` via HKDF-SHA256 and logs a single WARN. Rotating this key starts a new logical chain — historical rows continue to verify under the old key only. |
| `CORS_ALLOWED_ORIGIN` | Optional | `http://localhost:5173` | Frontend origin for CORS policy |
| `ACCESSFLOW_OAUTH2_FRONTEND_CALLBACK_URL` | Optional | `${CORS_ALLOWED_ORIGIN}/auth/oauth/callback` | Where the OAuth2 success/failure handler redirects after the provider roundtrip. The frontend `OAuthCallbackPage` parses `?code=` or `?error=` from the query string. |
| `ACCESSFLOW_OAUTH2_EXCHANGE_CODE_TTL` | Optional | `PT1M` | ISO-8601 duration — TTL of the one-time exchange code in Redis. Codes are single-use; keep short. |

#### Customer-DB Proxy (HikariCP + Execution)

| Variable | Required | Default | Description |
|----------|---------|---------|-------------|
| `ACCESSFLOW_PROXY_CONNECTION_TIMEOUT` | Optional | `30s` | HikariCP `connectionTimeout` for customer-DB pools |
| `ACCESSFLOW_PROXY_IDLE_TIMEOUT` | Optional | `10m` | HikariCP `idleTimeout` |
| `ACCESSFLOW_PROXY_MAX_LIFETIME` | Optional | `30m` | HikariCP `maxLifetime` |
| `ACCESSFLOW_PROXY_LEAK_DETECTION_THRESHOLD` | Optional | `0s` | HikariCP leak-detection threshold (`0s` disables) |
| `ACCESSFLOW_PROXY_EXECUTION_MAX_ROWS` | Optional | `10000` | Hard cap on rows returned by a single query execution |
| `ACCESSFLOW_PROXY_EXECUTION_STATEMENT_TIMEOUT` | Optional | `30s` | Statement-level timeout applied to customer-DB JDBC statements |
| `ACCESSFLOW_PROXY_EXECUTION_DEFAULT_FETCH_SIZE` | Optional | `1000` | Default JDBC fetch size |

#### Custom JDBC Driver Cache

| Variable | Required | Default | Description |
|----------|---------|---------|-------------|
| `ACCESSFLOW_DRIVER_CACHE` | Optional | `${user.home}/.accessflow/drivers` | Filesystem path for cached customer-DB driver JARs. Set to a system path (e.g. `/var/lib/accessflow/drivers`) and mount as a persistent volume in production. |
| `ACCESSFLOW_DRIVERS_REPOSITORY_URL` | Optional | `https://repo1.maven.org/maven2` | Maven repository base URL for on-demand driver downloads. Override for internal Nexus / Artifactory mirrors. |
| `ACCESSFLOW_DRIVERS_OFFLINE` | Optional | `false` | When `true`, disables network resolution and serves only from the cache. Required for air-gapped installs. |

#### Workflow & Notifications

| Variable | Required | Default | Description |
|----------|---------|---------|-------------|
| `ACCESSFLOW_WORKFLOW_TIMEOUT_POLL_INTERVAL` | Optional | `PT5M` | ISO-8601 duration. Cadence at which `QueryTimeoutJob` scans for `PENDING_REVIEW` queries past their plan's `approval_timeout_hours`. ShedLock makes this safe under horizontal scaling. |
| `ACCESSFLOW_PUBLIC_BASE_URL` | Optional | `http://localhost:5173` | Public base URL used in notification email links and webhook payloads |
| `ACCESSFLOW_NOTIFICATIONS_RETRY_FIRST` | Optional | `PT30S` | ISO-8601 duration — delay before the first webhook retry |
| `ACCESSFLOW_NOTIFICATIONS_RETRY_SECOND` | Optional | `PT2M` | ISO-8601 duration — delay before the second webhook retry |
| `ACCESSFLOW_NOTIFICATIONS_RETRY_THIRD` | Optional | `PT10M` | ISO-8601 duration — delay before the third (final) webhook retry |
| `ACCESSFLOW_SECURITY_INVITATION_TTL` | Optional | `P7D` | ISO-8601 duration. TTL of user-invitation tokens issued by `POST /admin/users/invitations`. Pending invitations past this duration are treated as expired on preview/accept; admins can resend to issue a fresh token. |
| `ACCESSFLOW_SECURITY_PASSWORD_RESET_TTL` | Optional | `PT1H` | ISO-8601 duration. TTL of password-reset tokens issued by `POST /api/v1/auth/password/forgot`. Tokens are single-use; users must request a new one if the link expires. |
| `ACCESSFLOW_SECURITY_PASSWORD_RESET_RESET_BASE_URL` | Optional | `http://localhost:5173` | Base URL embedded in password-reset emails. Set this to your production frontend origin (e.g. `https://accessflow.example.com`); the emailed link is `{base}/reset-password/{token}`. |

#### Observability

| Variable | Required | Default | Description |
|----------|---------|---------|-------------|
| `ACCESSFLOW_TRACING_SAMPLING_PROBABILITY` | Optional | `1.0` | Micrometer Tracing sampling probability (`0.0` – `1.0`). Lower this in high-traffic deployments to reduce export volume; MDC trace ids and `ProblemDetail.traceId` are populated regardless. See [docs/05-backend.md](05-backend.md). |

> **Overriding any other property.** Spring Boot's relaxed binding lets you override any
> `application.yml` key via its UPPER_SNAKE_CASE env-var equivalent — e.g.
> `spring.jpa.show-sql` → `SPRING_JPA_SHOW_SQL=true`. The tables above list the values we
> expect operators to tune; advanced framework knobs (`spring.flyway.*`, `spring.jpa.*`,
> `spring.servlet.multipart.*`, etc.) remain reachable via this mechanism.

> **AI provider config is not an environment variable.** Provider, model, API key, endpoint
> (Ollama only) and timeouts live in the per-organization `ai_config` table and are managed via
> `PUT /api/v1/admin/ai-config` at runtime (see [docs/05-backend.md → "AI Query Analyzer Service"](05-backend.md#ai-query-analyzer-service)). For OpenAI and Anthropic, Spring AI's built-in
> default base URLs are used; the `endpoint` column is read only when `provider = OLLAMA`. A
> fresh install has no AI configured until an ADMIN sets it; `POST /api/v1/admin/ai-config/test`
> returns `{"status":"ERROR", "detail":"AI is not configured…"}` until then. No `AI_PROVIDER` /
> `AI_API_KEY` / `AI_MODEL` env var is read.

---

### Frontend Runtime Configuration

The React frontend is built once by Vite — `import.meta.env.VITE_*` values are *baked into
the bundle at build time* and cannot be changed by setting env vars on the running container.
To keep "build once, deploy many" possible, the frontend reads its config at page load from
`/runtime-config.js`, a tiny script that sets `window.__APP_CONFIG__`. Replacing that one
file is sufficient to retarget the bundle.

**Default file** (`frontend/public/runtime-config.js`, shipped in the image at
`/usr/share/nginx/html/runtime-config.js`):

```js
window.__APP_CONFIG__ = {
  apiBaseUrl: "http://localhost:8080",
  wsUrl: "ws://localhost:8080/ws",
};
```

**Resolution precedence** (in `src/config/runtimeConfig.ts`):

1. `window.__APP_CONFIG__.{apiBaseUrl,wsUrl}` from `runtime-config.js` — production override.
2. `import.meta.env.VITE_API_BASE_URL` / `VITE_WS_URL` — build-time, only relevant for
   `npm run dev`.
3. Hard-coded `http://localhost:8080` / `ws://localhost:8080/ws`.

#### Config keys

| Key | Default | Description |
|-----|---------|-------------|
| `apiBaseUrl` | `http://localhost:8080` | Base URL for `axios` REST calls and OAuth2 redirects |
| `wsUrl` | `ws://localhost:8080/ws` | WebSocket URL — the JWT is appended as `?token=` automatically |

#### Docker Compose — bind-mount the file

```yaml
frontend:
  image: accessflow/frontend:latest
  volumes:
    - ./runtime-config.js:/usr/share/nginx/html/runtime-config.js:ro
  ports:
    - '3000:80'
```

Where `./runtime-config.js` on the host contains your environment's URLs.

#### Kubernetes — mount from a ConfigMap

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: accessflow-frontend-config
data:
  runtime-config.js: |
    window.__APP_CONFIG__ = {
      apiBaseUrl: "https://accessflow.company.com",
      wsUrl: "wss://accessflow.company.com/ws",
    };
---
# In the frontend Deployment:
spec:
  template:
    spec:
      containers:
        - name: frontend
          volumeMounts:
            - name: runtime-config
              mountPath: /usr/share/nginx/html/runtime-config.js
              subPath: runtime-config.js
      volumes:
        - name: runtime-config
          configMap:
            name: accessflow-frontend-config
```

The Helm chart renders this ConfigMap automatically from
`config.frontend.{apiBaseUrl,wsUrl}` (see [Full `values.yaml`](#full-valuesyaml) above).

> **Cache.** The default nginx image serves `runtime-config.js` with the same caching
> headers as other static assets. For zero-restart updates, configure your reverse proxy to
> serve this single file with `Cache-Control: no-store` (or version it with a query string
> on the `<script>` tag).

---

## Database Hardening

### `audit_log` is append-only — revoke UPDATE/DELETE on the application role

The application **never** issues `UPDATE`, `DELETE`, or `TRUNCATE` against `audit_log` —
writes go through `AuditLogService.record(...)` which only `INSERT`s, and reads through
`AuditLogService.query(...)` which only `SELECT`s (see [docs/07-security.md → "Audit log
integrity"](07-security.md)). Enforce that contract at the database layer in production so a
future code bug or compromised connection cannot rewrite history:

```sql
-- Run once, after Flyway has applied V9__create_audit_log.sql.
-- Replace `accessflow` with the role used by DB_USER.
REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM accessflow;
GRANT  SELECT, INSERT          ON audit_log TO   accessflow;
```

`SELECT` is retained because the admin audit-log UI
(`GET /api/v1/admin/audit-log`) reads from the same role.

A fully separate "audit writer" role distinct from the general application user remains a
deferred enhancement (see [docs/07-security.md → "Deferred"](07-security.md)); the `REVOKE`
above is the interim hardening that requires no application changes.

---

## Health Checks

| Endpoint | Purpose |
|----------|---------|
| `GET /actuator/health` | Spring Boot health (DB, Redis connectivity) |
| `GET /actuator/health/liveness` | Kubernetes liveness probe |
| `GET /actuator/health/readiness` | Kubernetes readiness probe |
| `GET /actuator/info` | Version and build info |

Kubernetes probe config:
```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  initialDelaySeconds: 10
  periodSeconds: 5
```

---

## Upgrading

1. Pull the new image tag.
2. Flyway runs automatically on startup and applies any new migration scripts.
3. For Kubernetes: `helm upgrade accessflow accessflow/accessflow --values my-values.yaml`
4. Rolling update strategy ensures zero downtime (multiple replicas + PDB).

> **Never** run `spring.jpa.hibernate.ddl-auto=create-drop` in production. Flyway is the sole schema manager.
