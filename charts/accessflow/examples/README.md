# AccessFlow Helm chart — example values files

Starting points for the most common deployment scenarios. Each file is a
**minimal override on top of the chart's [`values.yaml`](../values.yaml)** —
not a full values dump — so you can read it end-to-end and see exactly
what's being changed. Anything not listed inherits the chart default.

| File | Scenario | Highlights |
|---|---|---|
| [`values-minimal.yaml`](values-minimal.yaml) | Single-replica demo over plain HTTP | One backend, one frontend, bundled Postgres + Redis, chart-generated secrets, Ingress without TLS. Not for production. |
| [`values-production.yaml`](values-production.yaml) | Production with HA + TLS | 3 backends (HPA + PDB + pod anti-affinity), 2 frontends, ingress-nginx with cert-manager-issued TLS, sized PVCs, persistent JDBC driver cache. |
| [`values-external-services.yaml`](values-external-services.yaml) | Managed Postgres + Redis, externally-managed secrets | bitnami subcharts disabled, RDS / ElastiCache / Vault-style refs for every secret. |
| [`values-bootstrap.yaml`](values-bootstrap.yaml) | GitOps-driven admin config | Declares organization, admin user, datasources, AI provider, review plan, OAuth2, notification channel via the `bootstrap:` block. Reconciled on every backend start. |
| [`values-airgapped.yaml`](values-airgapped.yaml) | Air-gapped / on-premise | Internal image registry, `ACCESSFLOW_DRIVERS_OFFLINE=true`, pre-seeded driver cache, manual TLS Secret. |

## Usage

```bash
helm repo add accessflow https://bablsoft.github.io/accessflow
helm repo update

helm install accessflow accessflow/accessflow \
  --namespace accessflow --create-namespace \
  -f charts/accessflow/examples/values-production.yaml
```

You can layer multiple files — later files override earlier ones. A common
production setup is to start from `values-production.yaml`, then layer your
own site-specific overrides on top:

```bash
helm install accessflow accessflow/accessflow \
  --namespace accessflow --create-namespace \
  -f charts/accessflow/examples/values-production.yaml \
  -f my-site-overrides.yaml
```

## What's NOT in here

- Every example references `accessflow.company.com` (or `*.acme.example.com`)
  — change to your actual hostname before installing.
- Every example assumes the `ingress-nginx` controller. Set
  `ingress.className` to `traefik`, `alb`, etc. for other controllers.
- These examples cover application-level configuration only. Cluster-level
  prerequisites (Ingress controller, cert-manager, StorageClass) are out
  of scope — install them separately.

## See also

- [Chart README](../README.md) — full values table, secrets model, TLS, upgrade flow.
- [docs/09-deployment.md](../../../docs/09-deployment.md) — operator guide
  with bootstrap semantics, environment-variable reference, and the
  production-hardening checklist.
