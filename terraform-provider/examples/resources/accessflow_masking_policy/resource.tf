resource "accessflow_masking_policy" "mask_email" {
  datasource_id = accessflow_datasource.prod_postgres.id
  column_ref    = "public.users.email"
  strategy      = "EMAIL"
  reveal_to_roles = ["ADMIN"]
}

resource "accessflow_masking_policy" "mask_ssn" {
  datasource_id = accessflow_datasource.prod_postgres.id
  column_ref    = "public.users.ssn"
  strategy      = "PARTIAL"
  strategy_params = {
    visible_suffix = "4"
  }
}
