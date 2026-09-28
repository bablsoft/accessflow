package provider

import (
	"testing"

	"github.com/bablsoft/terraform-provider-accessflow/internal/client"
	"github.com/hashicorp/terraform-plugin-framework/types"
)

func TestDecisionHookModel_ToRequest(t *testing.T) {
	m := decisionHookResourceModel{
		Name:         types.StringValue("OPA"),
		DatasourceID: types.StringNull(),
		EndpointURL:  types.StringValue("https://opa.example.com/v1/data"),
		TimeoutMs:    types.Int64Value(1500),
		Secret:       types.StringValue("0123456789abcdef0123456789abcdef"),
		IncludeSQL:   types.BoolValue(false),
		Enabled:      types.BoolValue(true),
	}

	req := m.toRequest(true)
	if req.Name != "OPA" || req.EndpointURL != "https://opa.example.com/v1/data" {
		t.Errorf("scalars: %+v", req)
	}
	if req.DatasourceID != nil {
		t.Errorf("a null datasource must be omitted so the hook is the organization default: %v", *req.DatasourceID)
	}
	if req.TimeoutMs == nil || *req.TimeoutMs != 1500 || req.Enabled == nil || !*req.Enabled {
		t.Errorf("optionals: %+v", req)
	}
	if req.Secret == nil || *req.Secret != "0123456789abcdef0123456789abcdef" {
		t.Errorf("the secret must be sent on create: %+v", req.Secret)
	}
	if without := m.toRequest(false); without.Secret != nil {
		t.Errorf("the secret must be omitted when it did not change, got %v", *without.Secret)
	}
}

func TestDecisionHookModel_ApplyAPIKeepsTheSecret(t *testing.T) {
	ds := "ds-1"
	m := decisionHookResourceModel{Secret: types.StringValue("configured-secret-0123456789abcdef")}
	m.applyAPI(&client.DecisionHook{
		ID: "dh-1", OrganizationID: "org-1", DatasourceID: &ds, Name: "OPA",
		EndpointURL: "https://opa.example.com", TimeoutMs: 2000, IncludeSQL: true, Enabled: false,
		SecretConfigured: true,
	})
	if m.ID.ValueString() != "dh-1" || m.DatasourceID.ValueString() != "ds-1" || m.TimeoutMs.ValueInt64() != 2000 {
		t.Errorf("refreshed fields: %+v", m)
	}
	if !m.IncludeSQL.ValueBool() || m.Enabled.ValueBool() {
		t.Errorf("booleans: %+v", m)
	}
	if m.Secret.ValueString() != "configured-secret-0123456789abcdef" {
		t.Errorf("the write-only secret must be left as configured, got %q", m.Secret.ValueString())
	}

	m.applyAPI(&client.DecisionHook{ID: "dh-1", OrganizationID: "org-1", Name: "OPA"})
	if !m.DatasourceID.IsNull() {
		t.Errorf("an organization default must refresh to a null datasource, got %v", m.DatasourceID)
	}
}
