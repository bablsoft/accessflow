# An organization-wide decision hook: consulted for every query that no routing policy matched,
# just before the grant fast path. It can allow, escalate, require more approvals or reject —
# never approve — and a timeout, error or bad signature sends the query to human review.
resource "accessflow_decision_hook" "opa" {
  name         = "OPA production gate"
  endpoint_url = "https://opa.internal.example.com/v1/data/accessflow/decision"
  secret       = var.decision_hook_secret
  timeout_ms   = 2000
}

# A datasource-specific hook replaces the organization default for that datasource. Sending the
# SQL text is an explicit opt-in: it can contain literal values.
resource "accessflow_decision_hook" "payments" {
  name          = "Payments policy service"
  datasource_id = accessflow_datasource.payments.id
  endpoint_url  = "https://policy.payments.example.com/decide"
  secret        = var.payments_hook_secret
  include_sql   = true
}

variable "decision_hook_secret" {
  type      = string
  sensitive = true
}

variable "payments_hook_secret" {
  type      = string
  sensitive = true
}
