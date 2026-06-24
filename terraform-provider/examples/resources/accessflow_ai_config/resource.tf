resource "accessflow_ai_config" "claude" {
  name        = "claude"
  ai_provider = "ANTHROPIC" # `provider` is reserved by Terraform, so the attribute is `ai_provider`
  model       = "claude-sonnet-4-20250514"
  api_key     = var.anthropic_api_key # write-only
}

variable "anthropic_api_key" {
  type      = string
  sensitive = true
}
