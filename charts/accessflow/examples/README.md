# AccessFlow Helm chart — example values files

Starting points for the most common deployment scenarios. Each file is a
**minimal override on top of the chart's [`values.yaml`](../values.yaml)** —
not a full values dump — so you can read it end-to-end and see exactly
what's being changed. Anything not listed inherits the chart default.

### Deployment shapes

Pick one of these as the base. They cover the cluster-level setup
(replicas, autoscaling, ingress, persistence, secrets model):

| File | Scenario | Highlights |
|---|---|---|
| [`values-minimal.yaml`](values-minimal.yaml) | Single-replica demo over plain HTTP | One backend, one frontend, bundled Postgres + Redis, chart-generated secrets, Ingress without TLS. Not for production. |
| [`values-production.yaml`](values-production.yaml) | Production with HA + TLS | 3 backends (HPA + PDB + pod anti-affinity), 2 frontends, ingress-nginx with cert-manager-issued TLS, sized PVCs, persistent JDBC driver cache. |
| [`values-external-services.yaml`](values-external-services.yaml) | Managed Postgres + Redis, externally-managed secrets | bitnami subcharts disabled, RDS / ElastiCache / Vault-style refs for every secret. |
| [`values-airgapped.yaml`](values-airgapped.yaml) | Air-gapped / on-premise | Internal image registry, `ACCESSFLOW_DRIVERS_OFFLINE=true`, pre-seeded driver cache, manual TLS Secret. |

### Bootstrap (declarative admin config)

Each of these declares the `bootstrap:` block — `bootstrap.enabled: true`,
the organization, and the first admin user — plus the slice of admin
state listed in the table. They are designed to **layer on top of** one
of the deployment shapes above (or your own equivalent); none of them set
ingress / replicas / secrets backend on their own.

| File | Adds | Use it when |
|---|---|---|
| [`values-bootstrap-minimal.yaml`](values-bootstrap-minimal.yaml) | Organization + first admin user, nothing else | You want to skip the first-run signup screen but otherwise operate via the admin UI. |
| [`values-bootstrap-oauth2-sso.yaml`](values-bootstrap-oauth2-sso.yaml) | OAuth2 providers (Google, Microsoft Entra ID, GitHub) | SSO-first deployments. The local admin password stays as a break-glass account. |
| [`values-bootstrap-saml-sso.yaml`](values-bootstrap-saml-sso.yaml) | SAML 2.0 SP wired to a corporate IdP | Enterprise IdPs (Okta, Azure AD, JumpCloud, Auth0, ADFS). |
| [`values-bootstrap-datasources.yaml`](values-bootstrap-datasources.yaml) | AI provider + tiered review plans + multi-dialect datasources (Postgres, MySQL, MSSQL) | Day-one ready to query — submit SQL through AccessFlow without touching the admin UI first. |
| [`values-bootstrap-notifications.yaml`](values-bootstrap-notifications.yaml) | System SMTP relay + Slack / email / webhook channels + a fan-out review plan | Outbound notifications go live with the install (password resets, review pings, observability webhooks). |
| [`values-bootstrap.yaml`](values-bootstrap.yaml) | All of the above in one file | Kitchen-sink reference covering every `bootstrap.*` field at once. |

```bash
# Pick one deployment shape + one bootstrap slice and layer them:
helm install accessflow accessflow/accessflow \
  --namespace accessflow --create-namespace \
  -f charts/accessflow/examples/values-production.yaml \
  -f charts/accessflow/examples/values-bootstrap-datasources.yaml
```

## Usage

```bash
helm repo add accessflow https://bablsoft.github.io/accessflow
helm repo update

helm install accessflow accessflow/accessflow \
  --namespace accessflow --create-namespace \
  -f charts/accessflow/examples/values-production.yaml
```

You can layer multiple files — later files override earlier ones. The
intended pattern is **deployment shape + one bootstrap slice + your own
overrides**:

```bash
helm install accessflow accessflow/accessflow \
  --namespace accessflow --create-namespace \
  -f charts/accessflow/examples/values-production.yaml \
  -f charts/accessflow/examples/values-bootstrap-oauth2-sso.yaml \
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
