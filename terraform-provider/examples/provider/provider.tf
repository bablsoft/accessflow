terraform {
  required_providers {
    accessflow = {
      source = "bablsoft/accessflow"
    }
  }
}

# Endpoint and API key may also be supplied via the ACCESSFLOW_ENDPOINT and
# ACCESSFLOW_API_KEY environment variables (recommended for CI).
provider "accessflow" {
  endpoint = "https://accessflow.example.com"
  api_key  = var.accessflow_api_key # the af_-prefixed token, e.g. from a bootstrap service account
}

variable "accessflow_api_key" {
  type      = string
  sensitive = true
}
