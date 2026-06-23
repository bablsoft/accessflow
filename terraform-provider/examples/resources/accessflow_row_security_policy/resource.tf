resource "accessflow_row_security_policy" "tenant_isolation" {
  datasource_id    = accessflow_datasource.prod_postgres.id
  table_name       = "orders"
  column_name      = "tenant_id"
  operator         = "EQUALS"
  value_type       = "VARIABLE"
  value_expression = ":user.tenant_id"
  applies_to_roles = ["ANALYST", "READONLY"]
}
