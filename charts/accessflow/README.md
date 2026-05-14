# AccessFlow Helm Chart

Helm chart for [AccessFlow](https://github.com/bablsoft/accessflow) — an open-source database access governance platform.

## TL;DR

```bash
helm repo add accessflow https://bablsoft.github.io/accessflow
helm repo update

# Create required secrets first (see "Secrets" below)
helm install accessflow accessflow/accessflow \
  --namespace accessflow \
  --create-namespace \
  --values my-values.yaml
```

The chart `version` and `appVersion` are kept in lock-step with AccessFlow releases — `helm install accessflow/accessflow --version 1.2.3` always installs the `1.2.3` images.

## Prerequisites

- Kubernetes ≥ 1.27
- Helm ≥ 3.14
- An Ingress controller (default values target `ingress-nginx`)
- cert-manager when `ingress.annotations` reference an issuer (default values do)
- An external PostgreSQL **or** the bundled subchart (`postgresql.enabled=true`, default)
- An external Redis **or** the bundled subchart (`redis.enabled=true`, default)

## Secrets

The chart never creates secret material — it only references existing Kubernetes Secrets via the `existingSecret` pattern. Create them before `helm install`:

```bash
# PostgreSQL password (when postgresql.enabled=true OR when externalDatabase.existingSecret is used)
kubectl create secret generic accessflow-pg-secret \
  --from-literal=password='strong_db_password' -n accessflow

# AES-256-GCM encryption key — 32 hex bytes
kubectl create secret generic accessflow-encryption-key \
  --from-literal=value="$(openssl rand -hex 32)" -n accessflow

# JWT RS256 private key (RSA-2048 PEM)
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt_private_key.pem
kubectl create secret generic accessflow-jwt-key \
  --from-file=value=./jwt_private_key.pem -n accessflow
```

> AI provider keys (Anthropic / OpenAI / Ollama) are **not** chart inputs — they're stored per-organization in the `ai_config` table and managed from the admin UI. See [docs/05-backend.md → "AI Query Analyzer Service"](https://github.com/bablsoft/accessflow/blob/main/docs/05-backend.md).

## Selected values

The full reference lives in [`values.yaml`](values.yaml) and [`docs/09-deployment.md`](https://github.com/bablsoft/accessflow/blob/main/docs/09-deployment.md). The most commonly tuned keys:

| Key | Default | Description |
|-----|---------|-------------|
| `replicaCount.backend` | `3` | Backend replica count (overridden by HPA when enabled). |
| `replicaCount.frontend` | `2` | Frontend replica count. |
| `image.backend.tag` / `image.frontend.tag` | `""` | Override the chart's `appVersion`. Leave empty in normal use. |
| `postgresql.enabled` | `true` | Install the bundled bitnami/postgresql subchart. Set `false` to use `externalDatabase`. |
| `redis.enabled` | `true` | Install the bundled bitnami/redis subchart. Set `false` to use `externalRedis`. |
| `externalDatabase.host` / `.port` / `.database` / `.username` / `.existingSecret` | `""` | External PostgreSQL settings. |
| `externalRedis.url` | `""` | External Redis URL (e.g. `redis://host:6379`). |
| `config.encryptionKeySecret` | `accessflow-encryption-key` | Secret with a `value` key — 32-byte hex AES-256-GCM key. |
| `config.jwtPrivateKeySecret` | `accessflow-jwt-key` | Secret with a `value` key — RSA-2048 PEM. |
| `config.corsAllowedOrigin` | `https://accessflow.company.com` | Frontend origin allowed by CORS. |
| `config.frontend.apiBaseUrl` / `.wsUrl` | `https://accessflow.company.com[/ws]` | Rendered into the runtime-config.js ConfigMap. |
| `ingress.enabled` / `.className` / `.hosts` / `.tls` | enabled, `nginx` | Single Ingress dispatches `/api`+`/ws` → backend, `/` → frontend. |
| `resources.backend.*` / `resources.frontend.*` | see `values.yaml` | Pod-level requests / limits. |
| `autoscaling.backend.enabled` | `true` | Horizontal Pod Autoscaler for backend (CPU-based). |
| `podDisruptionBudget.backend.enabled` | `true` | PDB protecting backend during rolling updates. |
| `driverCache.persistence.enabled` | `false` | Mount a PVC at the backend's custom-driver cache path. |

## Upgrade

```bash
helm repo update
helm upgrade accessflow accessflow/accessflow \
  --namespace accessflow \
  --version <new-version> \
  --values my-values.yaml
```

The backend runs Flyway migrations on startup, so no separate migration job is required. New replicas wait for the rolling update to complete before serving traffic via the readiness probe.

## Development

```bash
helm dependency update charts/accessflow
helm lint charts/accessflow
helm template accessflow charts/accessflow
```

The chart is published from this repo's `release.yml` workflow on every tagged release. See [docs/09-deployment.md → "Chart development"](https://github.com/bablsoft/accessflow/blob/main/docs/09-deployment.md) for the publish flow.
