package provider

import (
	"context"
	"testing"

	"github.com/bablsoft/terraform-provider-accessflow/internal/client"
	"github.com/hashicorp/terraform-plugin-framework/attr"
	"github.com/hashicorp/terraform-plugin-framework/diag"
	"github.com/hashicorp/terraform-plugin-framework/types"
)

func paramsMap(t *testing.T, values map[string][]string) types.Map {
	t.Helper()
	m, d := types.MapValueFrom(context.Background(), sqlReviewRuleParamsType, values)
	if d.HasError() {
		t.Fatalf("building params: %v", d)
	}
	return m
}

func TestSqlReviewRulesetModel_ToRequest(t *testing.T) {
	var diags diag.Diagnostics
	m := sqlReviewRulesetResourceModel{
		Name:        types.StringValue("Production"),
		Environment: types.StringValue("PRODUCTION"),
		Rules: []sqlReviewRuleModel{
			{RuleID: types.StringValue("select_star"), Severity: types.StringValue("OFF"), Params: types.MapNull(sqlReviewRuleParamsType)},
			{RuleID: types.StringValue("protected_table"), Severity: types.StringValue("BLOCK"),
				Params: paramsMap(t, map[string][]string{"globs": {"payroll.*", "*.audit_log"}})},
		},
	}
	req := m.toRequest(context.Background(), &diags)
	if diags.HasError() {
		t.Fatalf("diags: %v", diags)
	}
	if req.Name == nil || *req.Name != "Production" || req.Description != nil || req.Enabled != nil {
		t.Errorf("scalars: %+v", req)
	}
	if len(req.Rules) != 2 {
		t.Fatalf("rules: %+v", req.Rules)
	}
	if req.Rules[0].Params != nil {
		t.Errorf("null params must stay nil: %+v", req.Rules[0])
	}
	if got := req.Rules[1].Params["globs"]; len(got) != 2 || got[1] != "*.audit_log" {
		t.Errorf("params: %+v", req.Rules[1].Params)
	}
	empty := sqlReviewRulesetResourceModel{Name: types.StringValue("Default")}
	if r := empty.toRequest(context.Background(), &diags); r.Rules == nil || len(r.Rules) != 0 {
		t.Errorf("no rules must serialise as an empty list, not be omitted: %+v", r.Rules)
	}
}

func TestSqlReviewRulesetModel_ApplyAPI(t *testing.T) {
	var diags diag.Diagnostics
	desc := "payroll is off limits"
	api := &client.SqlReviewRuleset{
		ID: "rs-1", OrganizationID: "org-1", Name: "Production", Description: &desc, Enabled: true,
		Rules: []client.SqlReviewRuleConfig{
			{RuleID: "disallowed_function", Severity: "BLOCK", Params: map[string][]string{"names": {"pg_sleep"}}},
			{RuleID: "missing_limit_on_select", Severity: "BLOCK", Params: map[string][]string{}},
			{RuleID: "select_star", Severity: "OFF", Params: map[string][]string{}},
		},
	}
	// select_star was configured with an explicit empty map; missing_limit_on_select was not.
	m := sqlReviewRulesetResourceModel{Rules: []sqlReviewRuleModel{
		{RuleID: types.StringValue("select_star"), Severity: types.StringValue("OFF"),
			Params: types.MapValueMust(sqlReviewRuleParamsType, map[string]attr.Value{})},
	}}
	m.applyAPI(context.Background(), api, &diags)
	if diags.HasError() {
		t.Fatalf("diags: %v", diags)
	}
	if m.ID.ValueString() != "rs-1" || m.Description.ValueString() != desc || !m.Environment.IsNull() || !m.Enabled.ValueBool() {
		t.Errorf("scalars: %+v", m)
	}
	byID := map[string]sqlReviewRuleModel{}
	for _, r := range m.Rules {
		byID[r.RuleID.ValueString()] = r
	}
	if len(byID) != 3 {
		t.Fatalf("rules: %+v", m.Rules)
	}
	if byID["disallowed_function"].Params.IsNull() {
		t.Error("populated params must be kept")
	}
	if !byID["missing_limit_on_select"].Params.IsNull() {
		t.Error("an empty API params object must map to null when not configured")
	}
	if p := byID["select_star"].Params; p.IsNull() || len(p.Elements()) != 0 {
		t.Errorf("a configured empty map must be preserved, got %v", p)
	}

	none := &client.SqlReviewRuleset{ID: "rs-2", OrganizationID: "org-1", Name: "Default"}
	m.applyAPI(context.Background(), none, &diags)
	if m.Rules == nil || len(m.Rules) != 0 {
		t.Errorf("a configured rule set that the API now reports empty must stay an empty set, got %+v", m.Rules)
	}

	omitted := sqlReviewRulesetResourceModel{}
	omitted.applyAPI(context.Background(), none, &diags)
	if omitted.Rules != nil {
		t.Errorf("no rules with no configured set must map to nil (null), got %+v", omitted.Rules)
	}
	empty := sqlReviewRulesetResourceModel{Rules: []sqlReviewRuleModel{}}
	empty.applyAPI(context.Background(), none, &diags)
	if empty.Rules == nil || len(empty.Rules) != 0 {
		t.Errorf("rules = [] must round-trip as an empty set, got %+v", empty.Rules)
	}
}
