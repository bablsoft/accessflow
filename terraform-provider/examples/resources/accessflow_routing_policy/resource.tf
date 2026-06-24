resource "accessflow_routing_policy" "block_deletes" {
  name     = "block-deletes-off-hours"
  priority = 100
  action   = "AUTO_REJECT"
  reason   = "DELETE statements are never auto-approved"

  # The typed condition tree, as JSON.
  condition = jsonencode({
    type   = "query_type"
    any_of = ["DELETE"]
  })
}

resource "accessflow_routing_policy" "escalate_high_risk" {
  name               = "escalate-high-risk"
  priority           = 200
  action             = "REQUIRE_APPROVALS"
  required_approvals = 2
  condition = jsonencode({
    type     = "risk_level"
    any_of   = ["HIGH", "CRITICAL"]
  })
}
