resource "accessflow_datasource" "prod_postgres" {
  name          = "prod-postgres"
  db_type       = "POSTGRESQL"
  host          = "postgres.prod.internal"
  port          = 5432
  database_name = "app"
  username      = "af_reader"
  password      = var.prod_postgres_password # write-only
  ssl_mode      = "REQUIRE"

  require_review_writes = true
  ai_analysis_enabled   = true
  review_plan_id        = accessflow_review_plan.standard.id
}

variable "prod_postgres_password" {
  type      = string
  sensitive = true
}
