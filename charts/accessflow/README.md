# AccessFlow Helm Chart

Helm chart for [AccessFlow](https://github.com/bablsoft/accessflow) — an open-source database & API access governance platform.

## TL;DR

```bash
helm repo add accessflow https://bablsoft.github.io/accessflow
helm repo update

helm install accessflow accessflow/accessflow \
  --namespace accessflow \
  --create-namespace
```

That's it. With defaults, the chart:

- generates the AES-256-GCM encryption key and JWT RS256 private key on first install and stores them in a chart-managed Secret (`{release}-accessflow-secrets`) that is preserved across `helm upgrade` (and survives `helm uninstall` thanks to `helm.sh/resource-policy: keep`),
- pulls in the bundled `bitnami/postgresql` subchart, which auto-generates a random database password and stores it in `{release}-postgresql`,
- pulls in the bundled `bitnami/redis` subchart with auth disabled (in-cluster only).

For production, supply your own `my-values.yaml` (Ingress host, TLS, CORS origin, externalised secrets, …) and pass it with `--values my-values.yaml`.

The chart `version` and `appVersion` are kept in lock-step with AccessFlow releases — `helm install accessflow/accessflow --version 1.2.3` always installs the `1.2.3` images.

## Example values files

Self-contained starting points for the common deployment shapes live under
[`examples/`](examples/). They split into **deployment shapes** (cluster-
level: replicas, ingress, secrets model) and **bootstrap slices**
(declarative admin config). Pick one of each and layer them:

```bash
helm install accessflow accessflow/accessflow \
  --namespace accessflow --create-namespace \
  -f charts/accessflow/examples/values-production.yaml \
  -f charts/accessflow/examples/values-bootstrap-oauth2-sso.yaml
```

### Deployment shapes

| File | Scenario |
|---|---|
| [`examples/values-minimal.yaml`](examples/values-minimal.yaml) | Single-replica demo over plain HTTP. |
| [`examples/values-production.yaml`](examples/values-production.yaml) | HA backend (HPA + PDB + pod anti-affinity), cert-manager-issued TLS, persistent driver cache. |
| [`examples/values-external-services.yaml`](examples/values-external-services.yaml) | Managed Postgres + Redis (RDS / ElastiCache / …), every secret managed outside the chart. |
| [`examples/values-airgapped.yaml`](examples/values-airgapped.yaml) | Air-gapped: internal registry mirror, offline JDBC drivers, manual TLS Secret. |
| [`examples/values-observability.yaml`](examples/values-observability.yaml) | OTLP trace export to a collector + Prometheus scrape annotations + the pre-built Grafana dashboards. |
| [`examples/values-backup.yaml`](examples/values-backup.yaml) | Nightly `pg_dump` backups to a PVC with retention pruning, optional rclone upload (S3/GCS/…), and the one-shot restore Job (AF-458). |

### Observability (AF-454)

`observability.tracing.otlp.{enabled,endpoint}` wires OTLP trace export of the proxy
pipeline (parse → AI analyze → pool acquire → execute) to Tempo / Jaeger / Honeycomb —
the chart emits `OTEL_EXPORTER_OTLP_ENDPOINT` into the backend env. `observability.metrics.podAnnotations`
adds Prometheus scrape annotations for `/actuator/prometheus` (unauthenticated for in-cluster
scraping — keep it off the public ingress and restrict with a NetworkPolicy). `dashboards.enabled=true`
ships the JSON in [`dashboards/`](dashboards/) as a `grafana_dashboard`-labelled ConfigMap that the
Grafana sidecar auto-imports (query volume, approval SLAs, AI usage/cost, rejection rates, pool stats).

### Backup & restore (AF-458)

`backup.enabled=true` renders a CronJob that `pg_dump -Fc`s the AccessFlow database
(bundled subchart or `externalDatabase`) onto a dedicated backups PVC (`helm.sh/resource-policy: keep`),
prunes to the newest `backup.retention.keepLast` dumps, and — with `backup.upload.enabled` —
ships the directory to any rclone remote (S3, GCS, Azure, SFTP, …) using an operator
Secret holding `rclone.conf`. NFS destinations need no upload step: point
`backup.persistence.storageClass` / `.existingClaim` at an NFS-backed volume.

Restore is a one-shot helm-hook Job: scale the backend to 0, then
`helm upgrade --reuse-values --set restore.enabled=true --set restore.dumpFile=<name>.dump`.
The Job provisions the audit-writer role and runs `pg_restore --clean --if-exists`
preserving `audit_log` ownership + ACLs. Afterwards scale the backend back up with
`ACCESSFLOW_AUDIT_VERIFY_CHAIN_ON_STARTUP=true` (via `backend.env`) to verify every
organization's audit HMAC chain. Full runbook:
[docs/09-deployment.md → Disaster Recovery](https://github.com/bablsoft/accessflow/blob/main/docs/09-deployment.md).

### Bootstrap (declarative admin config)

Each adds the `bootstrap:` block — organization, first admin user, and the
specific slice listed below. Designed to layer on a deployment shape; none
set ingress / replicas / secrets on their own.

| File | Adds |
|---|---|
| [`examples/values-bootstrap-minimal.yaml`](examples/values-bootstrap-minimal.yaml) | Organization + first admin user, nothing else. Skip the first-run signup screen. |
| [`examples/values-bootstrap-oauth2-sso.yaml`](examples/values-bootstrap-oauth2-sso.yaml) | OAuth2 providers (Google, Microsoft Entra ID, GitHub). |
| [`examples/values-bootstrap-saml-sso.yaml`](examples/values-bootstrap-saml-sso.yaml) | SAML 2.0 SP wired to a corporate IdP (Okta, Azure AD, JumpCloud, Auth0, ADFS). |
| [`examples/values-bootstrap-datasources.yaml`](examples/values-bootstrap-datasources.yaml) | AI provider + tiered review plans + multi-dialect datasources (Postgres, MySQL, MSSQL). |
| [`examples/values-bootstrap-notifications.yaml`](examples/values-bootstrap-notifications.yaml) | System SMTP relay + Slack / email / webhook channels + a fan-out review plan. |
| [`examples/values-bootstrap.yaml`](examples/values-bootstrap.yaml) | Kitchen-sink reference covering every `bootstrap.*` field at once. |

The example files are sourced from GitHub — they are intentionally excluded
from the packaged chart (`.helmignore`) so the `.tgz` stays lean.

## Prerequisites

- Kubernetes ≥ 1.27
- Helm ≥ 3.14
- An Ingress controller (default values target `ingress-nginx` but the chart is agnostic — set `ingress.className` to `traefik`, `alb`, etc., or `ingress.enabled=false` to expose via port-forward / your own routing)
- An external PostgreSQL **or** the bundled subchart (`postgresql.enabled=true`, default)
- An external Redis **or** the bundled subchart (`redis.enabled=true`, default)

## Secrets

The chart prefers a zero-touch model: with the defaults above, no Kubernetes Secrets need to be pre-created. The relevant Secrets are:

| Secret | Source by default | How to override |
|---|---|---|
| Encryption key (AES-256-GCM) | Auto-generated into `{release}-accessflow-secrets` (key `ENCRYPTION_KEY`) | Pre-create your own and set `config.encryptionKey.existingSecret` (and optionally `.key`) |
| JWT private key (RSA-2048 PEM) | Auto-generated into the same `{release}-accessflow-secrets` (key `JWT_PRIVATE_KEY`) | Pre-create your own and set `config.jwtPrivateKey.existingSecret` (and optionally `.key`) |
| PostgreSQL password | Auto-generated into `{release}-accessflow-db` (keys `password` + `postgres-password`) | Pre-create your own and set `postgresql.auth.existingSecret` |
| Audit HMAC key (optional) | Derived at runtime from the encryption key via HKDF-SHA256 | Pre-create your own and set `config.auditHmac.existingSecret` |

Both auto-generated Secrets carry `helm.sh/resource-policy: keep` so they
survive `helm uninstall`. That's especially important for the Postgres
password — the postgresql PVC also survives uninstall, and a fresh random
password generated on `helm install` against an existing data dir would
produce `FATAL: password authentication failed for user "accessflow"` (the
historical [#228](https://github.com/bablsoft/accessflow/issues/228) failure
mode).

### Bringing your own secrets

```bash
kubectl create namespace accessflow

# PostgreSQL password — the Secret MUST expose both `password` (the AccessFlow
# user's password) and `postgres-password` (the admin user's password).
kubectl -n accessflow create secret generic accessflow-pg-secret \
  --from-literal=password="$(openssl rand -base64 24)" \
  --from-literal=postgres-password="$(openssl rand -base64 24)"

# AES-256-GCM encryption key — 64 hex chars (32 bytes). The default `key` is "value".
kubectl -n accessflow create secret generic accessflow-encryption-key \
  --from-literal=value="$(openssl rand -hex 32)"

# JWT RS256 private key (RSA-2048 PEM). The default `key` is "value".
# Either PKCS#8 (`-----BEGIN PRIVATE KEY-----`) or the legacy PKCS#1
# (`-----BEGIN RSA PRIVATE KEY-----`) PEM is accepted.
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt_private_key.pem
kubectl -n accessflow create secret generic accessflow-jwt-key \
  --from-file=value=./jwt_private_key.pem
```

Then point the chart at them via `values.yaml`:

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

> AI provider keys (Anthropic / OpenAI / Ollama) are **not** chart inputs — they're stored per-organization in the `ai_config` table and managed from the admin UI. See [docs/05-backend.md → "AI Query Analyzer Service"](https://github.com/bablsoft/accessflow/blob/main/docs/05-backend.md).

## TLS / HTTPS

The Ingress is rendered with **TLS disabled** by default — the chart makes no assumption about how you provision certificates. To turn it on:

```yaml
ingress:
  enabled: true
  className: nginx
  annotations:
    # Have cert-manager provision the cert for you (optional)
    cert-manager.io/cluster-issuer: letsencrypt-prod
  hosts:
    - host: accessflow.company.com
      paths:
        - { path: /api/, pathType: Prefix, service: backend }
        - { path: /ws,   pathType: Prefix, service: backend }
        - { path: /,     pathType: Prefix, service: frontend }
  tls:
    enabled: true
    secretName: accessflow-tls   # Created by cert-manager (above) or pre-loaded with kubectl
```

When you set `ingress.tls.enabled=true`, the chart will fail-fast at template time if `secretName` is empty.

## Selected values

The full reference lives in [`values.yaml`](values.yaml) and [`docs/09-deployment.md`](https://github.com/bablsoft/accessflow/blob/main/docs/09-deployment.md). The most commonly tuned keys:

| Key | Default | Description |
|-----|---------|-------------|
| `replicaCount.backend` | `2` | Backend replica count. Honored when `autoscaling.backend.enabled=false` (the default); when the HPA is enabled, its `minReplicas` floor takes over. |
| `replicaCount.frontend` | `2` | Frontend replica count. |
| `image.backend.tag` / `image.frontend.tag` | `""` | Override the chart's `appVersion`. Leave empty in normal use. |
| `postgresql.enabled` | `true` | Install the bundled bitnami/postgresql subchart. Set `false` to use `externalDatabase`. |
| `postgresql.auth.existingSecret` | `""` (auto-generated) | Bitnami's Secret reference. When empty, the subchart auto-generates the password into `{release}-postgresql`. |
| `redis.enabled` | `true` | Install the bundled bitnami/redis subchart. Set `false` to use `externalRedis`. |
| `externalDatabase.host` / `.port` / `.database` / `.username` / `.existingSecret` | `""` | External PostgreSQL settings (used when `postgresql.enabled=false`). |
| `externalRedis.url` | `""` | External Redis URL (e.g. `redis://host:6379`). |
| `config.encryptionKey.existingSecret` / `.key` | `""` (auto-generated) / `value` | Override only if you manage the encryption key externally. |
| `config.jwtPrivateKey.existingSecret` / `.key` | `""` (auto-generated) / `value` | Override only if you manage the JWT key externally. |
| `config.corsAllowedOrigin` | `https://accessflow.company.com` | Frontend origin allowed by CORS. |
| `config.frontend.apiBaseUrl` / `.wsUrl` | `https://accessflow.company.com[/ws]` | Rendered into the runtime-config.js ConfigMap. |
| `ingress.enabled` / `.className` / `.hosts` | enabled, `nginx` | Single Ingress dispatches `/api`+`/ws` → backend, `/` → frontend. `paths` is optional; when omitted, the chart fills in the standard 3-path routing. |
| `ingress.tls.enabled` / `.secretName` | `false` / `accessflow-tls` | TLS termination at the Ingress. Off by default. |
| `ingress.annotations` | `{}` | Free-form Ingress annotations (cert-manager, nginx, ALB, …). No cert-manager annotation is set by default. |
| `resources.backend.*` / `resources.frontend.*` | see `values.yaml` | Pod-level requests / limits. |
| `autoscaling.backend.enabled` | `false` | Horizontal Pod Autoscaler for backend (CPU-based). Off by default so `replicaCount.backend` is the single source of truth on first install. |
| `podDisruptionBudget.backend.enabled` | `false` | PDB protecting backend during rolling updates. Off by default — enable on production deployments running ≥ 2 replicas. |
| `driverCache.persistence.enabled` | `false` | Use a **durable PVC** for the on-demand driver / engine-plugin cache. When `false`, an ephemeral `emptyDir` is mounted at the same path instead — so connectors work out of the box either way; the PVC just avoids re-downloading on restart. A writable cache volume is **always** mounted (the default `~/.accessflow/drivers` is not writable under `runAsUser: 1000`, which would leave every non-bundled connector `UNAVAILABLE`). |
| `driverCache.emptyDir.sizeLimit` | `""` | Optional `sizeLimit` for the ephemeral cache (e.g. `1Gi`), used only when `persistence.enabled=false`. Empty = no limit. |
| `backup.enabled` | `false` | Nightly `pg_dump -Fc` CronJob onto the backups PVC, pruned to `backup.retention.keepLast` dumps (AF-458). |
| `backup.schedule` / `backup.retention.keepLast` | `"0 2 * * *"` / `7` | Backup cadence (cron, UTC) and how many newest dumps to keep. |
| `backup.persistence.existingClaim` / `.size` / `.storageClass` | `""` / `20Gi` / `""` | Backups volume — chart-created PVC (kept across uninstall) or an operator-managed claim (e.g. NFS). |
| `backup.upload.enabled` / `.remote` / `.existingSecret` | `false` / `""` / `""` | Optional rclone shipping of the backup directory to S3/GCS/… — the Secret must hold an `rclone.conf` key. |
| `restore.enabled` / `restore.dumpFile` | `false` / `""` | One-shot restore Job (helm hook) replaying a named dump from the backups volume. See the Backup & restore section. |

## Upgrade

```bash
helm repo update
helm upgrade accessflow accessflow/accessflow \
  --namespace accessflow \
  --version <new-version> \
  --values my-values.yaml
```

The backend runs Flyway migrations on startup, so no separate migration job is required. New replicas wait for the rolling update to complete before serving traffic via the readiness probe.

The chart-managed Secret carries `helm.sh/resource-policy: keep`, so `helm uninstall` will leave the encryption key and JWT key behind. **Do not delete that Secret while encrypted datasource credentials exist in the database** — they will become unreadable.

## Development

```bash
helm dependency update charts/accessflow
helm lint charts/accessflow
helm template accessflow charts/accessflow
```

The chart is published from this repo's `release.yml` workflow on every tagged release. See [docs/09-deployment.md → "Chart development"](https://github.com/bablsoft/accessflow/blob/main/docs/09-deployment.md) for the publish flow.
