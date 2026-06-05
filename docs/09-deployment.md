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
      # Provisions the accessflow_audit role used by V38 (audit_log role separation).
      - ./deploy/postgres-init:/docker-entrypoint-initdb.d:ro
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
      # Dedicated audit-writer role — see "audit_log role separation" below.
      AUDIT_DB_USER: accessflow_audit
      AUDIT_DB_PASSWORD: ${AUDIT_DB_PASSWORD}
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
# Password for the dedicated audit-writer role provisioned by
# deploy/postgres-init/01-audit-role.sql (see "audit_log role separation" below).
AUDIT_DB_PASSWORD=change_me_audit_password
# 64 hex characters = 32 bytes. Generate with: openssl rand -hex 32
ENCRYPTION_KEY=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
# RSA-2048 PEM. Both PKCS#8 (`-----BEGIN PRIVATE KEY-----`) and the legacy
# PKCS#1 (`-----BEGIN RSA PRIVATE KEY-----`) form are accepted. Generate with:
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

helm install accessflow accessflow/accessflow \
  --namespace accessflow \
  --create-namespace
```

With the defaults the chart needs **no pre-created Secrets**:

- The AES-256-GCM encryption key and the JWT RS256 private key are generated on
  first install and stored in a chart-managed Secret `{release}-accessflow-secrets`.
- The PostgreSQL password (and the bundled-Postgres admin password) are generated
  on first install and stored in a chart-managed Secret `{release}-accessflow-db`
  (keys `password` + `postgres-password`). The bitnami subchart points at this
  Secret via its `auth.existingSecret` setting instead of generating its own.

Both Secrets carry `helm.sh/resource-policy: keep` and the values are preserved
across upgrades via Helm's `lookup` function. That's particularly important for
the database password: the postgresql PVC also survives `helm uninstall`, so a
fresh random password generated against an existing data dir would otherwise
produce `FATAL: password authentication failed for user "accessflow"`
(historically [#228](https://github.com/bablsoft/accessflow/issues/228)). On
upgrade from the pre-AF-228 chart layout (where bitnami managed its own
`{release}-postgresql` Secret), the new Secret's `lookup` falls back to that
legacy Secret so the password stays in sync with the data dir during the cut-
over.

For production deployments you'll typically want to supply your own
`my-values.yaml` to set the ingress host, CORS origin, TLS, and (optionally)
externally-managed Secrets:

```bash
helm install accessflow accessflow/accessflow \
  --namespace accessflow \
  --create-namespace \
  --values my-values.yaml
```

The chart lives in this repo at [`charts/accessflow/`](../charts/accessflow/) and is published to the
`gh-pages` branch of `bablsoft/accessflow` (helm repo URL above) on every tagged release.
Chart `version` and `appVersion` track the app version 1:1 — `--version X.Y.Z` always installs
the `X.Y.Z` container images.

### Example values files

Self-contained starting points live under
[`charts/accessflow/examples/`](../charts/accessflow/examples/) — see the
[index README](../charts/accessflow/examples/README.md) for the full list.
They split into **deployment shapes** (cluster-level: replicas, ingress,
secrets model) and **bootstrap slices** (declarative admin config). The
intended pattern is one of each, plus your own site-specific overrides:

```bash
helm install accessflow accessflow/accessflow \
  --namespace accessflow --create-namespace \
  -f charts/accessflow/examples/values-production.yaml \
  -f charts/accessflow/examples/values-bootstrap-oauth2-sso.yaml \
  -f my-site-overrides.yaml
```

**Deployment shapes:**

| File | Scenario |
|---|---|
| [`values-minimal.yaml`](../charts/accessflow/examples/values-minimal.yaml) | Single-replica demo over plain HTTP. |
| [`values-production.yaml`](../charts/accessflow/examples/values-production.yaml) | HA backend (HPA + PDB + pod anti-affinity), cert-manager-issued TLS, persistent driver cache. |
| [`values-external-services.yaml`](../charts/accessflow/examples/values-external-services.yaml) | Managed Postgres + Redis (RDS / ElastiCache / …), every secret managed outside the chart. |
| [`values-airgapped.yaml`](../charts/accessflow/examples/values-airgapped.yaml) | Air-gapped: internal registry mirror, offline JDBC drivers, manual TLS Secret. |

**Bootstrap slices** (each declares organization + first admin and layers on
top of a deployment shape — see [Bootstrap configuration](#bootstrap-configuration)
for the semantics):

| File | Adds |
|---|---|
| [`values-bootstrap-minimal.yaml`](../charts/accessflow/examples/values-bootstrap-minimal.yaml) | Just organization + first admin user. Skip the first-run signup screen. |
| [`values-bootstrap-oauth2-sso.yaml`](../charts/accessflow/examples/values-bootstrap-oauth2-sso.yaml) | OAuth2 providers (Google, Microsoft Entra ID, GitHub). |
| [`values-bootstrap-saml-sso.yaml`](../charts/accessflow/examples/values-bootstrap-saml-sso.yaml) | SAML 2.0 SP wired to a corporate IdP. |
| [`values-bootstrap-datasources.yaml`](../charts/accessflow/examples/values-bootstrap-datasources.yaml) | AI provider + tiered review plans + multi-dialect datasources (Postgres, MySQL, MSSQL). |
| [`values-bootstrap-notifications.yaml`](../charts/accessflow/examples/values-bootstrap-notifications.yaml) | System SMTP relay + Slack / email / webhook channels + a fan-out review plan. |
| [`values-bootstrap.yaml`](../charts/accessflow/examples/values-bootstrap.yaml) | Kitchen-sink reference covering every `bootstrap.*` field at once. |

Each example is a **minimal override on top of the chart's `values.yaml`** —
not a full dump — so you can read it end-to-end and see exactly what's
being changed. Anything not listed inherits the chart default. The example
files are sourced from GitHub; they're excluded from the packaged chart via
`.helmignore` so the published `.tgz` stays lean.

### Full `values.yaml`

```yaml
# Replicas. Honored when `autoscaling.*.enabled=false` (the default); when an
# HPA is enabled, `autoscaling.*.minReplicas` becomes the effective floor.
replicaCount:
  backend: 2
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

# Internal PostgreSQL (set enabled: false to use external).
# `auth.existingSecret` defaults to a Helm template string `'{{ .Release.Name }}-accessflow-db'`
# (bitnami runs it through `tpl`), which resolves to a chart-managed Secret
# preserved across uninstall via `helm.sh/resource-policy: keep`. To manage
# the password externally, override `existingSecret` with the name of your
# own Secret (must expose keys `password` and `postgres-password`).
postgresql:
  enabled: true
  auth:
    database: accessflow
    username: accessflow
    existingSecret: '{{ .Release.Name }}-accessflow-db'
  primary:
    persistence:
      size: 20Gi

# Internal Redis
redis:
  enabled: true
  architecture: standalone

# Application config.
#
# When `existingSecret` is empty, the chart auto-generates the value into a
# chart-managed Secret `{release}-accessflow-secrets` and preserves it across
# upgrades. Set `existingSecret` to override with a Secret you manage yourself.
config:
  encryptionKey:
    existingSecret: ""
    key: value
  jwtPrivateKey:
    existingSecret: ""
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

# Ingress. TLS is off by default — set ingress.tls.enabled=true and provide
# a secretName (cert-manager-issued or pre-loaded with kubectl) to terminate
# HTTPS at the Ingress.
ingress:
  enabled: true
  className: nginx
  annotations: {}
  # # To have cert-manager auto-provision the TLS cert, uncomment:
  # annotations:
  #   cert-manager.io/cluster-issuer: letsencrypt-prod
  hosts:
    # `paths` is optional. Omit it (e.g. `hosts: [{ host: my-host }]`) to
    # inherit the standard 3-path routing (`/api` + `/ws` → backend, `/` →
    # frontend) wired into the chart.
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
    enabled: false
    secretName: accessflow-tls

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

# Horizontal Pod Autoscaler.
# Off by default — `replicaCount.backend` stays the single source of truth on
# first install. Flip to `enabled: true` for production, at which point the
# HPA's `minReplicas` floor takes precedence over `replicaCount.backend`.
autoscaling:
  backend:
    enabled: false
    minReplicas: 2
    maxReplicas: 10
    targetCPUUtilizationPercentage: 70

# Pod disruption budget (ensure HA during rolling updates).
# Off by default — enabling on a single-replica deployment blocks voluntary
# evictions (node drains, cluster upgrades) forever. Enable for production
# deployments running ≥ 2 replicas.
podDisruptionBudget:
  backend:
    enabled: false
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
helm template accessflow charts/accessflow              # default (bundled Postgres + Redis, auto-generated secrets)
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

The chart auto-generates the encryption key, JWT private key, and PostgreSQL
password on first install — you only need to create Secrets when you want to
manage them externally (sealed-secrets, External Secrets, Vault, …). Each
external Secret then takes precedence over the chart-managed default:

```bash
# PostgreSQL password — the Secret MUST expose BOTH keys: `password` (the
# AccessFlow custom-user password) and `postgres-password` (the admin password).
kubectl -n accessflow create secret generic accessflow-pg-secret \
  --from-literal=password="$(openssl rand -base64 24)" \
  --from-literal=postgres-password="$(openssl rand -base64 24)"

# Encryption key — 64 hex chars = 32 bytes. The default `key` is "value".
kubectl -n accessflow create secret generic accessflow-encryption-key \
  --from-literal=value="$(openssl rand -hex 32)"

# JWT RSA private key (PEM). The default `key` is "value".
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt_private_key.pem
kubectl -n accessflow create secret generic accessflow-jwt-key \
  --from-file=value=./jwt_private_key.pem
```

Then wire them into `values.yaml`:

```yaml
postgresql:
  auth:
    existingSecret: accessflow-pg-secret

config:
  encryptionKey:
    existingSecret: accessflow-encryption-key
    key: value
  jwtPrivateKey:
    existingSecret: accessflow-jwt-key
    key: value
```

> The chart-managed Secret `{release}-accessflow-secrets` is annotated with
> `helm.sh/resource-policy: keep` so it survives `helm uninstall`. **Do not delete
> it while encrypted datasource credentials exist in the database** — they will
> become unreadable.

> AI provider keys are **not** chart inputs — they're stored per-organization in the
> `ai_config` table and managed from the admin UI. See
> [docs/05-backend.md → "AI Query Analyzer Service"](05-backend.md#ai-query-analyzer-service).

---

## Bootstrap configuration

AccessFlow can seed the **organization, first admin user, review plans, AI configs, datasources, SAML, OAuth2 providers, notification channels, and system SMTP** from environment variables at startup. This unlocks fully declarative GitOps deployments — `helm upgrade -f values.yaml` reconciles the database to match what's checked in.

**Authoritative semantics.** When `ACCESSFLOW_BOOTSTRAP_ENABLED=true`, the backend re-applies every declared row on every restart. Rows that match a declared spec are **overwritten**; rows not declared are untouched. Admin-UI edits to declared resources are reverted on the next restart — operators should treat the env-driven set as the source of truth.

**Auditability.** Every bootstrap upsert is audited. The backend caches a SHA-256 fingerprint of each declared spec in `bootstrap_state` and, on subsequent restarts, short-circuits resources whose spec has not changed — so restarting with unchanged env vars writes **zero** new `audit_log` rows. When a spec genuinely differs from the persisted state (or runs for the first time), the corresponding row appears in `audit_log` with `actor_id = NULL` and `metadata.source = "BOOTSTRAP"`, participating in the same per-org HMAC chain as user-driven audits. See [docs/05-backend.md → "Bootstrap audit semantics"](05-backend.md#bootstrap-audit-semantics).

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
SAML → OAuth2 → Langfuse → system SMTP
```

If the organization reconciler fails, bootstrap aborts immediately. For every subsequent step, failures are logged at ERROR and collected; at the end the runner throws `BootstrapException` so the pod fails readiness — the operator sees the message in `kubectl describe pod` / `kubectl logs`.

### Multi-replica safety

In a Helm release with `replicaCount.backend > 1` (the chart defaults to `2`; `values-production.yaml` ships `3`), every pod fires `ApplicationReadyEvent` concurrently. Bootstrap wraps the reconcile body in a Redis-backed distributed lock named `bootstrapReconcile` (`lockAtMostFor=10m`), so exactly one pod per startup wave performs the upserts. The other pods log `Bootstrap: another node holds the 'bootstrapReconcile' lock; skipping reconciliation on this replica` at INFO and continue serving traffic — they do not fail readiness.

The lock reuses the same Redis instance that powers ShedLock (`@SchedulerLock`) and the JWT refresh-token store; lock keys live under the existing `accessflow:shedlock:` prefix. **No new env vars** — if `REDIS_URL` is set, the lock works. If Redis is unreachable at startup, the lock acquisition throws and the pod fails readiness, the same loud-failure model as `BootstrapException`.

Operational notes:
- If the winning replica crashes mid-reconcile, the Redis key expires after `lockAtMostFor` and the next pod to restart re-runs every reconciler from scratch (all upserts are idempotent — admin user lookup-by-email, datasource lookup-by-name, etc.).
- The lock is held only for the duration of the reconcile call. Once it returns, the key is released immediately so a pod that starts a few minutes later can re-bootstrap if needed.
- Operators with multiple Helm releases pointing at the same Redis instance should keep the chart-level `redis.fullnameOverride` per-release or use distinct Redis databases — the lock prefix is shared across releases that share Redis.

### Secret hygiene

Sensitive env vars **must** come from Kubernetes `Secret` objects, never from `ConfigMap` or inline `values.yaml`. The chart enforces this via the structured `*SecretRef` shape — see the Helm walkthrough below. The complete list of fields the chart routes through Secrets:

| Spec path | Env var | Kind |
|---|---|---|
| `bootstrap.admin.passwordSecretRef` | `ACCESSFLOW_BOOTSTRAP_ADMIN_PASSWORD` | BCrypt-hashed at first start; never rotated |
| `bootstrap.datasources[N].passwordSecretRef` | `ACCESSFLOW_BOOTSTRAP_DATASOURCES_<N>_PASSWORD` | Encrypted (AES-256-GCM) before persist |
| `bootstrap.aiConfigs[N].apiKeySecretRef` | `ACCESSFLOW_BOOTSTRAP_AI_CONFIGS_<N>_API_KEY` | Encrypted before persist |
| `bootstrap.notificationChannels[N].sensitiveSecretRefs.<field>` | `ACCESSFLOW_BOOTSTRAP_NOTIFICATION_CHANNELS_<N>_CONFIG_<FIELD>` | Encrypted before persist |
| `bootstrap.oauth2[N].clientSecretRef` | `ACCESSFLOW_BOOTSTRAP_OAUTH2_<N>_CLIENT_SECRET` | Encrypted before persist |
| `bootstrap.langfuse.secretKeySecretRef` | `ACCESSFLOW_BOOTSTRAP_LANGFUSE_SECRET_KEY` | Encrypted before persist |
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
      textToSqlEnabled: false   # optional; when true users can draft SQL from natural language (reuses aiConfigName)
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
      # Optional: restrict sign-in to specific email domains (Google Workspace lock-down).
      allowedEmailDomains:
        - acme.com
      defaultRole: REVIEWER
      active: true
    - provider: GITHUB
      clientId: "Iv1.abc123"
      clientSecretRef: { name: accessflow-bootstrap-secrets, key: github-client-secret }
      # GitHub allowed-organizations require the `read:org` scope.
      scopesOverride: "read:user user:email read:org"
      allowedOrganizations:
        - bablsoft
      defaultRole: ANALYST
      active: true
    - provider: GITHUB_ENTERPRISE
      # Self-hosted GitHub Enterprise Server. URLs are not OIDC; only the origin
      # is operator-editable — AccessFlow appends /login/oauth/authorize,
      # /login/oauth/access_token, and /api/v3/* automatically. Must be https.
      baseUrl: https://github.acme.corp
      clientId: "Iv1.ghes123"
      clientSecretRef: { name: accessflow-bootstrap-secrets, key: ghes-client-secret }
      scopesOverride: "read:user user:email read:org"
      allowedOrganizations:
        - platform-team
      defaultRole: ANALYST
      active: true
    - provider: GITLAB_ENTERPRISE
      # Self-managed GitLab. OIDC-compliant — AccessFlow appends /oauth/authorize,
      # /oauth/token, /oauth/userinfo, and /oauth/discovery/keys automatically.
      # Must be https.
      baseUrl: https://gitlab.acme.corp
      clientId: "gl-app-id"
      clientSecretRef: { name: accessflow-bootstrap-secrets, key: gle-client-secret }
      allowedOrganizations:
        - acme/team
      defaultRole: ANALYST
      active: true
    - provider: OIDC
      # Generic OAuth 2.0 / OIDC — works with Keycloak, Auth0, Okta, Authentik,
      # Zitadel, etc. The displayName is rendered as "Continue with {displayName}"
      # on the login page. Most IdPs publish the five URLs below at
      # /.well-known/openid-configuration.
      displayName: Keycloak
      clientId: accessflow
      clientSecretRef: { name: accessflow-bootstrap-secrets, key: oidc-client-secret }
      authorizationUri: https://keycloak.example.com/realms/acme/protocol/openid-connect/auth
      tokenUri: https://keycloak.example.com/realms/acme/protocol/openid-connect/token
      userInfoUri: https://keycloak.example.com/realms/acme/protocol/openid-connect/userinfo
      jwkSetUri: https://keycloak.example.com/realms/acme/protocol/openid-connect/certs
      issuerUri: https://keycloak.example.com/realms/acme
      # Optional. Override these only if your IdP uses non-standard claim names.
      # userNameAttribute: sub
      # emailAttribute: email
      # emailVerifiedAttribute: email_verified
      # displayNameAttribute: name
      # Optional. Required if you want to restrict sign-in via allowedOrganizations.
      # groupsAttribute: groups
      scopesOverride: "openid email profile"
      defaultRole: ANALYST
      active: true
  langfuse:
    # Langfuse tracing + prompt management. Per-org singleton. host blank ⇒
    # ACCESSFLOW_LANGFUSE_DEFAULT_HOST (https://cloud.langfuse.com).
    enabled: true
    host: https://cloud.langfuse.com
    publicKey: pk-lf-...
    secretKeyRef: { name: accessflow-bootstrap-secrets, key: langfuse-secret-key }
    tracingEnabled: true
    promptManagementEnabled: false
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

> **Self-hosted CAs (GitHub Enterprise / GitLab self-managed).** AccessFlow's `WebClient` /
> `RestClient` calls the IdP's userinfo, JWK, emails, and orgs endpoints. If your
> Enterprise instance terminates TLS with a certificate issued by an internal CA, mount the
> CA into the backend pod's JVM truststore (set `JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=…
> -Djavax.net.ssl.trustStorePassword=…` and project the trust store via a Kubernetes
> Secret/ConfigMap). Without this, Spring AI's HTTP client rejects the TLS handshake and the
> OAuth login fails with `OAUTH2_LOGIN_FAILED`.

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
| `DB_USER` | ✓ | `accessflow` | PostgreSQL username for the general application connection pool. UPDATE/DELETE/TRUNCATE on `audit_log` are revoked by `V38__audit_log_role_separation.sql`; SELECT is retained for the admin read endpoint. |
| `DB_PASSWORD` | ✓ | `accessflow` | Password for `DB_USER`. |
| `AUDIT_DB_USER` | Optional | `accessflow_audit` | PostgreSQL username for the dedicated audit-writer role used by the audit module's `auditDataSource` bean to INSERT into `audit_log`. The role must exist before Flyway runs — see ["audit_log role separation"](#audit_log-role-separation) below. |
| `AUDIT_DB_PASSWORD` | Optional | `accessflow_audit` | Password for `AUDIT_DB_USER`. |
| `REDIS_URL` | Optional | `redis://localhost:6379` | Redis URL for token revocation, async events, and ShedLock scheduler locks |

#### Security & Auth

| Variable | Required | Default | Description |
|----------|---------|---------|-------------|
| `ENCRYPTION_KEY` | ✓ | — | 32-byte hex AES-256-GCM key for datasource credential encryption |
| `JWT_PRIVATE_KEY` | ✓ | — | RSA-2048 PEM private key for JWT RS256 signing. Both PKCS#8 (`-----BEGIN PRIVATE KEY-----`) and the legacy PKCS#1 (`-----BEGIN RSA PRIVATE KEY-----`) form are accepted. |
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

#### Langfuse Integration

Per-organization Langfuse credentials and toggles live in the `langfuse_config` table (managed from `/admin/langfuse`), not in env. These variables only set deployment-wide defaults for the outbound Langfuse client.

| Variable | Required | Default | Description |
|----------|---------|---------|-------------|
| `ACCESSFLOW_LANGFUSE_DEFAULT_HOST` | Optional | `https://cloud.langfuse.com` | Default Langfuse host pre-filled when a per-org config omits its own host. Point at a self-hosted Langfuse for on-prem. |
| `ACCESSFLOW_LANGFUSE_PROMPT_CACHE_TTL` | Optional | `PT60S` | ISO-8601 duration a Langfuse-managed analyzer prompt is cached before re-fetch. |
| `ACCESSFLOW_LANGFUSE_CONNECT_TIMEOUT` | Optional | `PT5S` | Connect timeout for the Langfuse ingestion / prompt API client. |
| `ACCESSFLOW_LANGFUSE_REQUEST_TIMEOUT` | Optional | `PT10S` | Read timeout for the Langfuse ingestion / prompt API client. |

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
| `ACCESSFLOW_PROXY_HEALTH_CACHE_TTL` | Optional | `PT30S` | Caffeine TTL for the admin datasource-health snapshot, cached per `(organizationId, datasourceId)` so the dashboard's 30s auto-refresh doesn't re-run the aggregate every poll |

> **Read-replica routing** (added in v1.2 — see [docs/05-backend.md → "Read-replica routing"](05-backend.md#read-replica-routing)) reuses the same `ACCESSFLOW_PROXY_*` HikariCP tunables above; there are no replica-specific env vars. Configure replicas per-datasource via the settings UI or `PUT /api/v1/datasources/{id}` with `read_replica_jdbc_url`/`read_replica_username`/`read_replica_password`.

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
| `ACCESSFLOW_WORKFLOW_SCHEDULED_RUN_POLL_INTERVAL` | Optional | `PT1M` | ISO-8601 duration. Cadence at which `ScheduledQueryRunJob` scans for `APPROVED` queries whose `scheduled_for` timestamp has been reached and triggers their execution via the workflow's lifecycle service. ShedLock makes this safe under horizontal scaling. |
| `ACCESSFLOW_ACCESS_GRANT_EXPIRY_POLL_INTERVAL` | Optional | `PT5M` | ISO-8601 duration. Cadence at which `AccessGrantExpiryJob` (the `access` module) scans for `APPROVED` JIT access grants past their `expires_at` and revokes the materialised permission. ShedLock makes this safe under horizontal scaling. |
| `ACCESSFLOW_ACCESS_MIN_DURATION` | Optional | `PT15M` | ISO-8601 duration. Smallest requestable JIT access-grant duration; enforced server-side on submit. |
| `ACCESSFLOW_ACCESS_MAX_DURATION` | Optional | `P30D` | ISO-8601 duration. Largest requestable JIT access-grant duration. |
| `ACCESSFLOW_PUBLIC_BASE_URL` | Optional | `http://localhost:5173` | Public base URL used in notification email links and webhook payloads |
| `ACCESSFLOW_NOTIFICATIONS_RETRY_FIRST` | Optional | `PT30S` | ISO-8601 duration — delay before the first webhook retry |
| `ACCESSFLOW_NOTIFICATIONS_RETRY_SECOND` | Optional | `PT2M` | ISO-8601 duration — delay before the second webhook retry |
| `ACCESSFLOW_NOTIFICATIONS_RETRY_THIRD` | Optional | `PT10M` | ISO-8601 duration — delay before the third (final) webhook retry |
| `ACCESSFLOW_NOTIFICATIONS_TELEGRAM_API_BASE_URL` | Optional | `https://api.telegram.org/` | Telegram Bot API base URL used by `TELEGRAM` notification channels. Override for air-gapped installs that route through an internal proxy. Trailing slash is added automatically when missing. |
| `ACCESSFLOW_NOTIFICATIONS_PAGERDUTY_API_BASE_URL` | Optional | `https://events.pagerduty.com/` | PagerDuty Events API v2 base URL used by `PAGERDUTY` notification channels. Override for air-gapped installs that route through an internal proxy. Trailing slash is added automatically when missing. |
| `ACCESSFLOW_NOTIFICATIONS_SLACK_LINK_CODE_TTL` | Optional | `PT10M` | ISO-8601 duration — TTL of the one-time Slack account-link code generated for `/accessflow link <code>` (stored single-use in Redis). |
| `ACCESSFLOW_NOTIFICATIONS_SLACK_SIGNATURE_TOLERANCE` | Optional | `PT5M` | ISO-8601 duration — acceptance window for the inbound Slack `X-Slack-Request-Timestamp` HMAC signature; also the replay-dedup window. Stale or replayed Slack callbacks outside this window are rejected with `401`. |
| `ACCESSFLOW_SECURITY_INVITATION_TTL` | Optional | `P7D` | ISO-8601 duration. TTL of user-invitation tokens issued by `POST /admin/users/invitations`. Pending invitations past this duration are treated as expired on preview/accept; admins can resend to issue a fresh token. |
| `ACCESSFLOW_SECURITY_PASSWORD_RESET_TTL` | Optional | `PT1H` | ISO-8601 duration. TTL of password-reset tokens issued by `POST /api/v1/auth/password/forgot`. Tokens are single-use; users must request a new one if the link expires. |
| `ACCESSFLOW_SECURITY_PASSWORD_RESET_RESET_BASE_URL` | Optional | `http://localhost:5173` | Base URL embedded in password-reset emails. Set this to your production frontend origin (e.g. `https://accessflow.example.com`); the emailed link is `{base}/reset-password/{token}`. |

#### Observability

| Variable | Required | Default | Description |
|----------|---------|---------|-------------|
| `ACCESSFLOW_TRACING_SAMPLING_PROBABILITY` | Optional | `1.0` | Micrometer Tracing sampling probability (`0.0` – `1.0`). Lower this in high-traffic deployments to reduce export volume; MDC trace ids and `ProblemDetail.traceId` are populated regardless. See [docs/05-backend.md](05-backend.md). |
| `ACCESSFLOW_LOGGING_STRUCTURED_FORMAT` | Optional | — | When set, console logs are emitted as one JSON object per line in the named schema. Accepted values: `logstash` (recommended for ELK / OpenSearch), `ecs` (Elastic Common Schema, for Elastic SIEM), `gelf` (Graylog). When unset (default), AccessFlow emits the plain-text format with the `[accessflow-app,<traceId>,<spanId>]` prefix. The MDC `traceId` / `spanId` populated by the Micrometer tracing bridge are top-level fields in every JSON variant — no extra wiring needed. See [docs/05-backend.md → "Observability and tracing"](05-backend.md#observability-and-tracing). |

> **Spring Boot banner.** AccessFlow disables the ASCII banner at startup (`spring.main.banner-mode=off`) so it does not pollute JSON log streams or ELK pipelines. Operators who want it back can re-enable it via `SPRING_MAIN_BANNER_MODE=console` (Spring relaxed binding).

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

### audit_log role separation

The application **never** issues `UPDATE`, `DELETE`, or `TRUNCATE` against `audit_log` —
writes go through `AuditLogService.record(...)` which only `INSERT`s, and reads through
`AuditLogService.query(...)` which only `SELECT`s (see [docs/07-security.md → "Audit log
integrity"](07-security.md)).

A pair of changes enforces that contract at the database layer:

1. **Two Postgres roles**, not one. The general `DB_USER` (default `accessflow`) handles
   every connection except audit writes. A dedicated `AUDIT_DB_USER` (default
   `accessflow_audit`) owns `audit_log` and is the only principal that INSERTs rows.
2. **Flyway migration `V38__audit_log_role_separation.sql`** runs automatically on backend
   startup. It transfers ownership of `audit_log` to `AUDIT_DB_USER`, revokes all privileges
   from `DB_USER`, and re-grants only `SELECT` (for the admin read endpoint). Without
   ownership, `DB_USER` no longer carries implicit privileges — the REVOKE bites.

The application wires the second role into a separate Hikari pool. `DefaultAuditLogService`
INSERTs through it; everything else (including reads on `audit_log`) goes through the
primary `DataSource`.

**The audit role must exist before Flyway runs.** The migration aborts startup with a
clear error if it does not.

#### Docker Compose

The bundled [`docker-compose.yml`](../docker-compose.yml) bind-mounts
[`deploy/postgres-init/`](../deploy/postgres-init/) into
`/docker-entrypoint-initdb.d` on the `postgres` service. The script
[`01-audit-role.sql`](../deploy/postgres-init/01-audit-role.sql) runs once on first init
of the Postgres data volume — it creates `accessflow_audit`, grants it `CONNECT` +
`USAGE`, and grants the `accessflow` role membership in `accessflow_audit` so the
subsequent `ALTER TABLE OWNER` in V38 succeeds without superuser privileges.

For the demo, `AUDIT_DB_PASSWORD` defaults to `accessflow_audit` (override via the env
var when running in production).

#### Helm

The bundled chart provisions the role through
`postgresql.primary.initdb.scripts.01-audit-role.sh` (see
[`charts/accessflow/values.yaml`](../charts/accessflow/values.yaml)). The script reads
the audit-role password from `AUDIT_DB_PASSWORD`, which the bitnami subchart sources
from the chart-managed Secret `{release}-accessflow-audit-db`. That Secret carries
`helm.sh/resource-policy: keep` so its value survives `helm uninstall` in lock-step with
the postgresql PVC (the same pattern used by the main DB Secret in
[`templates/db-secret.yaml`](../charts/accessflow/templates/db-secret.yaml)).

The backend Deployment exposes the same Secret to the application as `AUDIT_DB_PASSWORD`
and emits `AUDIT_DB_USER` from the ConfigMap so the audit `DataSource` bean can
authenticate.

To bring your own audit Secret (sealed-secrets, External Secrets Operator, Vault…),
override `audit.db.existingSecret` in your values. This path is fully supported only
when `postgresql.enabled=false` (external Postgres) — when the bundled subchart is in
use, the chart always manages the audit-db Secret to keep the initdb script and the
backend pointing at the same value.

#### External Postgres (or managed DBaaS)

When `postgresql.enabled=false`, run the role provisioning manually against your managed
database before the first `helm install`:

```sql
-- Replace placeholders to match your deployment.
CREATE ROLE accessflow_audit LOGIN PASSWORD '<strong password>';
GRANT CONNECT ON DATABASE accessflow TO accessflow_audit;
GRANT USAGE  ON SCHEMA public TO accessflow_audit;
-- So V38 can ALTER TABLE OWNER without superuser privileges.
GRANT accessflow_audit TO accessflow;
```

Then store the password under a Kubernetes Secret and point the chart at it:

```yaml
audit:
  db:
    username: accessflow_audit
    existingSecret: accessflow-pg-audit-secret
    existingSecretPasswordKey: password
```

See [`charts/accessflow/examples/values-external-services.yaml`](../charts/accessflow/examples/values-external-services.yaml)
for the full external-services template.

#### Verifying the grants

After deployment, confirm at the database layer that the general role can no longer
mutate `audit_log`:

```bash
# Connect as the general DB_USER (NOT the audit role or postgres superuser).
psql "$DB_URL" -c "DELETE FROM audit_log WHERE id IS NULL"
# Expected: ERROR:  permission denied for table audit_log
```

The integration test `AuditRoleSeparationIntegrationTest` asserts the same property as
part of every backend build.

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
