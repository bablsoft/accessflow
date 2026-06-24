resource "accessflow_notification_channel" "ops_slack" {
  name         = "ops-slack"
  channel_type = "SLACK"
  config = {
    webhook_url = var.slack_webhook_url
    channel_id  = "#ops"
  }
}

variable "slack_webhook_url" {
  type      = string
  sensitive = true
}
