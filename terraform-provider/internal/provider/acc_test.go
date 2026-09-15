package provider

import (
	"os"
	"testing"

	"github.com/hashicorp/terraform-plugin-testing/helper/resource"
)

// testAccPreCheck runs before acceptance tests (only when TF_ACC=1). It requires a live
// AccessFlow instance and an admin API key, supplied via the same env vars the provider reads.
func testAccPreCheck(t *testing.T) {
	if os.Getenv("ACCESSFLOW_ENDPOINT") == "" {
		t.Fatal("ACCESSFLOW_ENDPOINT must be set for acceptance tests")
	}
	if os.Getenv("ACCESSFLOW_API_KEY") == "" {
		t.Fatal("ACCESSFLOW_API_KEY must be set for acceptance tests")
	}
}

func TestAccDatasource_basic(t *testing.T) {
	resource.Test(t, resource.TestCase{
		PreCheck:                 func() { testAccPreCheck(t) },
		ProtoV6ProviderFactories: testAccProtoV6ProviderFactories,
		Steps: []resource.TestStep{
			{
				Config: `
resource "accessflow_datasource" "test" {
  name                = "tf-acc-ds"
  db_type             = "POSTGRESQL"
  host                = "db.internal"
  port                = 5432
  database_name       = "app"
  username            = "reader"
  password            = "s3cret"
  ssl_mode            = "DISABLE"
  ai_analysis_enabled = false # no ai_config in the test stack; avoids 422 MissingAiConfigForDatasource
  environment         = "STAGING"
}
`,
				Check: resource.ComposeAggregateTestCheckFunc(
					resource.TestCheckResourceAttrSet("accessflow_datasource.test", "id"),
					resource.TestCheckResourceAttr("accessflow_datasource.test", "name", "tf-acc-ds"),
					resource.TestCheckResourceAttr("accessflow_datasource.test", "db_type", "POSTGRESQL"),
					resource.TestCheckResourceAttr("accessflow_datasource.test", "environment", "STAGING"),
				),
			},
			{
				Config: `
resource "accessflow_datasource" "test" {
  name                = "tf-acc-ds"
  db_type             = "POSTGRESQL"
  host                = "db.internal"
  port                = 5432
  database_name       = "app"
  username            = "reader"
  password            = "s3cret"
  ssl_mode            = "DISABLE"
  ai_analysis_enabled = false
  max_rows_per_query  = 500
  # environment dropped: the update must send clear_environment, not silently keep STAGING
}
`,
				Check: resource.ComposeAggregateTestCheckFunc(
					resource.TestCheckResourceAttr("accessflow_datasource.test", "max_rows_per_query", "500"),
					resource.TestCheckNoResourceAttr("accessflow_datasource.test", "environment"),
				),
			},
			{
				ResourceName:            "accessflow_datasource.test",
				ImportState:             true,
				ImportStateVerify:       true,
				ImportStateVerifyIgnore: []string{"password"}, // write-only, not returned by the API
			},
		},
	})
}

func TestAccReviewPlan_basic(t *testing.T) {
	resource.Test(t, resource.TestCase{
		PreCheck:                 func() { testAccPreCheck(t) },
		ProtoV6ProviderFactories: testAccProtoV6ProviderFactories,
		Steps: []resource.TestStep{
			{
				Config: `
resource "accessflow_review_plan" "test" {
  name                    = "tf-acc-rp"
  requires_ai_review      = false
  requires_human_approval = false # no approvers attached; the API rejects human-approval plans with none
  auto_approve_reads      = true
}
`,
				Check: resource.ComposeAggregateTestCheckFunc(
					resource.TestCheckResourceAttrSet("accessflow_review_plan.test", "id"),
					resource.TestCheckResourceAttr("accessflow_review_plan.test", "name", "tf-acc-rp"),
					resource.TestCheckResourceAttr("accessflow_review_plan.test", "auto_approve_reads", "true"),
				),
			},
			{
				ResourceName:      "accessflow_review_plan.test",
				ImportState:       true,
				ImportStateVerify: true,
			},
		},
	})
}

func TestAccRoutingPolicy_basic(t *testing.T) {
	resource.Test(t, resource.TestCase{
		PreCheck:                 func() { testAccPreCheck(t) },
		ProtoV6ProviderFactories: testAccProtoV6ProviderFactories,
		Steps: []resource.TestStep{
			{
				Config: `
resource "accessflow_routing_policy" "test" {
  name      = "tf-acc-routing"
  priority  = 100
  action    = "AUTO_REJECT"
  reason    = "block deletes"
  condition = jsonencode({ type = "query_type", any_of = ["DELETE"] })
}
`,
				Check: resource.ComposeAggregateTestCheckFunc(
					resource.TestCheckResourceAttrSet("accessflow_routing_policy.test", "id"),
					resource.TestCheckResourceAttr("accessflow_routing_policy.test", "action", "AUTO_REJECT"),
					resource.TestCheckResourceAttr("accessflow_routing_policy.test", "priority", "100"),
				),
			},
			{
				ResourceName:      "accessflow_routing_policy.test",
				ImportState:       true,
				ImportStateVerify: true,
				// The API re-serializes the condition JSON in field order on read but echoes the
				// submitted key order on create, so the raw string isn't byte-stable across import.
				// jsontypes.Normalized handles this for plan/refresh; ImportStateVerify compares
				// literally, so skip it for this opaque JSON blob (all other attrs are verified).
				ImportStateVerifyIgnore: []string{"condition"},
			},
		},
	})
}

func TestAccSqlReviewRuleset_basic(t *testing.T) {
	resource.Test(t, resource.TestCase{
		PreCheck:                 func() { testAccPreCheck(t) },
		ProtoV6ProviderFactories: testAccProtoV6ProviderFactories,
		Steps: []resource.TestStep{
			{
				Config: `
resource "accessflow_sql_review_ruleset" "test" {
  name        = "tf-acc-sqlreview"
  description = "acceptance"
  environment = "TEST"
  rules = [
    { rule_id = "select_star", severity = "BLOCK" },
    { rule_id = "protected_table", severity = "BLOCK", params = { globs = ["payroll.*"] } },
  ]
}
`,
				Check: resource.ComposeAggregateTestCheckFunc(
					resource.TestCheckResourceAttrSet("accessflow_sql_review_ruleset.test", "id"),
					resource.TestCheckResourceAttr("accessflow_sql_review_ruleset.test", "environment", "TEST"),
					resource.TestCheckResourceAttr("accessflow_sql_review_ruleset.test", "enabled", "true"),
					resource.TestCheckResourceAttr("accessflow_sql_review_ruleset.test", "rules.#", "2"),
					resource.TestCheckTypeSetElemNestedAttrs("accessflow_sql_review_ruleset.test", "rules.*", map[string]string{
						"rule_id":        "protected_table",
						"severity":       "BLOCK",
						"params.globs.#": "1",
						"params.globs.0": "payroll.*",
					}),
				),
			},
			{
				Config: `
resource "accessflow_sql_review_ruleset" "test" {
  name        = "tf-acc-sqlreview"
  environment = "TEST"
  enabled     = false
  rules = [
    { rule_id = "select_star", severity = "WARN" },
    { rule_id = "disallowed_function", severity = "BLOCK", params = { names = ["pg_sleep", "dblink"] } },
  ]
}
`,
				Check: resource.ComposeAggregateTestCheckFunc(
					resource.TestCheckResourceAttr("accessflow_sql_review_ruleset.test", "enabled", "false"),
					resource.TestCheckNoResourceAttr("accessflow_sql_review_ruleset.test", "description"),
					resource.TestCheckResourceAttr("accessflow_sql_review_ruleset.test", "rules.#", "2"),
					resource.TestCheckTypeSetElemNestedAttrs("accessflow_sql_review_ruleset.test", "rules.*", map[string]string{
						"rule_id":  "select_star",
						"severity": "WARN",
					}),
					resource.TestCheckTypeSetElemNestedAttrs("accessflow_sql_review_ruleset.test", "rules.*", map[string]string{
						"rule_id":        "disallowed_function",
						"params.names.#": "2",
					}),
				),
			},
			{
				ResourceName:      "accessflow_sql_review_ruleset.test",
				ImportState:       true,
				ImportStateVerify: true,
			},
		},
	})
}
