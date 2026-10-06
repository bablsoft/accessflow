package provider

import (
	"context"
	"encoding/json"

	"github.com/bablsoft/terraform-provider-accessflow/internal/client"
	"github.com/hashicorp/terraform-plugin-framework-jsontypes/jsontypes"
	"github.com/hashicorp/terraform-plugin-framework/resource"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema/boolplanmodifier"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema/planmodifier"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema/stringplanmodifier"
	"github.com/hashicorp/terraform-plugin-framework/types"
)

var (
	_ resource.Resource                = (*sqlReviewRuleResource)(nil)
	_ resource.ResourceWithImportState = (*sqlReviewRuleResource)(nil)
)

func NewSqlReviewRuleResource() resource.Resource { return &sqlReviewRuleResource{} }

type sqlReviewRuleResource struct {
	client *client.Client
}

type sqlReviewRuleResourceModel struct {
	ID              types.String         `tfsdk:"id"`
	OrganizationID  types.String         `tfsdk:"organization_id"`
	RuleID          types.String         `tfsdk:"rule_id"`
	Name            types.String         `tfsdk:"name"`
	Description     types.String         `tfsdk:"description"`
	Message         types.String         `tfsdk:"message"`
	Category        types.String         `tfsdk:"category"`
	DefaultSeverity types.String         `tfsdk:"default_severity"`
	Enabled         types.Bool           `tfsdk:"enabled"`
	Condition       jsontypes.Normalized `tfsdk:"condition"`
}

func (r *sqlReviewRuleResource) Metadata(_ context.Context, req resource.MetadataRequest, resp *resource.MetadataResponse) {
	resp.TypeName = req.ProviderTypeName + "_sql_review_rule"
}

func (r *sqlReviewRuleResource) Schema(_ context.Context, _ resource.SchemaRequest, resp *resource.SchemaResponse) {
	resp.Schema = schema.Schema{
		MarkdownDescription: "An organization-defined SQL review rule: a condition tree over facts of one parsed " +
			"statement, and the message a matching statement is reported with. Once saved and enabled it joins the " +
			"rule catalog — configure it in an `accessflow_sql_review_ruleset` by its `rule_id` like a built-in.",
		Attributes: map[string]schema.Attribute{
			"id":              schema.StringAttribute{Computed: true, PlanModifiers: []planmodifier.String{stringplanmodifier.UseStateForUnknown()}},
			"organization_id": schema.StringAttribute{Computed: true, PlanModifiers: []planmodifier.String{stringplanmodifier.UseStateForUnknown()}},
			"rule_id": schema.StringAttribute{
				Required:            true,
				PlanModifiers:       []planmodifier.String{stringplanmodifier.RequiresReplace()},
				MarkdownDescription: "`custom_` followed by 3–61 lowercase letters, digits or underscores, starting with a letter. Immutable — changing it replaces the rule.",
			},
			"name":        schema.StringAttribute{Required: true, MarkdownDescription: "Display name, at most 255 characters."},
			"description": schema.StringAttribute{Optional: true, MarkdownDescription: "At most 2000 characters."},
			"message": schema.StringAttribute{
				Required:            true,
				MarkdownDescription: "Finding message, at most 500 characters. `{tables}`, `{functions}` and `{statement_type}` are substituted per statement.",
			},
			"category":         schema.StringAttribute{Required: true, MarkdownDescription: "`STATEMENT_SAFETY`, `PERFORMANCE`, `SCHEMA_CHANGE`, or `DATA_PROTECTION`."},
			"default_severity": schema.StringAttribute{Required: true, MarkdownDescription: "`OFF`, `WARN`, or `BLOCK` — used wherever a ruleset does not configure the rule."},
			"enabled": schema.BoolAttribute{
				Optional:            true,
				Computed:            true,
				PlanModifiers:       []planmodifier.Bool{boolplanmodifier.UseStateForUnknown()},
				MarkdownDescription: "Defaults to `true`. A disabled rule leaves evaluation but keeps its ruleset configs.",
			},
			"condition": schema.StringAttribute{
				Required:            true,
				CustomType:          jsontypes.NormalizedType{},
				MarkdownDescription: "The typed condition tree as a JSON object string (depth ≤ 5, ≤ 20 criteria).",
			},
		},
	}
}

func (r *sqlReviewRuleResource) Configure(_ context.Context, req resource.ConfigureRequest, resp *resource.ConfigureResponse) {
	r.client = providerClient(req.ProviderData, &resp.Diagnostics)
}

func (m *sqlReviewRuleResourceModel) toRequest() client.SqlReviewRuleRequest {
	req := client.SqlReviewRuleRequest{
		RuleID:          strPtr(m.RuleID),
		Name:            strPtr(m.Name),
		Description:     strPtr(m.Description),
		Message:         strPtr(m.Message),
		Category:        strPtr(m.Category),
		DefaultSeverity: strPtr(m.DefaultSeverity),
		Enabled:         boolPtr(m.Enabled),
	}
	if !m.Condition.IsNull() && !m.Condition.IsUnknown() {
		req.Condition = json.RawMessage(m.Condition.ValueString())
	}
	return req
}

func (m *sqlReviewRuleResourceModel) applyAPI(rule *client.SqlReviewRule) {
	m.ID = types.StringValue(rule.ID)
	m.OrganizationID = types.StringValue(rule.OrganizationID)
	m.RuleID = types.StringValue(rule.RuleID)
	m.Name = types.StringValue(rule.Name)
	m.Description = strVal(rule.Description)
	m.Message = types.StringValue(rule.Message)
	m.Category = types.StringValue(rule.Category)
	m.DefaultSeverity = types.StringValue(rule.DefaultSeverity)
	m.Enabled = types.BoolValue(rule.Enabled)
	if len(rule.Condition) > 0 {
		m.Condition = jsontypes.NewNormalizedValue(string(rule.Condition))
	}
}

func (r *sqlReviewRuleResource) Create(ctx context.Context, req resource.CreateRequest, resp *resource.CreateResponse) {
	var plan sqlReviewRuleResourceModel
	resp.Diagnostics.Append(req.Plan.Get(ctx, &plan)...)
	if resp.Diagnostics.HasError() {
		return
	}
	rule, err := r.client.CreateSqlReviewRule(ctx, plan.toRequest())
	if err != nil {
		resp.Diagnostics.AddError("Creating SQL review rule failed", err.Error())
		return
	}
	plan.applyAPI(rule)
	resp.Diagnostics.Append(resp.State.Set(ctx, &plan)...)
}

func (r *sqlReviewRuleResource) Read(ctx context.Context, req resource.ReadRequest, resp *resource.ReadResponse) {
	var state sqlReviewRuleResourceModel
	resp.Diagnostics.Append(req.State.Get(ctx, &state)...)
	if resp.Diagnostics.HasError() {
		return
	}
	rule, err := r.client.GetSqlReviewRule(ctx, state.ID.ValueString())
	if err != nil {
		if client.IsNotFound(err) {
			resp.State.RemoveResource(ctx)
			return
		}
		resp.Diagnostics.AddError("Reading SQL review rule failed", err.Error())
		return
	}
	state.applyAPI(rule)
	resp.Diagnostics.Append(resp.State.Set(ctx, &state)...)
}

func (r *sqlReviewRuleResource) Update(ctx context.Context, req resource.UpdateRequest, resp *resource.UpdateResponse) {
	var plan sqlReviewRuleResourceModel
	resp.Diagnostics.Append(req.Plan.Get(ctx, &plan)...)
	if resp.Diagnostics.HasError() {
		return
	}
	rule, err := r.client.UpdateSqlReviewRule(ctx, plan.ID.ValueString(), plan.toRequest())
	if err != nil {
		resp.Diagnostics.AddError("Updating SQL review rule failed", err.Error())
		return
	}
	plan.applyAPI(rule)
	resp.Diagnostics.Append(resp.State.Set(ctx, &plan)...)
}

func (r *sqlReviewRuleResource) Delete(ctx context.Context, req resource.DeleteRequest, resp *resource.DeleteResponse) {
	var state sqlReviewRuleResourceModel
	resp.Diagnostics.Append(req.State.Get(ctx, &state)...)
	if resp.Diagnostics.HasError() {
		return
	}
	if err := r.client.DeleteSqlReviewRule(ctx, state.ID.ValueString()); err != nil && !client.IsNotFound(err) {
		resp.Diagnostics.AddError("Deleting SQL review rule failed", err.Error())
	}
}

func (r *sqlReviewRuleResource) ImportState(ctx context.Context, req resource.ImportStateRequest, resp *resource.ImportStateResponse) {
	resource.ImportStatePassthroughID(ctx, pathRoot("id"), req, resp)
}
