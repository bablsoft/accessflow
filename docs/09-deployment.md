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
    environment:
      VITE_API_BASE_URL: http://localhost:8080
      VITE_WS_URL: ws://localhost:8080/ws
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

## Environment Variables Reference

| Variable | Required | Default | Description |
|----------|---------|---------|-------------|
| `DB_URL` | ✓ | — | JDBC URL for AccessFlow internal PostgreSQL |
| `DB_USER` | ✓ | — | PostgreSQL username |
| `DB_PASSWORD` | ✓ | — | PostgreSQL password |
| `ENCRYPTION_KEY` | ✓ | — | 32-byte hex AES-256 key for credential encryption |
| `JWT_PRIVATE_KEY` | ✓ | — | RSA-2048 PEM private key for JWT signing |
| `AUDIT_HMAC_KEY` | Optional | — | Hex-encoded HMAC-SHA256 key (≥ 32 bytes) used to chain `audit_log` rows. When unset, the audit module auto-derives a per-deployment key from `ENCRYPTION_KEY` via HKDF-SHA256 and logs a single WARN. Rotating this key starts a new logical chain — historical rows continue to verify under the old key only. |
| `REDIS_URL` | Optional | `redis://localhost:6379` | Redis URL for token revocation and async events |
| `CORS_ALLOWED_ORIGIN` | Optional | `http://localhost:3000` | Frontend origin for CORS policy |
| `SMTP_HOST` | Optional | — | SMTP host for email notifications |
| `SMTP_PORT` | Optional | `587` | SMTP port |
| `SMTP_USER` | Optional | — | SMTP username |
| `SMTP_PASSWORD` | Optional | — | SMTP password |
| `SMTP_TLS` | Optional | `true` | Enable SMTP STARTTLS |
| `SAML_IDP_METADATA_URL` | Optional | — | URL to IdP metadata XML (required only when SAML SSO is in use) |
| `SAML_SP_ENTITY_ID` | Optional | — | Service Provider entity ID |
| `SAML_KEYSTORE_PATH` | Optional | — | Path to SAML keystore JKS |
| `SAML_KEYSTORE_PASSWORD` | Optional | — | SAML keystore password |
| `SERVER_PORT` | Optional | `8080` | Backend HTTP port |
| `ACCESSFLOW_TRACING_SAMPLING_PROBABILITY` | Optional | `1.0` | Micrometer Tracing sampling probability (`0.0` – `1.0`). Lower this in high-traffic deployments to reduce export volume; MDC trace ids and `ProblemDetail.traceId` are populated regardless. See `docs/05-backend.md` → *Observability and tracing*. |

> **AI provider config is not an environment variable.** Provider, model, API key, endpoint
> (Ollama only) and timeouts live in the per-organization `ai_config` table and are managed via
> `PUT /api/v1/admin/ai-config` at runtime (see [docs/05-backend.md → "AI Query Analyzer Service"](05-backend.md#ai-query-analyzer-service)). For OpenAI and Anthropic, Spring AI's built-in
> default base URLs are used; the `endpoint` column is read only when `provider = OLLAMA`. A
> fresh install has no AI configured until an ADMIN sets it; `POST /api/v1/admin/ai-config/test`
> returns `{"status":"ERROR", "detail":"AI is not configured…"}` until then. No `AI_PROVIDER` /
> `AI_API_KEY` / `AI_MODEL` env var is read.

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
