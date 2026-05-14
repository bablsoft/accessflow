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

{{- define "accessflow.db.passwordSecret" -}}
{{- if .Values.postgresql.enabled -}}
{{ required "postgresql.auth.existingSecret is required" .Values.postgresql.auth.existingSecret }}
{{- else -}}
{{ required "externalDatabase.existingSecret is required when postgresql.enabled=false" .Values.externalDatabase.existingSecret }}
{{- end -}}
{{- end }}

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
