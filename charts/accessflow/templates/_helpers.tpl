{{/*
Expand the name of the chart.
*/}}
{{- define "accessflow.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Fully qualified app name. Truncated to 63 chars to obey DNS-1123 limits.
*/}}
{{- define "accessflow.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{- define "accessflow.backend.fullname" -}}
{{- printf "%s-backend" (include "accessflow.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "accessflow.frontend.fullname" -}}
{{- printf "%s-frontend" (include "accessflow.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Chart name and version, as used by the chart label.
*/}}
{{- define "accessflow.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels applied to every resource.
*/}}
{{- define "accessflow.labels" -}}
helm.sh/chart: {{ include "accessflow.chart" . }}
{{ include "accessflow.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: accessflow
{{- with .Values.commonLabels }}
{{ toYaml . }}
{{- end }}
{{- end }}

{{/*
Selector labels — must be stable across upgrades.
*/}}
{{- define "accessflow.selectorLabels" -}}
app.kubernetes.io/name: {{ include "accessflow.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "accessflow.backend.selectorLabels" -}}
{{ include "accessflow.selectorLabels" . }}
app.kubernetes.io/component: backend
{{- end }}

{{- define "accessflow.frontend.selectorLabels" -}}
{{ include "accessflow.selectorLabels" . }}
app.kubernetes.io/component: frontend
{{- end }}

{{- define "accessflow.backend.labels" -}}
{{ include "accessflow.labels" . }}
app.kubernetes.io/component: backend
{{- end }}

{{- define "accessflow.frontend.labels" -}}
{{ include "accessflow.labels" . }}
app.kubernetes.io/component: frontend
{{- end }}

{{/*
Service account name.
*/}}
{{- define "accessflow.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "accessflow.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Image tag — fall back to Chart.AppVersion when the per-tier tag is empty.
Usage: {{ include "accessflow.image.tag" (dict "tier" .Values.image.backend "ctx" $) }}
*/}}
{{- define "accessflow.image.tag" -}}
{{- $tier := .tier -}}
{{- $ctx := .ctx -}}
{{- default $ctx.Chart.AppVersion $tier.tag -}}
{{- end }}

{{/*
DB JDBC URL — derived from the bundled postgresql subchart or externalDatabase.
*/}}
{{- define "accessflow.db.url" -}}
{{- if .Values.postgresql.enabled -}}
{{- printf "jdbc:postgresql://%s-postgresql:5432/%s" .Release.Name .Values.postgresql.auth.database -}}
{{- else -}}
{{- $host := required "externalDatabase.host is required when postgresql.enabled=false" .Values.externalDatabase.host -}}
{{- printf "jdbc:postgresql://%s:%v/%s" $host .Values.externalDatabase.port .Values.externalDatabase.database -}}
{{- end -}}
{{- end }}

{{- define "accessflow.db.username" -}}
{{- if .Values.postgresql.enabled -}}
{{ .Values.postgresql.auth.username }}
{{- else -}}
{{ .Values.externalDatabase.username }}
{{- end -}}
{{- end }}

{{/*
PostgreSQL password Secret name.

When postgresql.enabled=true:
  - `postgresql.auth.existingSecret` is templated (bitnami runs it through `tpl`),
    so its default in values.yaml is `'{{ .Release.Name }}-accessflow-db'` —
    pointing at the chart-managed Secret rendered by `db-secret.yaml`. That Secret
    carries `helm.sh/resource-policy: keep`, which is what keeps the password in
    sync with the postgresql PVC across `helm uninstall` + `helm install` cycles
    (the PVC always survives uninstall; bitnami's own Secret does not, and the
    mismatch was the cause of #228's "password authentication failed").
  - Operators who manage the password externally point `existingSecret` at their
    own Secret (must expose keys `password` and `postgres-password`).

When postgresql.enabled=false (externalDatabase mode):
  - externalDatabase.existingSecret is required.
*/}}
{{- define "accessflow.db.passwordSecret" -}}
{{- if .Values.postgresql.enabled -}}
{{- tpl .Values.postgresql.auth.existingSecret . -}}
{{- else -}}
{{ required "externalDatabase.existingSecret is required when postgresql.enabled=false" .Values.externalDatabase.existingSecret }}
{{- end -}}
{{- end }}

{{/*
PostgreSQL password Secret key — always `password` for the custom user,
matching bitnami's own naming convention.
*/}}
{{- define "accessflow.db.passwordSecretKey" -}}
{{- if .Values.postgresql.enabled -}}
password
{{- else -}}
{{ default "password" .Values.externalDatabase.existingSecretPasswordKey }}
{{- end -}}
{{- end }}

{{/*
Chart-managed db Secret name. Must match the rendered value of
`postgresql.auth.existingSecret` so bitnami's `tpl` call resolves to the same
string.
*/}}
{{- define "accessflow.db.secrets.fullname" -}}
{{- printf "%s-accessflow-db" .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Returns "true" when the chart-managed db Secret should render — i.e. the
operator has left `postgresql.auth.existingSecret` at its default template
(which resolves to {{ include "accessflow.db.secrets.fullname" . }}) rather
than overriding with their own Secret name.
*/}}
{{- define "accessflow.db.chartManaged" -}}
{{- if .Values.postgresql.enabled -}}
{{- $resolved := tpl .Values.postgresql.auth.existingSecret . -}}
{{- $managed := include "accessflow.db.secrets.fullname" . -}}
{{- if eq $resolved $managed -}}true{{- end -}}
{{- end -}}
{{- end -}}

{{/*
Looks up a key on the chart-managed db Secret so the random passwords survive
upgrades AND uninstall+reinstall cycles. Falls back to the legacy
`{release}-postgresql` Secret on the first upgrade from the pre-AF-228 layout
(bitnami's auto-generated Secret) so existing data dirs keep working.

Usage:
  {{ include "accessflow.db.lookupOrDefault" (dict "ctx" . "key" "password" "default" "abcdef") }}
*/}}
{{- define "accessflow.db.lookupOrDefault" -}}
{{- $ctx := .ctx -}}
{{- $managedName := include "accessflow.db.secrets.fullname" $ctx -}}
{{- $managed := lookup "v1" "Secret" $ctx.Release.Namespace $managedName -}}
{{- if and $managed $managed.data (hasKey $managed.data .key) -}}
{{- index $managed.data .key | b64dec -}}
{{- else -}}
{{- $legacyName := printf "%s-postgresql" $ctx.Release.Name -}}
{{- $legacy := lookup "v1" "Secret" $ctx.Release.Namespace $legacyName -}}
{{- if and $legacy $legacy.data (hasKey $legacy.data .key) -}}
{{- index $legacy.data .key | b64dec -}}
{{- else -}}
{{- .default -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
Audit-writer DB role (issue #67). The dedicated Postgres role that owns audit_log after
V38__audit_log_role_separation.sql; AccessFlow's auditDataSource bean uses these creds
to INSERT into audit_log while the general DB_USER retains SELECT only.
*/}}
{{- define "accessflow.auditDb.username" -}}
{{- if .Values.postgresql.enabled -}}
{{- default "accessflow_audit" .Values.audit.db.username -}}
{{- else -}}
{{- $u := .Values.audit.db.username -}}
{{- if not $u -}}{{- fail "audit.db.username is required when postgresql.enabled=false" -}}{{- end -}}
{{- $u -}}
{{- end -}}
{{- end }}

{{- define "accessflow.auditDb.secrets.fullname" -}}
{{- printf "%s-accessflow-audit-db" .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Chart-managed audit-db Secret only renders when the bundled postgresql subchart is in
use AND the operator has not supplied their own Secret. With an external Postgres
(`postgresql.enabled=false`), the operator provisions the role + Secret manually and
points `audit.db.existingSecret` at it.
*/}}
{{/*
The chart manages the audit-db Secret whenever the bundled postgresql subchart is in
use; the operator's `audit.db.existingSecret` only takes effect with an external
Postgres (postgresql.enabled=false), where it is required.
*/}}
{{- define "accessflow.auditDb.chartManaged" -}}
{{- if .Values.postgresql.enabled -}}true{{- end -}}
{{- end -}}

{{- define "accessflow.auditDb.passwordSecret" -}}
{{- if .Values.postgresql.enabled -}}
{{ include "accessflow.auditDb.secrets.fullname" . }}
{{- else -}}
{{ required "audit.db.existingSecret is required when postgresql.enabled=false" .Values.audit.db.existingSecret }}
{{- end -}}
{{- end -}}

{{- define "accessflow.auditDb.passwordSecretKey" -}}
{{- if .Values.postgresql.enabled -}}
password
{{- else -}}
{{ default "password" .Values.audit.db.existingSecretPasswordKey }}
{{- end -}}
{{- end -}}

{{- define "accessflow.auditDb.lookupOrDefault" -}}
{{- $ctx := .ctx -}}
{{- $name := include "accessflow.auditDb.secrets.fullname" $ctx -}}
{{- $managed := lookup "v1" "Secret" $ctx.Release.Namespace $name -}}
{{- if and $managed $managed.data (hasKey $managed.data .key) -}}
{{- index $managed.data .key | b64dec -}}
{{- else -}}
{{- .default -}}
{{- end -}}
{{- end -}}

{{/*
Redis URL — bundled subchart or externalRedis.
*/}}
{{- define "accessflow.redis.url" -}}
{{- if .Values.redis.enabled -}}
{{- printf "redis://%s-redis-master:6379" .Release.Name -}}
{{- else -}}
{{- .Values.externalRedis.url -}}
{{- end -}}
{{- end }}

{{/*
OAuth2 frontend callback URL — defaults to ${corsAllowedOrigin}/auth/oauth/callback.
*/}}
{{- define "accessflow.oauth2.callbackUrl" -}}
{{- if .Values.config.oauth2FrontendCallbackUrl -}}
{{ .Values.config.oauth2FrontendCallbackUrl }}
{{- else -}}
{{ printf "%s/auth/oauth/callback" .Values.config.corsAllowedOrigin }}
{{- end -}}
{{- end }}

{{/*
Fail the render with a clear message when the runtime URLs don't carry a
scheme. Without this guard, a value like `apiBaseUrl: demo.acme.example.com`
makes axios treat the string as a relative path — the browser resolves it
against the current page, producing requests like
`https://demo.acme.example.com/demo.acme.example.com/api/v1/...` and a
non-obvious "frontend can't reach backend" failure mode.

Invoked from frontend-configmap.yaml (always rendered) so the failure
surfaces at `helm install` / `helm template` time, not at runtime.
*/}}
{{- define "accessflow.config.validateUrls" -}}
{{- $api := .Values.config.frontend.apiBaseUrl -}}
{{- if not (or (hasPrefix "http://" $api) (hasPrefix "https://" $api)) -}}
{{- fail (printf "config.frontend.apiBaseUrl must be a full URL starting with http:// or https://, got %q" $api) -}}
{{- end -}}
{{- $ws := .Values.config.frontend.wsUrl -}}
{{- if not (or (hasPrefix "ws://" $ws) (hasPrefix "wss://" $ws)) -}}
{{- fail (printf "config.frontend.wsUrl must be a full URL starting with ws:// or wss://, got %q" $ws) -}}
{{- end -}}
{{- $cors := .Values.config.corsAllowedOrigin -}}
{{- if not (or (hasPrefix "http://" $cors) (hasPrefix "https://" $cors)) -}}
{{- fail (printf "config.corsAllowedOrigin must be a full URL starting with http:// or https://, got %q" $cors) -}}
{{- end -}}
{{- $base := .Values.config.publicBaseUrl -}}
{{- if not (or (hasPrefix "http://" $base) (hasPrefix "https://" $base)) -}}
{{- fail (printf "config.publicBaseUrl must be a full URL starting with http:// or https://, got %q" $base) -}}
{{- end -}}
{{- end -}}

{{/*
Chart-managed Secret name holding auto-generated app secrets
(encryption key, JWT signing key, audit HMAC key). The chart only creates this
Secret when at least one of the corresponding `existingSecret` fields is empty.
*/}}
{{- define "accessflow.secrets.fullname" -}}
{{- printf "%s-secrets" (include "accessflow.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Resolved Secret name for the AES-256-GCM encryption key.
Falls back to the chart-managed Secret when config.encryptionKey.existingSecret is empty.
*/}}
{{- define "accessflow.encryptionKey.secretName" -}}
{{- if .Values.config.encryptionKey.existingSecret -}}
{{ .Values.config.encryptionKey.existingSecret }}
{{- else -}}
{{ include "accessflow.secrets.fullname" . }}
{{- end -}}
{{- end }}

{{- define "accessflow.encryptionKey.secretKey" -}}
{{- if .Values.config.encryptionKey.existingSecret -}}
{{ default "value" .Values.config.encryptionKey.key }}
{{- else -}}
ENCRYPTION_KEY
{{- end -}}
{{- end }}

{{/*
Resolved Secret name for the JWT RS256 private key.
Falls back to the chart-managed Secret when config.jwtPrivateKey.existingSecret is empty.
*/}}
{{- define "accessflow.jwtPrivateKey.secretName" -}}
{{- if .Values.config.jwtPrivateKey.existingSecret -}}
{{ .Values.config.jwtPrivateKey.existingSecret }}
{{- else -}}
{{ include "accessflow.secrets.fullname" . }}
{{- end -}}
{{- end }}

{{- define "accessflow.jwtPrivateKey.secretKey" -}}
{{- if .Values.config.jwtPrivateKey.existingSecret -}}
{{ default "value" .Values.config.jwtPrivateKey.key }}
{{- else -}}
JWT_PRIVATE_KEY
{{- end -}}
{{- end }}

{{/*
Returns "true" if the chart-managed Secret should be rendered — i.e. at least
one of encryptionKey / jwtPrivateKey lacks an externally-supplied existingSecret.
*/}}
{{- define "accessflow.secrets.chartManaged" -}}
{{- if or (not .Values.config.encryptionKey.existingSecret) (not .Values.config.jwtPrivateKey.existingSecret) -}}
true
{{- end -}}
{{- end }}

{{/*
Looks up an existing key on the chart-managed Secret so that generated values
(encryption key, JWT private key) are preserved across `helm upgrade` runs.

Usage: {{ include "accessflow.secrets.lookupOrDefault" (dict "ctx" . "key" "ENCRYPTION_KEY" "default" "abcdef") }}

When the Secret + key already exist in the cluster, returns the previously
stored value (still base64-decoded). Otherwise returns the supplied default.
During `helm template` / `--dry-run`, lookup returns an empty map and the
default is used — which means every `helm template` render produces a fresh
value. That's expected: lookup is only meaningful against a live cluster.
*/}}
{{- define "accessflow.secrets.lookupOrDefault" -}}
{{- $ctx := .ctx -}}
{{- $secretName := include "accessflow.secrets.fullname" $ctx -}}
{{- $existing := lookup "v1" "Secret" $ctx.Release.Namespace $secretName -}}
{{- if and $existing $existing.data (hasKey $existing.data .key) -}}
{{- index $existing.data .key | b64dec -}}
{{- else -}}
{{- .default -}}
{{- end -}}
{{- end }}
