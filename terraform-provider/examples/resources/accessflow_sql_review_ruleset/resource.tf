# One ruleset per environment per organization, plus at most one org-wide default
# (no `environment`). A datasource without an environment falls through to the default.
resource "accessflow_sql_review_ruleset" "production" {
  name        = "Production"
  description = "Payroll is off limits; every unbounded read or write goes to a human"
  environment = "PRODUCTION"

  rules = [
    { rule_id = "select_star", severity = "OFF" },
    { rule_id = "missing_limit_on_select", severity = "BLOCK" },
    { rule_id = "missing_where_on_delete", severity = "BLOCK" },
    {
      rule_id  = "protected_table"
      severity = "BLOCK"
      params   = { globs = ["payroll.*", "*.audit_log"] }
    },
    {
      rule_id  = "disallowed_function"
      severity = "BLOCK"
      params   = { names = ["pg_sleep", "dblink"] }
    },
  ]
}

# Bind a datasource to the environment so the ruleset above applies to it.
resource "accessflow_datasource" "prod_postgres" {
  name          = "prod-postgres"
  db_type       = "POSTGRESQL"
  host          = "postgres.prod.internal"
  port          = 5432
  database_name = "app"
  username      = "af_reader"
  password      = var.prod_postgres_password
  ssl_mode      = "REQUIRE"
  environment   = "PRODUCTION"
}

variable "prod_postgres_password" {
  type      = string
  sensitive = true
}
