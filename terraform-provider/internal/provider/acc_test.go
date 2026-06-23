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
  name          = "tf-acc-ds"
  db_type       = "POSTGRESQL"
  host          = "db.internal"
  port          = 5432
  database_name = "app"
  username      = "reader"
  password      = "s3cret"
  ssl_mode      = "DISABLE"
}
`,
				Check: resource.ComposeAggregateTestCheckFunc(
					resource.TestCheckResourceAttrSet("accessflow_datasource.test", "id"),
					resource.TestCheckResourceAttr("accessflow_datasource.test", "name", "tf-acc-ds"),
					resource.TestCheckResourceAttr("accessflow_datasource.test", "db_type", "POSTGRESQL"),
				),
			},
			{
				Config: `
resource "accessflow_datasource" "test" {
  name               = "tf-acc-ds"
  db_type            = "POSTGRESQL"
  host               = "db.internal"
  port               = 5432
  database_name      = "app"
  username           = "reader"
  password           = "s3cret"
  ssl_mode           = "DISABLE"
  max_rows_per_query = 500
}
`,
				Check: resource.TestCheckResourceAttr("accessflow_datasource.test", "max_rows_per_query", "500"),
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
  requires_human_approval = true
  min_approvals_required  = 1
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
			},
		},
	})
}
