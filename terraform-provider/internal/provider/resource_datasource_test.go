package provider

import (
	"testing"

	"github.com/hashicorp/terraform-plugin-framework/types"
)

func TestClearEnvironmentFlag(t *testing.T) {
	cases := []struct {
		name    string
		prior   types.String
		planned types.String
		want    bool
	}{
		{"set to unset clears", types.StringValue("PRODUCTION"), types.StringNull(), true},
		{"unset stays unset", types.StringNull(), types.StringNull(), false},
		{"set stays set", types.StringValue("PRODUCTION"), types.StringValue("STAGING"), false},
		{"unset to set", types.StringNull(), types.StringValue("TEST"), false},
		{"unknown prior never clears", types.StringUnknown(), types.StringNull(), false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := clearEnvironmentFlag(tc.prior, tc.planned)
			if tc.want && (got == nil || !*got) {
				t.Errorf("expected clear_environment=true, got %v", got)
			}
			if !tc.want && got != nil {
				t.Errorf("expected clear_environment omitted, got %v", *got)
			}
		})
	}
}

func TestDatasourceModel_EnvironmentRoundTrip(t *testing.T) {
	m := datasourceResourceModel{Name: types.StringValue("ds"), Environment: types.StringValue("STAGING")}
	req := m.toRequest()
	if req.Environment == nil || *req.Environment != "STAGING" {
		t.Errorf("environment not sent: %+v", req)
	}
	if req.ClearEnvironment != nil {
		t.Errorf("toRequest must never set clear_environment on its own")
	}
	unset := datasourceResourceModel{Name: types.StringValue("ds")}
	if unset.toRequest().Environment != nil {
		t.Error("null environment must be omitted")
	}
}
