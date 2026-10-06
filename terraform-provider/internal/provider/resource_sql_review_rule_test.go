package provider

import (
	"encoding/json"
	"testing"

	"github.com/bablsoft/terraform-provider-accessflow/internal/client"
	"github.com/hashicorp/terraform-plugin-framework-jsontypes/jsontypes"
	"github.com/hashicorp/terraform-plugin-framework/types"
)

func TestSqlReviewRuleModel_ToRequest(t *testing.T) {
	m := sqlReviewRuleResourceModel{
		RuleID:          types.StringValue("custom_no_dblink"),
		Name:            types.StringValue("No dblink"),
		Description:     types.StringNull(),
		Message:         types.StringValue("dblink on {tables}"),
		Category:        types.StringValue("STATEMENT_SAFETY"),
		DefaultSeverity: types.StringValue("BLOCK"),
		Enabled:         types.BoolUnknown(),
		Condition:       jsontypes.NewNormalizedValue(`{"type":"function_called","names":["dblink"]}`),
	}
	req := m.toRequest()
	if req.RuleID == nil || *req.RuleID != "custom_no_dblink" || *req.DefaultSeverity != "BLOCK" || *req.Category != "STATEMENT_SAFETY" {
		t.Errorf("scalars: %+v", req)
	}
	if req.Description != nil || req.Enabled != nil {
		t.Errorf("null/unknown optionals must be omitted: %+v", req)
	}
	var cond map[string]any
	if err := json.Unmarshal(req.Condition, &cond); err != nil || cond["type"] != "function_called" {
		t.Errorf("condition: %s (%v)", req.Condition, err)
	}
	noCondition := sqlReviewRuleResourceModel{Condition: jsontypes.NewNormalizedUnknown()}
	if r := noCondition.toRequest(); r.Condition != nil {
		t.Errorf("unknown condition must not be sent: %s", r.Condition)
	}
}

func TestSqlReviewRuleModel_ApplyAPI(t *testing.T) {
	desc := "no cross-database calls"
	var m sqlReviewRuleResourceModel
	m.applyAPI(&client.SqlReviewRule{
		ID: "r-1", OrganizationID: "org-1", RuleID: "custom_no_dblink", Name: "No dblink", Description: &desc,
		Message: "m", Category: "DATA_PROTECTION", DefaultSeverity: "WARN", Enabled: false,
		Condition: json.RawMessage(`{"names":["dblink"],"type":"function_called"}`),
	})
	if m.ID.ValueString() != "r-1" || m.OrganizationID.ValueString() != "org-1" || m.RuleID.ValueString() != "custom_no_dblink" {
		t.Errorf("ids: %+v", m)
	}
	if m.Description.ValueString() != desc || m.Category.ValueString() != "DATA_PROTECTION" || m.Enabled.ValueBool() {
		t.Errorf("fields: %+v", m)
	}
	if m.Condition.ValueString() != `{"names":["dblink"],"type":"function_called"}` {
		t.Errorf("condition: %s", m.Condition.ValueString())
	}

	var noDesc sqlReviewRuleResourceModel
	noDesc.applyAPI(&client.SqlReviewRule{ID: "r-2", Enabled: true})
	if !noDesc.Description.IsNull() || !noDesc.Condition.IsNull() {
		t.Errorf("absent description/condition must stay null: %+v", noDesc)
	}
}
