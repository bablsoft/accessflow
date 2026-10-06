# A custom rule: block any UPDATE on the billing schema that has no WHERE clause.
resource "accessflow_sql_review_rule" "no_unbounded_billing_update" {
  rule_id          = "custom_no_unbounded_billing_update"
  name             = "No unbounded billing updates"
  description      = "Every UPDATE on the billing schema needs a WHERE clause"
  message          = "{statement_type} on {tables} has no WHERE clause"
  category         = "STATEMENT_SAFETY"
  default_severity = "BLOCK"

  condition = jsonencode({
    type = "and"
    children = [
      { type = "query_type", any_of = ["UPDATE"] },
      { type = "referenced_table", globs = ["billing.*"] },
      { type = "has_where", expected = false },
    ]
  })
}

# Configure it in a ruleset by its rule_id, exactly like a built-in rule.
resource "accessflow_sql_review_ruleset" "production" {
  name        = "Production"
  environment = "PRODUCTION"

  rules = [
    { rule_id = accessflow_sql_review_rule.no_unbounded_billing_update.rule_id, severity = "BLOCK" },
  ]
}
