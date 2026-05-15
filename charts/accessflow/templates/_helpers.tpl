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
  - If postgresql.auth.existingSecret is set, use it as-is.
  - Otherwise fall back to the Secret the bitnami subchart auto-generates and
    manages internally, which is named "{release}-postgresql".

When postgresql.enabled=false (externalDatabase mode):
  - externalDatabase.existingSecret is required.
*/}}
{{- define "accessflow.db.passwordSecret" -}}
{{- if .Values.postgresql.enabled -}}
{{- if .Values.postgresql.auth.existingSecret -}}
{{ .Values.postgresql.auth.existingSecret }}
{{- else -}}
{{ printf "%s-postgresql" .Release.Name }}
{{- end -}}
{{- else -}}
{{ required "externalDatabase.existingSecret is required when postgresql.enabled=false" .Values.externalDatabase.existingSecret }}
{{- end -}}
{{- end }}

{{/*
PostgreSQL password Secret key. The bitnami chart stores the custom-user
password under the key "password" by default — both for the auto-generated
secret and for any existingSecret passed in.
*/}}
{{- define "accessflow.db.passwordSecretKey" -}}
{{- if .Values.postgresql.enabled -}}
password
{{- else -}}
{{ default "password" .Values.externalDatabase.existingSecretPasswordKey }}
{{- end -}}
{{- end }}

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
