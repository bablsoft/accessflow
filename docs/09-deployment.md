# 09 — Deployment

## Deployment Options

| Option | Best For | Effort |
|--------|---------|--------|
| Docker Compose | Local dev, small teams, evaluation | Low |
| Kubernetes / Helm | Production, enterprise, high availability | Medium |

---

## Docker Compose

The repository ships [`docker-compose.yml`](../docker-compose.yml) as a **zero-config demo
stack**: a fresh `docker compose up -d` against a clean clone pulls the published GHCR
images for the backend and frontend, boots Postgres + Redis alongside them, and gets
you to a working `http://localhost:5173` where the in-app onboarding wizard creates
the first organization + admin user.

Use the demo stack to try AccessFlow locally. For anything beyond evaluation, follow
the **production-style configuration** below — generate your own secrets, lock down
ports, and bring your own AI provider config.

### Quick start (demo)

```bash
git clone https://github.com/bablsoft/accessflow.git
cd accessflow
docker compose up -d            # pulls 4 images, starts everything
open http://localhost:5173      # SPA → /setup wizard creates first admin
```

Four containers come up: `accessflow-postgres`, `accessflow-redis`, `accessflow-backend`
(`ghcr.io/bablsoft/accessflow-backend:latest`, exposed on host port `8080`), and
`accessflow-frontend` (`ghcr.io/bablsoft/accessflow-frontend:latest`, host port `5173`).
No `.env` is required — the `JWT_PRIVATE_KEY` and `ENCRYPTION_KEY` are embedded with
demo defaults inside the compose file.

> ⚠️ **Demo only.** The embedded JWT private key and encryption key are committed
> in plaintext for the zero-config experience. They are not safe for any deployment
> that handles real data. Before pointing the demo at a real customer database or
> exposing it to other users, follow the production-style configuration below.

### Production-style configuration

Override the demo defaults with your own secrets and tighten the surface area. Drop
the file below at the repo root (or anywhere else — it is independent of the demo
compose) and use a real `.env`:

```yaml
services:
  postgres:
    image: postgres:18
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
    image: redis:8-alpine
    volumes:
      - redis_data:/data
    restart: unless-stopped

  backend:
    image: ghcr.io/bablsoft/accessflow-backend:latest
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
      REDIS_URL: redis://redis:6379
      CORS_ALLOWED_ORIGIN: https://accessflow.example.com
    ports:
      - '8080:8080'
    restart: unless-stopped

  frontend:
    image: ghcr.io/bablsoft/accessflow-frontend:latest
    # The frontend bundle is built with Vite — env vars are *not* read at container
    # runtime. To point the same image at a different backend, mount your own
    # runtime-config.js over the one shipped in the image:
    volumes:
      - ./runtime-config.js:/usr/share/nginx/html/runtime-config.js:ro
    ports:
      - '5173:80'
    depends_on:
      - backend
    restart: unless-stopped

  # Optional: self-hosted AI. Provider selection lives in the per-org `ai_config`
  # table (admin UI → AI configs), not in env vars. Run this if you want Ollama
  # available on the same Docker network, then configure an org's AI provider
  # against `http://ollama:11434`.
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

#### `.env` for the production-style stack

```env
DB_PASSWORD=change_me_strong_password
# 64 hex characters = 32 bytes. Generate with: openssl rand -hex 32
ENCRYPTION_KEY=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
# PKCS#8 RSA-2048. Generate with:
#   openssl genpkey -algorithm RSA -outform PEM -pkeyopt rsa_keygen_bits:2048
JWT_PRIVATE_KEY="-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----"
```

The `.env` file controls only what is *missing* from the compose file's inline values
— Docker Compose `${VAR:-default}` lookups fall back to the embedded demo defaults
when an env var is unset. To rotate keys, set them in `.env` (or your secret store)
and `docker compose up -d` will restart the backend with the new values.

#### With self-hosted Ollama

```bash
docker compose --profile ollama up -d
# Pull a model into the container:
docker exec -it accessflow-ollama-1 ollama pull llama3.2
# Then in the admin UI: AI configs → New → Provider: Ollama, Base URL: http://ollama:11434
```

---

## Kubernetes / Helm

### Install

```bash
helm repo add accessflow https://bablsoft.github.io/accessflow
helm repo update

# config.encryptionKey.existingSecret and config.jwtPrivateKey.existingSecret have no
# defaults — set them either via --set or in your my-values.yaml.
helm install accessflow accessflow/accessflow \
  --namespace accessflow \
  --create-namespace \
  --set config.encryptionKey.existingSecret=accessflow-encryption-key \
  --set config.jwtPrivateKey.existingSecret=accessflow-jwt-key \
  --values my-values.yaml
```

The chart lives in this repo at [`charts/accessflow/`](../charts/accessflow/) and is published to the
`gh-pages` branch of `bablsoft/accessflow` (helm repo URL above) on every tagged release.
Chart `version` and `appVersion` track the app version 1:1 — `--version X.Y.Z` always installs
the `X.Y.Z` container images.

### Full `values.yaml`

```yaml
replicaCount:
  backend: 3
  frontend: 2

image:
  backend:
    repository: ghcr.io/bablsoft/accessflow-backend
    tag: "1.0.0"
    pullPolicy: IfNotPresent
  frontend:
    repository: ghcr.io/bablsoft/accessflow-frontend
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

# Application config — Kubernetes Secret references (chart never creates them).
config:
  encryptionKey:
    existingSecret: accessflow-encryption-key
    key: value
  jwtPrivateKey:
    existingSecret: accessflow-jwt-key
    key: value
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

# External Redis (used when redis.enabled=false)
externalRedis:
  url: ""                            # e.g. redis://host:6379
  existingSecret: ""                 # optional — pull the URL from a Secret
  existingSecretUrlKey: REDIS_URL

# Frontend HPA (off by default)
autoscaling:
  frontend:
    enabled: false
    minReplicas: 2
    maxReplicas: 6
    targetCPUUtilizationPercentage: 70

# Custom JDBC driver cache persistence (matches ACCESSFLOW_DRIVER_CACHE)
driverCache:
  persistence:
    enabled: false
    size: 5Gi
    storageClass: ""
    accessMode: ReadWriteOnce
    mountPath: /var/lib/accessflow/drivers

# SAML 2.0 SSO (optional)
saml:
  enabled: false
  spEntityId: ""
  idpMetadataUrl: ""
  keystoreSecret: ""
```

### Chart development

The chart sources live alongside the application code:

```bash
helm dependency update charts/accessflow
helm lint charts/accessflow
helm template accessflow charts/accessflow              # default (bundled Postgres + Redis)
helm template accessflow charts/accessflow \
  --set postgresql.enabled=false --set redis.enabled=false \
  --set externalDatabase.host=db.example.com \
  --set externalDatabase.existingSecret=db-secret \
  --set externalRedis.url=redis://r.example.com:6379    # external services
```

The same lint + render pair runs in the `helm` job of [`.github/workflows/ci.yml`](../.github/workflows/ci.yml)
on every PR that touches `charts/**`, so chart regressions are caught before merge.

### Releasing the chart

[`.github/workflows/release.yml`](../.github/workflows/release.yml) repackages the chart on every
tagged release. It overwrites `Chart.yaml#version` and `appVersion` with the release semver,
runs `helm dependency update`, and uses
[`helm/chart-releaser-action`](https://github.com/helm/chart-releaser-action) to push the
packaged `.tgz` and the updated `index.yaml` to the `gh-pages` branch.

After the first release lands, enable GitHub Pages once in **Repo Settings → Pages**
(Source = "Deploy from a branch" → `gh-pages` / root). All subsequent releases just need a
tag — no further manual steps.

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
```

> AI provider keys are **not** chart inputs — they're stored per-organization in the
> `ai_config` table and managed from the admin UI. See
> [docs/05-backend.md → "AI Query Analyzer Service"](05-backend.md#ai-query-analyzer-service).

---

## Bootstrap configuration

AccessFlow can seed the **organization, first admin user, review plans, AI configs, datasources, SAML, OAuth2 providers, notification channels, and system SMTP** from environment variables at startup. This unlocks fully declarative GitOps deployments — `helm upgrade -f values.yaml` reconciles the database to match what's checked in.

**Authoritative semantics.** When `ACCESSFLOW_BOOTSTRAP_ENABLED=true`, the backend re-applies every declared row on every restart. Rows that match a declared spec are **overwritten**; rows not declared are untouched. Admin-UI edits to declared resources are reverted on the next restart — operators should treat the env-driven set as the source of truth.

### When to use it

| Install path | Use bootstrap? |
|---|---|
| Helm / Kubernetes deployment with secrets in `Secret` objects | **Yes** — primary intended use case |
| Docker Compose dev environment | Optional — `ACCESSFLOW_BOOTSTRAP_*` env vars in the compose file work the same way |
| Bare-metal / VM install with admin doing one-off setup via the wizard | **No** — leave `ACCESSFLOW_BOOTSTRAP_ENABLED=false` (the default) |

### How env vars map to properties

The backend exposes a single `@ConfigurationProperties("accessflow.bootstrap")` record. Spring Boot's relaxed binding maps each property path to an upper-snake env-var name. Lists use `_<INDEX>_`:

```
accessflow.bootstrap.enabled                              → ACCESSFLOW_BOOTSTRAP_ENABLED
accessflow.bootstrap.organization.name                    → ACCESSFLOW_BOOTSTRAP_ORGANIZATION_NAME
accessflow.bootstrap.admin.display-name                   → ACCESSFLOW_BOOTSTRAP_ADMIN_DISPLAY_NAME
accessflow.bootstrap.review-plans[0].name                 → ACCESSFLOW_BOOTSTRAP_REVIEW_PLANS_0_NAME
accessflow.bootstrap.review-plans[0].approver-emails[1]   → ACCESSFLOW_BOOTSTRAP_REVIEW_PLANS_0_APPROVER_EMAILS_1
accessflow.bootstrap.datasources[2].password              → ACCESSFLOW_BOOTSTRAP_DATASOURCES_2_PASSWORD
```

The canonical property tree lives in [BootstrapProperties.java](../backend/src/main/java/com/bablsoft/accessflow/bootstrap/internal/BootstrapProperties.java) and its `spec/` sub-records.

### Reconcile order

`BootstrapRunner` runs once on `ApplicationReadyEvent` in this fixed order:

```
organization → admin user → notification channels → AI configs →
review plans (resolve approvers + channels) →
datasources (resolve review plan + AI config) →
SAML → OAuth2 → system SMTP
```

If the organization reconciler fails, bootstrap aborts immediately. For every subsequent step, failures are logged at ERROR and collected; at the end the runner throws `BootstrapException` so the pod fails readiness — the operator sees the message in `kubectl describe pod` / `kubectl logs`.

### Secret hygiene

Sensitive env vars **must** come from Kubernetes `Secret` objects, never from `ConfigMap` or inline `values.yaml`. The chart enforces this via the structured `*SecretRef` shape — see the Helm walkthrough below. The complete list of fields the chart routes through Secrets:

| Spec path | Env var | Kind |
|---|---|---|
| `bootstrap.admin.passwordSecretRef` | `ACCESSFLOW_BOOTSTRAP_ADMIN_PASSWORD` | BCrypt-hashed at first start; never rotated |
| `bootstrap.datasources[N].passwordSecretRef` | `ACCESSFLOW_BOOTSTRAP_DATASOURCES_<N>_PASSWORD` | Encrypted (AES-256-GCM) before persist |
| `bootstrap.aiConfigs[N].apiKeySecretRef` | `ACCESSFLOW_BOOTSTRAP_AI_CONFIGS_<N>_API_KEY` | Encrypted before persist |
| `bootstrap.notificationChannels[N].sensitiveSecretRefs.<field>` | `ACCESSFLOW_BOOTSTRAP_NOTIFICATION_CHANNELS_<N>_CONFIG_<FIELD>` | Encrypted before persist |
| `bootstrap.oauth2[N].clientSecretRef` | `ACCESSFLOW_BOOTSTRAP_OAUTH2_<N>_CLIENT_SECRET` | Encrypted before persist |
| `bootstrap.systemSmtp.passwordSecretRef` | `ACCESSFLOW_BOOTSTRAP_SYSTEM_SMTP_PASSWORD` | Encrypted before persist |

### Helm walkthrough

#### 1. Pre-create the Secrets

`accessflow-bootstrap-secrets` holds the admin password and any shared secrets (AI keys, Slack webhooks, OAuth2 client secrets, SMTP password):

```bash
kubectl create secret generic accessflow-bootstrap-secrets \
  --from-literal=admin-password="$(openssl rand -base64 24)" \
  --from-literal=anthropic-key="sk-…" \
  --from-literal=slack-webhook="https://hooks.slack.com/services/…" \
  --from-literal=google-client-secret="…" \
  --from-literal=smtp-password="…"
```

Per-datasource credentials live in their own Secrets:

```bash
kubectl create secret generic prod-pg-creds \
  --from-literal=password="…"
```

#### 2. Populate `values.yaml`

```yaml
bootstrap:
  enabled: true
  organization:
    name: Acme
  admin:
    email: admin@acme.com
    displayName: Initial Admin
    passwordSecretRef: { name: accessflow-bootstrap-secrets, key: admin-password }
  reviewPlans:
    - name: standard
      requiresAiReview: true
      requiresHumanApproval: true
      minApprovalsRequired: 1
      approvalTimeoutHours: 24
      autoApproveReads: false
      notifyChannelNames: [ops-slack]
      approverEmails: [admin@acme.com]
  aiConfigs:
    - name: claude
      provider: ANTHROPIC
      model: claude-sonnet-4-20250514
      apiKeySecretRef: { name: accessflow-bootstrap-secrets, key: anthropic-key }
  datasources:
    - name: prod-postgres
      dbType: POSTGRESQL
      host: postgres.prod.svc
      port: 5432
      databaseName: app
      username: af_reader
      passwordSecretRef: { name: prod-pg-creds, key: password }
      sslMode: REQUIRE
      reviewPlanName: standard
      aiAnalysisEnabled: true
      aiConfigName: claude
  notificationChannels:
    - name: ops-slack
      channelType: SLACK
      active: true
      config:
        channel: "#ops"
      sensitiveSecretRefs:
        webhookUrl: { name: accessflow-bootstrap-secrets, key: slack-webhook }
  oauth2:
    - provider: GOOGLE
      clientId: "1234.apps.googleusercontent.com"
      clientSecretRef: { name: accessflow-bootstrap-secrets, key: google-client-secret }
      defaultRole: REVIEWER
      active: true
  systemSmtp:
    enabled: true
    host: smtp.acme.com
    port: 587
    username: noreply
    passwordSecretRef: { name: accessflow-bootstrap-secrets, key: smtp-password }
    tls: true
    fromAddress: noreply@acme.com
    fromName: AccessFlow
```

A complete fixture that exercises every render path lives at [charts/accessflow/ci/bootstrap-values.yaml](../charts/accessflow/ci/bootstrap-values.yaml) and is wired into the `helm` job of [`.github/workflows/ci.yml`](../.github/workflows/ci.yml).

#### 3. `helm upgrade`

```bash
helm upgrade --install accessflow charts/accessflow -f values.yaml
```

`helm template` validates the inputs eagerly — if a required field is missing or a `*SecretRef` is incomplete, the install fails with a clear message (e.g. `bootstrap.enabled=true requires bootstrap.admin.passwordSecretRef.name`). The chart never templates a `Secret` object — pre-create them with `kubectl` per the snippets above.

#### 4. Verify

```bash
# Confirm the ConfigMap contains the non-secret bootstrap keys
kubectl get configmap accessflow-backend-env -o yaml | grep ACCESSFLOW_BOOTSTRAP

# Confirm the Deployment has the secret-ref env vars
kubectl describe pod -l app.kubernetes.io/name=accessflow,app.kubernetes.io/component=backend \
  | grep -A2 ACCESSFLOW_BOOTSTRAP

# Tail the backend log on first start; look for "Bootstrap: reconciliation completed"
kubectl logs -l app.kubernetes.io/component=backend --tail=200 | grep Bootstrap
```

### Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `helm install` fails with `bootstrap.enabled=true requires bootstrap.organization.name` | Required field empty in `values.yaml` | Set the field; required keys are listed in the validation block in [_bootstrap-env.tpl](../charts/accessflow/templates/_bootstrap-env.tpl) |
| Pod CrashLoopBackOff after `helm upgrade`, log shows `BootstrapException: datasources: …references unknown review plan 'X'` | Spec references a review plan that isn't in the same `bootstrap.reviewPlans` list | Add the review plan above the datasource list, or reference an existing plan |
| Datasource entry has `dbType: CUSTOM` and helm fails with `upload CUSTOM JDBC drivers via the admin API` | CUSTOM datasources are not supported via bootstrap | Upload the JAR via `POST /api/v1/admin/jdbc-drivers` and create the datasource from the admin UI |
| Admin user's password from `accessflow-bootstrap-secrets` no longer logs in | Bootstrap creates the admin once and never rotates the password on subsequent restarts | Use the admin API to rotate the password, then update the K8s Secret to keep them in sync |

### Out of scope (for now)

- **CUSTOM JDBC driver upload** — driver JARs must be uploaded through the admin API.
- **Deletion of un-declared rows** — bootstrap never deletes; remove rows through the admin API.
- **Audit-log entries for bootstrap writes** — bootstrap is a system actor; entries are silent today. Tracked in [#196](https://github.com/bablsoft/accessflow/issues/196).

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
| `ACCESSFLOW_SAML_FRONTEND_CALLBACK_URL` | Optional | `${CORS_ALLOWED_ORIGIN}/auth/saml/callback` | Where the SAML success / failure handler redirects after the IdP roundtrip. The frontend `SamlCallbackPage` parses `?code=` or `?error=` from the query string. |
| `ACCESSFLOW_SAML_EXCHANGE_CODE_TTL` | Optional | `PT1M` | ISO-8601 duration — TTL of the one-time SAML exchange code in Redis (separate `saml:exchange:` namespace from OAuth2). |
| `ACCESSFLOW_SAML_SP_SIGNING_KEY_PEM` | Optional | — | PEM-encoded RSA private key for the SP. When set together with `ACCESSFLOW_SAML_SP_SIGNING_CERT_PEM`, takes precedence over the auto-generated keypair persisted in `saml_config`. The operator owns rotation. |
| `ACCESSFLOW_SAML_SP_SIGNING_CERT_PEM` | Optional | — | PEM-encoded SP X.509 certificate (paired with `ACCESSFLOW_SAML_SP_SIGNING_KEY_PEM`). When unset, AccessFlow auto-generates a self-signed RSA-2048 keypair on first SAML flow and persists the encrypted private key + cleartext cert into `saml_config` so it survives restarts. |

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
  image: ghcr.io/bablsoft/accessflow-frontend:latest
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

## Releases

Releases are published to **GitHub Container Registry** at
[`ghcr.io/bablsoft/accessflow-backend`](https://github.com/bablsoft/accessflow/pkgs/container/accessflow-backend)
and
[`ghcr.io/bablsoft/accessflow-frontend`](https://github.com/bablsoft/accessflow/pkgs/container/accessflow-frontend),
tagged with the semver release version (e.g. `1.2.3`) and `latest`. Both images are
multi-arch (`linux/amd64`, `linux/arm64`).

### Cutting a release

Maintainers run the **Release** workflow from the Actions tab
(`.github/workflows/release.yml`) and supply a `version` input (without the leading
`v`, e.g. `1.2.3`). The workflow:

1. Bumps `backend/pom.xml` (`mvn versions:set`) and `frontend/package.json`
   (`npm version`).
2. Creates a detached commit `chore(release): vX.Y.Z`, tags it as `vX.Y.Z`, and
   pushes only the tag — `main` is never modified, so `main` always reflects
   `1.0.0-SNAPSHOT` for the next development cycle.
3. Builds and pushes the two Docker images under both `:X.Y.Z` and `:latest`.
4. Publishes a **GitHub Release** with auto-generated changelog notes (PRs and
   commits between the previous tag and this one).

### Version surfacing

The released version is observable end-to-end:

| Source | Where to read it |
|--------|------------------|
| Backend | `GET /actuator/info` → `build.version` (populated by Spring Boot's `build-info` Maven goal, exposed unauthenticated alongside `/actuator/health`). |
| Frontend | Sidebar brand mark (`v1.2.3` under the AccessFlow name). Injected by Vite via `__APP_VERSION__` from the `APP_VERSION` build-arg. |
| Git tag | `git ls-remote --tags origin` → `refs/tags/vX.Y.Z`. Checking out the tag shows pom.xml and package.json at the bumped version. |

The three are guaranteed to agree because the release workflow uses the same
single `version` input as build-arg, version-bump target, and tag name.

---

## Upgrading

1. Pull the new image tag.
2. Flyway runs automatically on startup and applies any new migration scripts.
3. For Kubernetes: `helm upgrade accessflow accessflow/accessflow --values my-values.yaml`
4. Rolling update strategy ensures zero downtime (multiple replicas + PDB).

> **Never** run `spring.jpa.hibernate.ddl-auto=create-drop` in production. Flyway is the sole schema manager.
