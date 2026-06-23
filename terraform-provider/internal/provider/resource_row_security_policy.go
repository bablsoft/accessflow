package provider

import (
	"context"
	"fmt"
	"strings"

	"github.com/bablsoft/terraform-provider-accessflow/internal/client"
	"github.com/hashicorp/terraform-plugin-framework/diag"
	"github.com/hashicorp/terraform-plugin-framework/path"
	"github.com/hashicorp/terraform-plugin-framework/resource"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema/boolplanmodifier"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema/planmodifier"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema/stringplanmodifier"
	"github.com/hashicorp/terraform-plugin-framework/types"
)

var (
	_ resource.Resource                = (*rowSecurityPolicyResource)(nil)
	_ resource.ResourceWithImportState = (*rowSecurityPolicyResource)(nil)
)

func NewRowSecurityPolicyResource() resource.Resource { return &rowSecurityPolicyResource{} }

type rowSecurityPolicyResource struct {
	client *client.Client
}

type rowSecurityPolicyResourceModel struct {
	ID                types.String `tfsdk:"id"`
	DatasourceID      types.String `tfsdk:"datasource_id"`
	TableName         types.String `tfsdk:"table_name"`
	ColumnName        types.String `tfsdk:"column_name"`
	Operator          types.String `tfsdk:"operator"`
	ValueType         types.String `tfsdk:"value_type"`
	ValueExpression   types.String `tfsdk:"value_expression"`
	AppliesToRoles    types.List   `tfsdk:"applies_to_roles"`
	AppliesToGroupIDs types.List   `tfsdk:"applies_to_group_ids"`
	AppliesToUserIDs  types.List   `tfsdk:"applies_to_user_ids"`
	Enabled           types.Bool   `tfsdk:"enabled"`
}

func (r *rowSecurityPolicyResource) Metadata(_ context.Context, req resource.MetadataRequest, resp *resource.MetadataResponse) {
	resp.TypeName = req.ProviderTypeName + "_row_security_policy"
}

func (r *rowSecurityPolicyResource) Schema(_ context.Context, _ resource.SchemaRequest, resp *resource.SchemaResponse) {
	resp.Schema = schema.Schema{
		MarkdownDescription: "A row-level security predicate injected into queries against a datasource table.",
		Attributes: map[string]schema.Attribute{
			"id": schema.StringAttribute{Computed: true, PlanModifiers: []planmodifier.String{stringplanmodifier.UseStateForUnknown()}},
			"datasource_id": schema.StringAttribute{
				Required:      true,
				PlanModifiers: []planmodifier.String{stringplanmodifier.RequiresReplace()},
			},
			"table_name":           schema.StringAttribute{Required: true},
			"column_name":          schema.StringAttribute{Required: true},
			"operator":             schema.StringAttribute{Required: true, MarkdownDescription: "`EQUALS`, `NOT_EQUALS`, `LESS_THAN`, `LESS_THAN_OR_EQUAL`, `GREATER_THAN`, `GREATER_THAN_OR_EQUAL`, `IN`, `NOT_IN`."},
			"value_type":           schema.StringAttribute{Required: true, MarkdownDescription: "`VARIABLE` (resolves `:user.<key>`) or `LITERAL`."},
			"value_expression":     schema.StringAttribute{Required: true},
			"applies_to_roles":     schema.ListAttribute{Optional: true, ElementType: types.StringType},
			"applies_to_group_ids": schema.ListAttribute{Optional: true, ElementType: types.StringType},
			"applies_to_user_ids":  schema.ListAttribute{Optional: true, ElementType: types.StringType},
			"enabled":              schema.BoolAttribute{Optional: true, Computed: true, PlanModifiers: []planmodifier.Bool{boolplanmodifier.UseStateForUnknown()}},
		},
	}
}

func (r *rowSecurityPolicyResource) Configure(_ context.Context, req resource.ConfigureRequest, resp *resource.ConfigureResponse) {
	r.client = providerClient(req.ProviderData, &resp.Diagnostics)
}

func (m *rowSecurityPolicyResourceModel) toRequest(ctx context.Context, diags *diag.Diagnostics) client.RowSecurityPolicyRequest {
	return client.RowSecurityPolicyRequest{
		TableName:         strPtr(m.TableName),
		ColumnName:        strPtr(m.ColumnName),
		Operator:          strPtr(m.Operator),
		ValueType:         strPtr(m.ValueType),
		ValueExpression:   strPtr(m.ValueExpression),
		AppliesToRoles:    stringListToSlice(ctx, m.AppliesToRoles, diags),
		AppliesToGroupIDs: stringListToSlice(ctx, m.AppliesToGroupIDs, diags),
		AppliesToUserIDs:  stringListToSlice(ctx, m.AppliesToUserIDs, diags),
		Enabled:           boolPtr(m.Enabled),
	}
}

func (m *rowSecurityPolicyResourceModel) applyAPI(p *client.RowSecurityPolicy) {
	m.ID = types.StringValue(p.ID)
	m.DatasourceID = types.StringValue(p.DatasourceID)
	m.TableName = types.StringValue(p.TableName)
	m.ColumnName = types.StringValue(p.ColumnName)
	m.Operator = types.StringValue(p.Operator)
	m.ValueType = types.StringValue(p.ValueType)
	m.ValueExpression = types.StringValue(p.ValueExpression)
	m.AppliesToRoles = sliceToStringList(p.AppliesToRoles)
	m.AppliesToGroupIDs = sliceToStringList(p.AppliesToGroupIDs)
	m.AppliesToUserIDs = sliceToStringList(p.AppliesToUserIDs)
	m.Enabled = types.BoolValue(p.Enabled)
}

func (r *rowSecurityPolicyResource) Create(ctx context.Context, req resource.CreateRequest, resp *resource.CreateResponse) {
	var plan rowSecurityPolicyResourceModel
	resp.Diagnostics.Append(req.Plan.Get(ctx, &plan)...)
	if resp.Diagnostics.HasError() {
		return
	}
	dsID := plan.DatasourceID.ValueString()
	p, err := r.client.CreateRowSecurityPolicy(ctx, dsID, plan.toRequest(ctx, &resp.Diagnostics))
	if err != nil {
		resp.Diagnostics.AddError("Creating row security policy failed", err.Error())
		return
	}
	plan.applyAPI(p)
	resp.Diagnostics.Append(resp.State.Set(ctx, &plan)...)
}

func (r *rowSecurityPolicyResource) Read(ctx context.Context, req resource.ReadRequest, resp *resource.ReadResponse) {
	var state rowSecurityPolicyResourceModel
	resp.Diagnostics.Append(req.State.Get(ctx, &state)...)
	if resp.Diagnostics.HasError() {
		return
	}
	p, err := r.client.GetRowSecurityPolicy(ctx, state.DatasourceID.ValueString(), state.ID.ValueString())
	if err != nil {
		if client.IsNotFound(err) {
			resp.State.RemoveResource(ctx)
			return
		}
		resp.Diagnostics.AddError("Reading row security policy failed", err.Error())
		return
	}
	state.applyAPI(p)
	resp.Diagnostics.Append(resp.State.Set(ctx, &state)...)
}

func (r *rowSecurityPolicyResource) Update(ctx context.Context, req resource.UpdateRequest, resp *resource.UpdateResponse) {
	var plan rowSecurityPolicyResourceModel
	resp.Diagnostics.Append(req.Plan.Get(ctx, &plan)...)
	if resp.Diagnostics.HasError() {
		return
	}
	p, err := r.client.UpdateRowSecurityPolicy(ctx, plan.DatasourceID.ValueString(), plan.ID.ValueString(), plan.toRequest(ctx, &resp.Diagnostics))
	if err != nil {
		resp.Diagnostics.AddError("Updating row security policy failed", err.Error())
		return
	}
	plan.applyAPI(p)
	resp.Diagnostics.Append(resp.State.Set(ctx, &plan)...)
}

func (r *rowSecurityPolicyResource) Delete(ctx context.Context, req resource.DeleteRequest, resp *resource.DeleteResponse) {
	var state rowSecurityPolicyResourceModel
	resp.Diagnostics.Append(req.State.Get(ctx, &state)...)
	if resp.Diagnostics.HasError() {
		return
	}
	if err := r.client.DeleteRowSecurityPolicy(ctx, state.DatasourceID.ValueString(), state.ID.ValueString()); err != nil && !client.IsNotFound(err) {
		resp.Diagnostics.AddError("Deleting row security policy failed", err.Error())
	}
}

func (r *rowSecurityPolicyResource) ImportState(ctx context.Context, req resource.ImportStateRequest, resp *resource.ImportStateResponse) {
	parts := strings.SplitN(req.ID, "/", 2)
	if len(parts) != 2 || parts[0] == "" || parts[1] == "" {
		resp.Diagnostics.AddError("Invalid import ID",
			fmt.Sprintf("Expected `datasource_id/policy_id`, got %q.", req.ID))
		return
	}
	resp.Diagnostics.Append(resp.State.SetAttribute(ctx, path.Root("datasource_id"), parts[0])...)
	resp.Diagnostics.Append(resp.State.SetAttribute(ctx, path.Root("id"), parts[1])...)
}
