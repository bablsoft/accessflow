package provider

import (
	"context"

	"github.com/bablsoft/terraform-provider-accessflow/internal/client"
	"github.com/hashicorp/terraform-plugin-framework/diag"
	"github.com/hashicorp/terraform-plugin-framework/resource"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema/boolplanmodifier"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema/planmodifier"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema/stringplanmodifier"
	"github.com/hashicorp/terraform-plugin-framework/types"
)

var (
	_ resource.Resource                = (*sqlReviewRulesetResource)(nil)
	_ resource.ResourceWithImportState = (*sqlReviewRulesetResource)(nil)
)

func NewSqlReviewRulesetResource() resource.Resource { return &sqlReviewRulesetResource{} }

type sqlReviewRulesetResource struct {
	client *client.Client
}

// sqlReviewRuleParamsType is the element type of a rule's params: rule parameter key ->
// list of string values (e.g. `globs = ["payroll.*"]`).
var sqlReviewRuleParamsType = types.ListType{ElemType: types.StringType}

type sqlReviewRuleModel struct {
	RuleID   types.String `tfsdk:"rule_id"`
	Severity types.String `tfsdk:"severity"`
	Params   types.Map    `tfsdk:"params"`
}

type sqlReviewRulesetResourceModel struct {
	ID             types.String         `tfsdk:"id"`
	OrganizationID types.String         `tfsdk:"organization_id"`
	Name           types.String         `tfsdk:"name"`
	Description    types.String         `tfsdk:"description"`
	Environment    types.String         `tfsdk:"environment"`
	Enabled        types.Bool           `tfsdk:"enabled"`
	Rules          []sqlReviewRuleModel `tfsdk:"rules"`
}

func (r *sqlReviewRulesetResource) Metadata(_ context.Context, req resource.MetadataRequest, resp *resource.MetadataResponse) {
	resp.TypeName = req.ProviderTypeName + "_sql_review_ruleset"
}

func (r *sqlReviewRulesetResource) Schema(_ context.Context, _ resource.SchemaRequest, resp *resource.SchemaResponse) {
	resp.Schema = schema.Schema{
		MarkdownDescription: "A deterministic SQL review ruleset: per-rule `OFF` / `WARN` / `BLOCK` severity, bound to one " +
			"datasource environment or serving as the organization-wide default. A `BLOCK` finding never rejects a " +
			"query — it only suppresses auto-approval and forces a human review.",
		Attributes: map[string]schema.Attribute{
			"id":              schema.StringAttribute{Computed: true, PlanModifiers: []planmodifier.String{stringplanmodifier.UseStateForUnknown()}},
			"organization_id": schema.StringAttribute{Computed: true, PlanModifiers: []planmodifier.String{stringplanmodifier.UseStateForUnknown()}},
			"name":            schema.StringAttribute{Required: true},
			"description":     schema.StringAttribute{Optional: true},
			"environment": schema.StringAttribute{
				Optional: true,
				MarkdownDescription: "`DEVELOPMENT`, `TEST`, `STAGING`, or `PRODUCTION`; omit for the organization-wide " +
					"default, which also governs any datasource whose environment has no ruleset bound. An organization " +
					"may hold one ruleset per environment plus one default — a second one fails with HTTP 409.",
			},
			"enabled": schema.BoolAttribute{
				Optional:            true,
				Computed:            true,
				PlanModifiers:       []planmodifier.Bool{boolplanmodifier.UseStateForUnknown()},
				MarkdownDescription: "Defaults to `true`. A disabled ruleset evaluates no rules and does not fall through to the default.",
			},
			"rules": schema.SetNestedAttribute{
				Optional: true,
				MarkdownDescription: "Rules configured explicitly; an unlisted rule runs at its built-in default severity. " +
					"Rule ids are listed by `GET /api/v1/sql-review/rules`.",
				NestedObject: schema.NestedAttributeObject{
					Attributes: map[string]schema.Attribute{
						"rule_id":  schema.StringAttribute{Required: true, MarkdownDescription: "Built-in rule id, e.g. `select_star`, `protected_table`."},
						"severity": schema.StringAttribute{Required: true, MarkdownDescription: "`OFF`, `WARN`, or `BLOCK`."},
						"params": schema.MapAttribute{
							Optional:    true,
							ElementType: sqlReviewRuleParamsType,
							MarkdownDescription: "Rule parameters as lists of strings — `protected_table` takes `globs`, " +
								"`disallowed_function` takes `names`. Omit for the twelve parameterless rules.",
						},
					},
				},
			},
		},
	}
}

func (r *sqlReviewRulesetResource) Configure(_ context.Context, req resource.ConfigureRequest, resp *resource.ConfigureResponse) {
	r.client = providerClient(req.ProviderData, &resp.Diagnostics)
}

func (m *sqlReviewRulesetResourceModel) toRequest(ctx context.Context, diags *diag.Diagnostics) client.SqlReviewRulesetRequest {
	req := client.SqlReviewRulesetRequest{
		Name:        strPtr(m.Name),
		Description: strPtr(m.Description),
		Environment: strPtr(m.Environment),
		Enabled:     boolPtr(m.Enabled),
		Rules:       make([]client.SqlReviewRuleConfig, 0, len(m.Rules)),
	}
	for _, rule := range m.Rules {
		cfg := client.SqlReviewRuleConfig{
			RuleID:   rule.RuleID.ValueString(),
			Severity: rule.Severity.ValueString(),
		}
		if !rule.Params.IsNull() && !rule.Params.IsUnknown() {
			params := map[string][]string{}
			diags.Append(rule.Params.ElementsAs(ctx, &params, false)...)
			cfg.Params = params
		}
		req.Rules = append(req.Rules, cfg)
	}
	return req
}

// applyAPI maps an API response onto the model. The API returns `"params": {}` for a
// parameterless rule; that maps to null unless the configuration spelled out an empty map
// for the same rule, which is kept so the state matches the plan.
func (m *sqlReviewRulesetResourceModel) applyAPI(ctx context.Context, rs *client.SqlReviewRuleset, diags *diag.Diagnostics) {
	configured := make(map[string]sqlReviewRuleModel, len(m.Rules))
	for _, rule := range m.Rules {
		configured[rule.RuleID.ValueString()] = rule
	}
	m.ID = types.StringValue(rs.ID)
	m.OrganizationID = types.StringValue(rs.OrganizationID)
	m.Name = types.StringValue(rs.Name)
	m.Description = strVal(rs.Description)
	m.Environment = strVal(rs.Environment)
	m.Enabled = types.BoolValue(rs.Enabled)
	if len(rs.Rules) == 0 {
		// A configured `rules = []` decodes to an empty non-nil slice and must round-trip as an
		// empty set, not null, or Terraform reports an inconsistent result after apply.
		if m.Rules != nil {
			m.Rules = []sqlReviewRuleModel{}
		}
		return
	}
	m.Rules = make([]sqlReviewRuleModel, 0, len(rs.Rules))
	for _, rule := range rs.Rules {
		params := types.MapNull(sqlReviewRuleParamsType)
		if len(rule.Params) > 0 {
			mapVal, d := types.MapValueFrom(ctx, sqlReviewRuleParamsType, rule.Params)
			diags.Append(d...)
			params = mapVal
		} else if prior, ok := configured[rule.RuleID]; ok && !prior.Params.IsNull() && !prior.Params.IsUnknown() {
			params = prior.Params
		}
		m.Rules = append(m.Rules, sqlReviewRuleModel{
			RuleID:   types.StringValue(rule.RuleID),
			Severity: types.StringValue(rule.Severity),
			Params:   params,
		})
	}
}

func (r *sqlReviewRulesetResource) Create(ctx context.Context, req resource.CreateRequest, resp *resource.CreateResponse) {
	var plan sqlReviewRulesetResourceModel
	resp.Diagnostics.Append(req.Plan.Get(ctx, &plan)...)
	if resp.Diagnostics.HasError() {
		return
	}
	rs, err := r.client.CreateSqlReviewRuleset(ctx, plan.toRequest(ctx, &resp.Diagnostics))
	if err != nil {
		resp.Diagnostics.AddError("Creating SQL review ruleset failed", err.Error())
		return
	}
	plan.applyAPI(ctx, rs, &resp.Diagnostics)
	resp.Diagnostics.Append(resp.State.Set(ctx, &plan)...)
}

func (r *sqlReviewRulesetResource) Read(ctx context.Context, req resource.ReadRequest, resp *resource.ReadResponse) {
	var state sqlReviewRulesetResourceModel
	resp.Diagnostics.Append(req.State.Get(ctx, &state)...)
	if resp.Diagnostics.HasError() {
		return
	}
	rs, err := r.client.GetSqlReviewRuleset(ctx, state.ID.ValueString())
	if err != nil {
		if client.IsNotFound(err) {
			resp.State.RemoveResource(ctx)
			return
		}
		resp.Diagnostics.AddError("Reading SQL review ruleset failed", err.Error())
		return
	}
	state.applyAPI(ctx, rs, &resp.Diagnostics)
	resp.Diagnostics.Append(resp.State.Set(ctx, &state)...)
}

func (r *sqlReviewRulesetResource) Update(ctx context.Context, req resource.UpdateRequest, resp *resource.UpdateResponse) {
	var plan sqlReviewRulesetResourceModel
	resp.Diagnostics.Append(req.Plan.Get(ctx, &plan)...)
	if resp.Diagnostics.HasError() {
		return
	}
	rs, err := r.client.UpdateSqlReviewRuleset(ctx, plan.ID.ValueString(), plan.toRequest(ctx, &resp.Diagnostics))
	if err != nil {
		resp.Diagnostics.AddError("Updating SQL review ruleset failed", err.Error())
		return
	}
	plan.applyAPI(ctx, rs, &resp.Diagnostics)
	resp.Diagnostics.Append(resp.State.Set(ctx, &plan)...)
}

func (r *sqlReviewRulesetResource) Delete(ctx context.Context, req resource.DeleteRequest, resp *resource.DeleteResponse) {
	var state sqlReviewRulesetResourceModel
	resp.Diagnostics.Append(req.State.Get(ctx, &state)...)
	if resp.Diagnostics.HasError() {
		return
	}
	if err := r.client.DeleteSqlReviewRuleset(ctx, state.ID.ValueString()); err != nil && !client.IsNotFound(err) {
		resp.Diagnostics.AddError("Deleting SQL review ruleset failed", err.Error())
	}
}

func (r *sqlReviewRulesetResource) ImportState(ctx context.Context, req resource.ImportStateRequest, resp *resource.ImportStateResponse) {
	resource.ImportStatePassthroughID(ctx, pathRoot("id"), req, resp)
}
