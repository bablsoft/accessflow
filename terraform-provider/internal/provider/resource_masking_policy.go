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
	_ resource.Resource                = (*maskingPolicyResource)(nil)
	_ resource.ResourceWithImportState = (*maskingPolicyResource)(nil)
)

func NewMaskingPolicyResource() resource.Resource { return &maskingPolicyResource{} }

type maskingPolicyResource struct {
	client *client.Client
}

type maskingPolicyResourceModel struct {
	ID               types.String `tfsdk:"id"`
	DatasourceID     types.String `tfsdk:"datasource_id"`
	ColumnRef        types.String `tfsdk:"column_ref"`
	Strategy         types.String `tfsdk:"strategy"`
	StrategyParams   types.Map    `tfsdk:"strategy_params"`
	RevealToRoles    types.List   `tfsdk:"reveal_to_roles"`
	RevealToGroupIDs types.List   `tfsdk:"reveal_to_group_ids"`
	RevealToUserIDs  types.List   `tfsdk:"reveal_to_user_ids"`
	Enabled          types.Bool   `tfsdk:"enabled"`
}

func (r *maskingPolicyResource) Metadata(_ context.Context, req resource.MetadataRequest, resp *resource.MetadataResponse) {
	resp.TypeName = req.ProviderTypeName + "_masking_policy"
}

func (r *maskingPolicyResource) Schema(_ context.Context, _ resource.SchemaRequest, resp *resource.SchemaResponse) {
	resp.Schema = schema.Schema{
		MarkdownDescription: "A dynamic data-masking policy for a datasource column.",
		Attributes: map[string]schema.Attribute{
			"id": schema.StringAttribute{Computed: true, PlanModifiers: []planmodifier.String{stringplanmodifier.UseStateForUnknown()}},
			"datasource_id": schema.StringAttribute{
				Required:      true,
				PlanModifiers: []planmodifier.String{stringplanmodifier.RequiresReplace()},
			},
			"column_ref": schema.StringAttribute{Required: true, MarkdownDescription: "Fully-qualified column, e.g. `schema.table.column`."},
			"strategy":   schema.StringAttribute{Required: true, MarkdownDescription: "`FULL`, `PARTIAL`, `HASH`, `EMAIL`, or `FORMAT_PRESERVING`."},
			"strategy_params": schema.MapAttribute{
				Optional:            true,
				ElementType:         types.StringType,
				MarkdownDescription: "Strategy-specific params, e.g. `{ visible_suffix = \"4\" }` for `PARTIAL`.",
			},
			"reveal_to_roles":     schema.ListAttribute{Optional: true, ElementType: types.StringType},
			"reveal_to_group_ids": schema.ListAttribute{Optional: true, ElementType: types.StringType},
			"reveal_to_user_ids":  schema.ListAttribute{Optional: true, ElementType: types.StringType},
			"enabled":             schema.BoolAttribute{Optional: true, Computed: true, PlanModifiers: []planmodifier.Bool{boolplanmodifier.UseStateForUnknown()}},
		},
	}
}

func (r *maskingPolicyResource) Configure(_ context.Context, req resource.ConfigureRequest, resp *resource.ConfigureResponse) {
	r.client = providerClient(req.ProviderData, &resp.Diagnostics)
}

func (m *maskingPolicyResourceModel) toRequest(ctx context.Context, diags *diag.Diagnostics) client.MaskingPolicyRequest {
	req := client.MaskingPolicyRequest{
		ColumnRef:        strPtr(m.ColumnRef),
		Strategy:         strPtr(m.Strategy),
		RevealToRoles:    stringListToSlice(ctx, m.RevealToRoles, diags),
		RevealToGroupIDs: stringListToSlice(ctx, m.RevealToGroupIDs, diags),
		RevealToUserIDs:  stringListToSlice(ctx, m.RevealToUserIDs, diags),
		Enabled:          boolPtr(m.Enabled),
	}
	if !m.StrategyParams.IsNull() && !m.StrategyParams.IsUnknown() {
		params := map[string]string{}
		diags.Append(m.StrategyParams.ElementsAs(ctx, &params, false)...)
		req.StrategyParams = params
	}
	return req
}

func (m *maskingPolicyResourceModel) applyAPI(ctx context.Context, p *client.MaskingPolicy, diags *diag.Diagnostics) {
	m.ID = types.StringValue(p.ID)
	m.DatasourceID = types.StringValue(p.DatasourceID)
	m.ColumnRef = types.StringValue(p.ColumnRef)
	m.Strategy = types.StringValue(p.Strategy)
	if len(p.StrategyParams) == 0 {
		m.StrategyParams = types.MapNull(types.StringType)
	} else {
		mapVal, d := types.MapValueFrom(ctx, types.StringType, p.StrategyParams)
		diags.Append(d...)
		m.StrategyParams = mapVal
	}
	m.RevealToRoles = sliceToStringList(p.RevealToRoles)
	m.RevealToGroupIDs = sliceToStringList(p.RevealToGroupIDs)
	m.RevealToUserIDs = sliceToStringList(p.RevealToUserIDs)
	m.Enabled = types.BoolValue(p.Enabled)
}

func (r *maskingPolicyResource) Create(ctx context.Context, req resource.CreateRequest, resp *resource.CreateResponse) {
	var plan maskingPolicyResourceModel
	resp.Diagnostics.Append(req.Plan.Get(ctx, &plan)...)
	if resp.Diagnostics.HasError() {
		return
	}
	p, err := r.client.CreateMaskingPolicy(ctx, plan.DatasourceID.ValueString(), plan.toRequest(ctx, &resp.Diagnostics))
	if err != nil {
		resp.Diagnostics.AddError("Creating masking policy failed", err.Error())
		return
	}
	plan.applyAPI(ctx, p, &resp.Diagnostics)
	resp.Diagnostics.Append(resp.State.Set(ctx, &plan)...)
}

func (r *maskingPolicyResource) Read(ctx context.Context, req resource.ReadRequest, resp *resource.ReadResponse) {
	var state maskingPolicyResourceModel
	resp.Diagnostics.Append(req.State.Get(ctx, &state)...)
	if resp.Diagnostics.HasError() {
		return
	}
	p, err := r.client.GetMaskingPolicy(ctx, state.DatasourceID.ValueString(), state.ID.ValueString())
	if err != nil {
		if client.IsNotFound(err) {
			resp.State.RemoveResource(ctx)
			return
		}
		resp.Diagnostics.AddError("Reading masking policy failed", err.Error())
		return
	}
	state.applyAPI(ctx, p, &resp.Diagnostics)
	resp.Diagnostics.Append(resp.State.Set(ctx, &state)...)
}

func (r *maskingPolicyResource) Update(ctx context.Context, req resource.UpdateRequest, resp *resource.UpdateResponse) {
	var plan maskingPolicyResourceModel
	resp.Diagnostics.Append(req.Plan.Get(ctx, &plan)...)
	if resp.Diagnostics.HasError() {
		return
	}
	p, err := r.client.UpdateMaskingPolicy(ctx, plan.DatasourceID.ValueString(), plan.ID.ValueString(), plan.toRequest(ctx, &resp.Diagnostics))
	if err != nil {
		resp.Diagnostics.AddError("Updating masking policy failed", err.Error())
		return
	}
	plan.applyAPI(ctx, p, &resp.Diagnostics)
	resp.Diagnostics.Append(resp.State.Set(ctx, &plan)...)
}

func (r *maskingPolicyResource) Delete(ctx context.Context, req resource.DeleteRequest, resp *resource.DeleteResponse) {
	var state maskingPolicyResourceModel
	resp.Diagnostics.Append(req.State.Get(ctx, &state)...)
	if resp.Diagnostics.HasError() {
		return
	}
	if err := r.client.DeleteMaskingPolicy(ctx, state.DatasourceID.ValueString(), state.ID.ValueString()); err != nil && !client.IsNotFound(err) {
		resp.Diagnostics.AddError("Deleting masking policy failed", err.Error())
	}
}

func (r *maskingPolicyResource) ImportState(ctx context.Context, req resource.ImportStateRequest, resp *resource.ImportStateResponse) {
	parts := strings.SplitN(req.ID, "/", 2)
	if len(parts) != 2 || parts[0] == "" || parts[1] == "" {
		resp.Diagnostics.AddError("Invalid import ID",
			fmt.Sprintf("Expected `datasource_id/policy_id`, got %q.", req.ID))
		return
	}
	resp.Diagnostics.Append(resp.State.SetAttribute(ctx, path.Root("datasource_id"), parts[0])...)
	resp.Diagnostics.Append(resp.State.SetAttribute(ctx, path.Root("id"), parts[1])...)
}
