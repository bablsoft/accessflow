{{/*
Helpers that render the bootstrap.* section of values.yaml as Spring-relaxed-binding
env vars (ACCESSFLOW_BOOTSTRAP_*). Splits non-secret keys into the ConfigMap and
*SecretRef values into the Deployment's env: list with valueFrom.secretKeyRef.
*/}}

{{/*
accessflow.bootstrap.validate
Fails the install/template/upgrade with a clear message when bootstrap.enabled=true
but a required field is empty. Mirrors the backend-side checks in BootstrapRunner
so operators see misconfig at helm time rather than during pod CrashLoopBackOff.
*/}}
{{- define "accessflow.bootstrap.validate" -}}
{{- if .Values.bootstrap.enabled -}}
  {{- if not .Values.bootstrap.organization.name -}}
    {{- fail "bootstrap.enabled=true requires bootstrap.organization.name" -}}
  {{- end -}}
  {{- if not .Values.bootstrap.admin.email -}}
    {{- fail "bootstrap.enabled=true requires bootstrap.admin.email" -}}
  {{- end -}}
  {{- if not .Values.bootstrap.admin.displayName -}}
    {{- fail "bootstrap.enabled=true requires bootstrap.admin.displayName" -}}
  {{- end -}}
  {{- if not .Values.bootstrap.admin.passwordSecretRef.name -}}
    {{- fail "bootstrap.enabled=true requires bootstrap.admin.passwordSecretRef.name" -}}
  {{- end -}}
  {{- if not .Values.bootstrap.admin.passwordSecretRef.key -}}
    {{- fail "bootstrap.enabled=true requires bootstrap.admin.passwordSecretRef.key" -}}
  {{- end -}}
  {{- range $i, $ds := .Values.bootstrap.datasources -}}
    {{- if eq (upper (toString $ds.dbType)) "CUSTOM" -}}
      {{- fail (printf "bootstrap.datasources[%d] uses dbType=CUSTOM; upload CUSTOM JDBC drivers via the admin API instead" $i) -}}
    {{- end -}}
    {{- if not $ds.passwordSecretRef -}}
      {{- fail (printf "bootstrap.datasources[%d] is missing passwordSecretRef" $i) -}}
    {{- end -}}
    {{- if or (not $ds.passwordSecretRef.name) (not $ds.passwordSecretRef.key) -}}
      {{- fail (printf "bootstrap.datasources[%d].passwordSecretRef requires both name and key" $i) -}}
    {{- end -}}
  {{- end -}}
  {{- range $i, $ai := .Values.bootstrap.aiConfigs -}}
    {{- if not $ai.apiKeySecretRef -}}
      {{- if ne (upper (toString $ai.provider)) "OLLAMA" -}}
        {{- fail (printf "bootstrap.aiConfigs[%d] is missing apiKeySecretRef" $i) -}}
      {{- end -}}
    {{- else if or (not $ai.apiKeySecretRef.name) (not $ai.apiKeySecretRef.key) -}}
      {{- fail (printf "bootstrap.aiConfigs[%d].apiKeySecretRef requires both name and key" $i) -}}
    {{- end -}}
  {{- end -}}
  {{- range $i, $oa := .Values.bootstrap.oauth2 -}}
    {{- if not $oa.clientSecretRef -}}
      {{- fail (printf "bootstrap.oauth2[%d] is missing clientSecretRef" $i) -}}
    {{- end -}}
    {{- if or (not $oa.clientSecretRef.name) (not $oa.clientSecretRef.key) -}}
      {{- fail (printf "bootstrap.oauth2[%d].clientSecretRef requires both name and key" $i) -}}
    {{- end -}}
  {{- end -}}
  {{- if .Values.bootstrap.systemSmtp.enabled -}}
    {{- if or (not .Values.bootstrap.systemSmtp.passwordSecretRef.name) (not .Values.bootstrap.systemSmtp.passwordSecretRef.key) -}}
      {{- fail "bootstrap.systemSmtp.enabled=true requires bootstrap.systemSmtp.passwordSecretRef" -}}
    {{- end -}}
  {{- end -}}
{{- end -}}
{{- end -}}

{{/*
accessflow.bootstrap.configmap
Emits flat KEY: "value" lines for every non-secret bootstrap field. Designed
to be embedded directly under `data:` in backend-configmap.yaml. Empty / null
values are skipped so the resulting ConfigMap never carries placeholder keys.
*/}}
{{- define "accessflow.bootstrap.configmap" -}}
{{- include "accessflow.bootstrap.validate" . -}}
{{- if .Values.bootstrap.enabled }}
ACCESSFLOW_BOOTSTRAP_ENABLED: "true"
ACCESSFLOW_BOOTSTRAP_ORGANIZATION_NAME: {{ .Values.bootstrap.organization.name | quote }}
{{- with .Values.bootstrap.organization.slug }}
ACCESSFLOW_BOOTSTRAP_ORGANIZATION_SLUG: {{ . | quote }}
{{- end }}
ACCESSFLOW_BOOTSTRAP_ADMIN_EMAIL: {{ .Values.bootstrap.admin.email | quote }}
ACCESSFLOW_BOOTSTRAP_ADMIN_DISPLAY_NAME: {{ .Values.bootstrap.admin.displayName | quote }}
{{- range $i, $rp := .Values.bootstrap.reviewPlans }}
ACCESSFLOW_BOOTSTRAP_REVIEW_PLANS_{{ $i }}_NAME: {{ $rp.name | quote }}
{{- with $rp.description }}
ACCESSFLOW_BOOTSTRAP_REVIEW_PLANS_{{ $i }}_DESCRIPTION: {{ . | quote }}
{{- end }}
{{- if hasKey $rp "requiresAiReview" }}
ACCESSFLOW_BOOTSTRAP_REVIEW_PLANS_{{ $i }}_REQUIRES_AI_REVIEW: {{ $rp.requiresAiReview | quote }}
{{- end }}
{{- if hasKey $rp "requiresHumanApproval" }}
ACCESSFLOW_BOOTSTRAP_REVIEW_PLANS_{{ $i }}_REQUIRES_HUMAN_APPROVAL: {{ $rp.requiresHumanApproval | quote }}
{{- end }}
{{- if hasKey $rp "minApprovalsRequired" }}
ACCESSFLOW_BOOTSTRAP_REVIEW_PLANS_{{ $i }}_MIN_APPROVALS_REQUIRED: {{ $rp.minApprovalsRequired | quote }}
{{- end }}
{{- if hasKey $rp "approvalTimeoutHours" }}
ACCESSFLOW_BOOTSTRAP_REVIEW_PLANS_{{ $i }}_APPROVAL_TIMEOUT_HOURS: {{ $rp.approvalTimeoutHours | quote }}
{{- end }}
{{- if hasKey $rp "autoApproveReads" }}
ACCESSFLOW_BOOTSTRAP_REVIEW_PLANS_{{ $i }}_AUTO_APPROVE_READS: {{ $rp.autoApproveReads | quote }}
{{- end }}
{{- range $j, $cn := $rp.notifyChannelNames }}
ACCESSFLOW_BOOTSTRAP_REVIEW_PLANS_{{ $i }}_NOTIFY_CHANNEL_NAMES_{{ $j }}: {{ $cn | quote }}
{{- end }}
{{- range $j, $email := $rp.approverEmails }}
ACCESSFLOW_BOOTSTRAP_REVIEW_PLANS_{{ $i }}_APPROVER_EMAILS_{{ $j }}: {{ $email | quote }}
{{- end }}
{{- end }}
{{- range $i, $ai := .Values.bootstrap.aiConfigs }}
ACCESSFLOW_BOOTSTRAP_AI_CONFIGS_{{ $i }}_NAME: {{ $ai.name | quote }}
ACCESSFLOW_BOOTSTRAP_AI_CONFIGS_{{ $i }}_PROVIDER: {{ $ai.provider | quote }}
ACCESSFLOW_BOOTSTRAP_AI_CONFIGS_{{ $i }}_MODEL: {{ $ai.model | quote }}
{{- with $ai.endpoint }}
ACCESSFLOW_BOOTSTRAP_AI_CONFIGS_{{ $i }}_ENDPOINT: {{ . | quote }}
{{- end }}
{{- with $ai.timeoutMs }}
ACCESSFLOW_BOOTSTRAP_AI_CONFIGS_{{ $i }}_TIMEOUT_MS: {{ . | quote }}
{{- end }}
{{- with $ai.maxPromptTokens }}
ACCESSFLOW_BOOTSTRAP_AI_CONFIGS_{{ $i }}_MAX_PROMPT_TOKENS: {{ . | quote }}
{{- end }}
{{- with $ai.maxCompletionTokens }}
ACCESSFLOW_BOOTSTRAP_AI_CONFIGS_{{ $i }}_MAX_COMPLETION_TOKENS: {{ . | quote }}
{{- end }}
{{- end }}
{{- range $i, $ds := .Values.bootstrap.datasources }}
ACCESSFLOW_BOOTSTRAP_DATASOURCES_{{ $i }}_NAME: {{ $ds.name | quote }}
ACCESSFLOW_BOOTSTRAP_DATASOURCES_{{ $i }}_DB_TYPE: {{ $ds.dbType | quote }}
ACCESSFLOW_BOOTSTRAP_DATASOURCES_{{ $i }}_HOST: {{ $ds.host | quote }}
ACCESSFLOW_BOOTSTRAP_DATASOURCES_{{ $i }}_PORT: {{ $ds.port | quote }}
ACCESSFLOW_BOOTSTRAP_DATASOURCES_{{ $i }}_DATABASE_NAME: {{ $ds.databaseName | quote }}
ACCESSFLOW_BOOTSTRAP_DATASOURCES_{{ $i }}_USERNAME: {{ $ds.username | quote }}
{{- with $ds.sslMode }}
ACCESSFLOW_BOOTSTRAP_DATASOURCES_{{ $i }}_SSL_MODE: {{ . | quote }}
{{- end }}
{{- with $ds.connectionPoolSize }}
ACCESSFLOW_BOOTSTRAP_DATASOURCES_{{ $i }}_CONNECTION_POOL_SIZE: {{ . | quote }}
{{- end }}
{{- with $ds.maxRowsPerQuery }}
ACCESSFLOW_BOOTSTRAP_DATASOURCES_{{ $i }}_MAX_ROWS_PER_QUERY: {{ . | quote }}
{{- end }}
{{- if hasKey $ds "requireReviewReads" }}
ACCESSFLOW_BOOTSTRAP_DATASOURCES_{{ $i }}_REQUIRE_REVIEW_READS: {{ $ds.requireReviewReads | quote }}
{{- end }}
{{- if hasKey $ds "requireReviewWrites" }}
ACCESSFLOW_BOOTSTRAP_DATASOURCES_{{ $i }}_REQUIRE_REVIEW_WRITES: {{ $ds.requireReviewWrites | quote }}
{{- end }}
{{- with $ds.reviewPlanName }}
ACCESSFLOW_BOOTSTRAP_DATASOURCES_{{ $i }}_REVIEW_PLAN_NAME: {{ . | quote }}
{{- end }}
{{- if hasKey $ds "aiAnalysisEnabled" }}
ACCESSFLOW_BOOTSTRAP_DATASOURCES_{{ $i }}_AI_ANALYSIS_ENABLED: {{ $ds.aiAnalysisEnabled | quote }}
{{- end }}
{{- with $ds.aiConfigName }}
ACCESSFLOW_BOOTSTRAP_DATASOURCES_{{ $i }}_AI_CONFIG_NAME: {{ . | quote }}
{{- end }}
{{- with $ds.jdbcUrlOverride }}
ACCESSFLOW_BOOTSTRAP_DATASOURCES_{{ $i }}_JDBC_URL_OVERRIDE: {{ . | quote }}
{{- end }}
{{- end }}
{{- range $i, $ch := .Values.bootstrap.notificationChannels }}
ACCESSFLOW_BOOTSTRAP_NOTIFICATION_CHANNELS_{{ $i }}_NAME: {{ $ch.name | quote }}
ACCESSFLOW_BOOTSTRAP_NOTIFICATION_CHANNELS_{{ $i }}_CHANNEL_TYPE: {{ $ch.channelType | quote }}
{{- if hasKey $ch "active" }}
ACCESSFLOW_BOOTSTRAP_NOTIFICATION_CHANNELS_{{ $i }}_ACTIVE: {{ $ch.active | quote }}
{{- end }}
{{- range $k, $v := $ch.config }}
ACCESSFLOW_BOOTSTRAP_NOTIFICATION_CHANNELS_{{ $i }}_CONFIG_{{ upper (snakecase $k) }}: {{ $v | quote }}
{{- end }}
{{- end }}
{{- if .Values.bootstrap.saml.enabled }}
ACCESSFLOW_BOOTSTRAP_SAML_ENABLED: "true"
ACCESSFLOW_BOOTSTRAP_SAML_IDP_METADATA_URL: {{ .Values.bootstrap.saml.idpMetadataUrl | quote }}
ACCESSFLOW_BOOTSTRAP_SAML_IDP_ENTITY_ID: {{ .Values.bootstrap.saml.idpEntityId | quote }}
ACCESSFLOW_BOOTSTRAP_SAML_SP_ENTITY_ID: {{ .Values.bootstrap.saml.spEntityId | quote }}
ACCESSFLOW_BOOTSTRAP_SAML_ACS_URL: {{ .Values.bootstrap.saml.acsUrl | quote }}
{{- with .Values.bootstrap.saml.sloUrl }}
ACCESSFLOW_BOOTSTRAP_SAML_SLO_URL: {{ . | quote }}
{{- end }}
{{- with .Values.bootstrap.saml.signingCertPem }}
ACCESSFLOW_BOOTSTRAP_SAML_SIGNING_CERT_PEM: {{ . | quote }}
{{- end }}
{{- with .Values.bootstrap.saml.attrEmail }}
ACCESSFLOW_BOOTSTRAP_SAML_ATTR_EMAIL: {{ . | quote }}
{{- end }}
{{- with .Values.bootstrap.saml.attrDisplayName }}
ACCESSFLOW_BOOTSTRAP_SAML_ATTR_DISPLAY_NAME: {{ . | quote }}
{{- end }}
{{- with .Values.bootstrap.saml.attrRole }}
ACCESSFLOW_BOOTSTRAP_SAML_ATTR_ROLE: {{ . | quote }}
{{- end }}
{{- with .Values.bootstrap.saml.defaultRole }}
ACCESSFLOW_BOOTSTRAP_SAML_DEFAULT_ROLE: {{ . | quote }}
{{- end }}
{{- if hasKey .Values.bootstrap.saml "active" }}
ACCESSFLOW_BOOTSTRAP_SAML_ACTIVE: {{ .Values.bootstrap.saml.active | quote }}
{{- end }}
{{- end }}
{{- range $i, $oa := .Values.bootstrap.oauth2 }}
ACCESSFLOW_BOOTSTRAP_OAUTH2_{{ $i }}_PROVIDER: {{ $oa.provider | quote }}
ACCESSFLOW_BOOTSTRAP_OAUTH2_{{ $i }}_CLIENT_ID: {{ $oa.clientId | quote }}
{{- with $oa.scopesOverride }}
ACCESSFLOW_BOOTSTRAP_OAUTH2_{{ $i }}_SCOPES_OVERRIDE: {{ . | quote }}
{{- end }}
{{- with $oa.tenantId }}
ACCESSFLOW_BOOTSTRAP_OAUTH2_{{ $i }}_TENANT_ID: {{ . | quote }}
{{- end }}
{{- with $oa.defaultRole }}
ACCESSFLOW_BOOTSTRAP_OAUTH2_{{ $i }}_DEFAULT_ROLE: {{ . | quote }}
{{- end }}
{{- if hasKey $oa "active" }}
ACCESSFLOW_BOOTSTRAP_OAUTH2_{{ $i }}_ACTIVE: {{ $oa.active | quote }}
{{- end }}
{{- end }}
{{- if .Values.bootstrap.systemSmtp.enabled }}
ACCESSFLOW_BOOTSTRAP_SYSTEM_SMTP_ENABLED: "true"
ACCESSFLOW_BOOTSTRAP_SYSTEM_SMTP_HOST: {{ .Values.bootstrap.systemSmtp.host | quote }}
ACCESSFLOW_BOOTSTRAP_SYSTEM_SMTP_PORT: {{ .Values.bootstrap.systemSmtp.port | quote }}
{{- with .Values.bootstrap.systemSmtp.username }}
ACCESSFLOW_BOOTSTRAP_SYSTEM_SMTP_USERNAME: {{ . | quote }}
{{- end }}
{{- if hasKey .Values.bootstrap.systemSmtp "tls" }}
ACCESSFLOW_BOOTSTRAP_SYSTEM_SMTP_TLS: {{ .Values.bootstrap.systemSmtp.tls | quote }}
{{- end }}
ACCESSFLOW_BOOTSTRAP_SYSTEM_SMTP_FROM_ADDRESS: {{ .Values.bootstrap.systemSmtp.fromAddress | quote }}
{{- with .Values.bootstrap.systemSmtp.fromName }}
ACCESSFLOW_BOOTSTRAP_SYSTEM_SMTP_FROM_NAME: {{ . | quote }}
{{- end }}
{{- end }}
{{- end }}
{{- end -}}

{{/*
accessflow.bootstrap.envSecrets
Emits the `- name: ... valueFrom.secretKeyRef: ...` env entries for every *SecretRef
in bootstrap.* . Designed to be appended to the backend Deployment's container `env:`
list. Renders nothing when bootstrap.enabled=false.
*/}}
{{- define "accessflow.bootstrap.envSecrets" -}}
{{- if .Values.bootstrap.enabled }}
- name: ACCESSFLOW_BOOTSTRAP_ADMIN_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ .Values.bootstrap.admin.passwordSecretRef.name | quote }}
      key: {{ .Values.bootstrap.admin.passwordSecretRef.key | quote }}
{{- range $i, $ds := .Values.bootstrap.datasources }}
- name: ACCESSFLOW_BOOTSTRAP_DATASOURCES_{{ $i }}_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ $ds.passwordSecretRef.name | quote }}
      key: {{ $ds.passwordSecretRef.key | quote }}
{{- end }}
{{- range $i, $ai := .Values.bootstrap.aiConfigs }}
{{- if $ai.apiKeySecretRef }}
{{- if $ai.apiKeySecretRef.name }}
- name: ACCESSFLOW_BOOTSTRAP_AI_CONFIGS_{{ $i }}_API_KEY
  valueFrom:
    secretKeyRef:
      name: {{ $ai.apiKeySecretRef.name | quote }}
      key: {{ $ai.apiKeySecretRef.key | quote }}
{{- end }}
{{- end }}
{{- end }}
{{- range $i, $ch := .Values.bootstrap.notificationChannels }}
{{- range $field, $ref := (default dict $ch.sensitiveSecretRefs) }}
- name: ACCESSFLOW_BOOTSTRAP_NOTIFICATION_CHANNELS_{{ $i }}_CONFIG_{{ upper (snakecase $field) }}
  valueFrom:
    secretKeyRef:
      name: {{ $ref.name | quote }}
      key: {{ $ref.key | quote }}
{{- end }}
{{- end }}
{{- range $i, $oa := .Values.bootstrap.oauth2 }}
- name: ACCESSFLOW_BOOTSTRAP_OAUTH2_{{ $i }}_CLIENT_SECRET
  valueFrom:
    secretKeyRef:
      name: {{ $oa.clientSecretRef.name | quote }}
      key: {{ $oa.clientSecretRef.key | quote }}
{{- end }}
{{- if .Values.bootstrap.systemSmtp.enabled }}
- name: ACCESSFLOW_BOOTSTRAP_SYSTEM_SMTP_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ .Values.bootstrap.systemSmtp.passwordSecretRef.name | quote }}
      key: {{ .Values.bootstrap.systemSmtp.passwordSecretRef.key | quote }}
{{- end }}
{{- end }}
{{- end -}}
