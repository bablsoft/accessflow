data "accessflow_datasource" "prod" {
  id = "11111111-2222-3333-4444-555555555555"
}

output "prod_db_type" {
  value = data.accessflow_datasource.prod.db_type
}
