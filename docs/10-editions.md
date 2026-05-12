# 10 — Community vs Enterprise Edition

## Overview

AccessFlow is developed as an open-source project under the **Apache 2.0 license**. The Enterprise Edition is a commercially licensed superset that adds SAML/SSO, multi-organization tenancy, and other features suited for large organizations.

The separation is enforced in the Spring Boot application via:
- `@ConditionalOnProperty(name = "accessflow.edition", havingValue = "enterprise")` on Enterprise-only beans
- A license key validation step at startup for Enterprise edition
- Frontend reads `/api/v1/system/info` to determine active edition and conditionally renders Enterprise-only UI

---

## Feature Matrix

| Feature | Community | Enterprise |
|---------|-----------|------------|
| **Core Proxy** | | |
| SQL proxy for PostgreSQL | ✓ | ✓ |
| SQL proxy for MySQL / MariaDB / Oracle / SQL Server | ✓ | ✓ |
| Admin-uploaded custom JDBC drivers (per-datasource selection) | ✓ | ✓ |
| Dynamic datasources (free-form JDBC URL + uploaded driver) | ✓ | ✓ |
| Connection pool per datasource | ✓ | ✓ |
| Max rows per query enforcement | ✓ | ✓ |
| Schema / table allow-listing | ✓ | ✓ |
| **SQL Editor** | | |
| Built-in SQL editor (CodeMirror) | ✓ | ✓ |
| Schema autocomplete | ✓ | ✓ |
| SQL formatter | ✓ | ✓ |
| **Review Workflows** | | |
| Configurable review plans | ✓ | ✓ |
| Multi-stage approval chains | ✓ | ✓ |
| Per-datasource review config | ✓ | ✓ |
| Approval timeout / auto-reject | ✓ | ✓ |
| **AI Query Analysis** | | |
| OpenAI API integration | ✓ | ✓ |
| Anthropic API integration | ✓ | ✓ |
| Self-hosted Ollama integration | ✓ | ✓ |
| Risk scoring | ✓ | ✓ |
| Missing index detection | ✓ | ✓ |
| Anti-pattern suggestions | ✓ | ✓ |
| **Access Control** | | |
| Role-based access (4 roles) | ✓ | ✓ |
| Per-user datasource permissions | ✓ | ✓ |
| Time-limited access grants | ✓ | ✓ |
| Local user management | ✓ | ✓ |
| **Audit & Compliance** | | |
| Metadata audit log | ✓ | ✓ |
| Audit log search & filter | ✓ | ✓ |
| Audit log CSV export | — | ✓ |
| **Notifications** | | |
| Email notifications | ✓ | ✓ |
| Slack notifications | ✓ | ✓ |
| Webhook notifications | ✓ | ✓ |
| **Authentication** | | |
| JWT (email + password) | ✓ | ✓ |
| SAML 2.0 / SSO integration | — | ✓ |
| Auto-provisioning via SAML | — | ✓ |
| SAML attribute → role mapping | — | ✓ |
| **Deployment** | | |
| Docker Compose | ✓ | ✓ |
| Helm chart (Kubernetes) | ✓ | ✓ |
| **Multi-tenancy** | | |
| Single organization | ✓ | ✓ |
| Multiple organizations | — | ✓ |
| **Support** | | |
| Community GitHub Issues | ✓ | ✓ |
| Priority support SLA | — | ✓ |
| **Branding** | | |
| Custom logo / white-label | — | ✓ |

---

## Edition Detection at Runtime

### Backend

The edition is set via the `ACCESSFLOW_EDITION` environment variable, read into the Spring `Environment`. Enterprise beans are annotated:

```java
@Service
@ConditionalOnProperty(name = "accessflow.edition", havingValue = "enterprise")
public class SamlAuthenticationService implements AuthenticationService {
    // Enterprise-only SAML implementation
}

@Service
@ConditionalOnProperty(name = "accessflow.edition", havingValue = "community", matchIfMissing = true)
public class LocalAuthenticationService implements AuthenticationService {
    // Community JWT implementation
}
```

### System Info Endpoint

```http
GET /api/v1/system/info
```

```json
{
  "version": "1.0.0",
  "edition": "community",
  "features": {
    "saml_enabled": false,
    "multi_org_enabled": false,
    "audit_export_enabled": false
  }
}
```

### Frontend

The frontend reads `features` from `/system/info` on app load (stored in Zustand) and uses it to conditionally render Enterprise-only routes and UI elements:

```typescript
// Example: conditionally show SAML config menu item
const { features } = useSystemInfo();

{features.saml_enabled && (
  <Menu.Item key="saml">
    <Link to="/admin/saml">SSO Configuration</Link>
  </Menu.Item>
)}
```

---

## Licensing

| Edition | License | Source Code |
|---------|---------|-------------|
| Community | Apache 2.0 | Fully open — `github.com/accessflow/accessflow` |
| Enterprise | Commercial | Core modules open; Enterprise modules in private repo |

Enterprise modules (`accessflow-enterprise-*`) are distributed as compiled JARs alongside the open-source modules. They activate automatically when `ACCESSFLOW_EDITION=enterprise` and a valid license key is present.

License keys are validated at startup against the AccessFlow licensing service (online) or a provided offline license file (air-gapped Enterprise deployments).
