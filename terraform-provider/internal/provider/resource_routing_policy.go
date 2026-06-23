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
	_ resource.Resource                = (*routingPolicyResource)(nil)
	_ resource.ResourceWithImportState = (*routingPolicyResource)(nil)
)

func NewRoutingPolicyResource() resource.Resource { return &routingPolicyResource{} }

type routingPolicyResource struct {
	client *client.Client
}

type routingPolicyResourceModel struct {
	ID                types.String         `tfsdk:"id"`
	OrganizationID    types.String         `tfsdk:"organization_id"`
	DatasourceID      types.String         `tfsdk:"datasource_id"`
	Name              types.String         `tfsdk:"name"`
	Description       types.String         `tfsdk:"description"`
	Priority          types.Int64          `tfsdk:"priority"`
	Enabled           types.Bool           `tfsdk:"enabled"`
	Condition         jsontypes.Normalized `tfsdk:"condition"`
	Action            types.String         `tfsdk:"action"`
	RequiredApprovals types.Int64          `tfsdk:"required_approvals"`
	Reason            types.String         `tfsdk:"reason"`
}

func (r *routingPolicyResource) Metadata(_ context.Context, req resource.MetadataRequest, resp *resource.MetadataResponse) {
	resp.TypeName = req.ProviderTypeName + "_routing_policy"
}

func (r *routingPolicyResource) Schema(_ context.Context, _ resource.SchemaRequest, resp *resource.SchemaResponse) {
	resp.Schema = schema.Schema{
		MarkdownDescription: "An attribute-based routing policy (auto-approve / auto-reject / require N approvals / escalate).",
		Attributes: map[string]schema.Attribute{
			"id":              schema.StringAttribute{Computed: true, PlanModifiers: []planmodifier.String{stringplanmodifier.UseStateForUnknown()}},
			"organization_id": schema.StringAttribute{Computed: true, PlanModifiers: []planmodifier.String{stringplanmodifier.UseStateForUnknown()}},
			"datasource_id":   schema.StringAttribute{Optional: true, MarkdownDescription: "Scope to a single datasource; omit for org-wide."},
			"name":            schema.StringAttribute{Required: true},
			"description":     schema.StringAttribute{Optional: true},
			"priority":        schema.Int64Attribute{Required: true, MarkdownDescription: "Lower numbers evaluate first."},
			"enabled":         schema.BoolAttribute{Optional: true, Computed: true, PlanModifiers: []planmodifier.Bool{boolplanmodifier.UseStateForUnknown()}},
			"condition": schema.StringAttribute{
				Required:            true,
				CustomType:          jsontypes.NormalizedType{},
				MarkdownDescription: "The typed condition tree as a JSON object string.",
			},
			"action":             schema.StringAttribute{Required: true, MarkdownDescription: "`AUTO_APPROVE`, `AUTO_REJECT`, `REQUIRE_APPROVALS`, or `ESCALATE`."},
			"required_approvals": schema.Int64Attribute{Optional: true, MarkdownDescription: "Required when action is `REQUIRE_APPROVALS` or `ESCALATE`."},
			"reason":             schema.StringAttribute{Optional: true},
		},
	}
}

func (r *routingPolicyResource) Configure(_ context.Context, req resource.ConfigureRequest, resp *resource.ConfigureResponse) {
	r.client = providerClient(req.ProviderData, &resp.Diagnostics)
}

func (m *routingPolicyResourceModel) toRequest() client.RoutingPolicyRequest {
	req := client.RoutingPolicyRequest{
		Name:              strPtr(m.Name),
		Description:       strPtr(m.Description),
		DatasourceID:      strPtr(m.DatasourceID),
		Priority:          int64Ptr(m.Priority),
		Enabled:           boolPtr(m.Enabled),
		Action:            strPtr(m.Action),
		RequiredApprovals: int64Ptr(m.RequiredApprovals),
		Reason:            strPtr(m.Reason),
	}
	if !m.Condition.IsNull() && !m.Condition.IsUnknown() {
		req.Condition = json.RawMessage(m.Condition.ValueString())
	}
	return req
}

func (m *routingPolicyResourceModel) applyAPI(rp *client.RoutingPolicy) {
	m.ID = types.StringValue(rp.ID)
	m.OrganizationID = types.StringValue(rp.OrganizationID)
	m.DatasourceID = strVal(rp.DatasourceID)
	m.Name = types.StringValue(rp.Name)
	m.Description = strVal(rp.Description)
	m.Priority = types.Int64Value(rp.Priority)
	m.Enabled = types.BoolValue(rp.Enabled)
	if len(rp.Condition) > 0 {
		m.Condition = jsontypes.NewNormalizedValue(string(rp.Condition))
	}
	m.Action = types.StringValue(rp.Action)
	m.RequiredApprovals = int64Val(rp.RequiredApprovals)
	m.Reason = strVal(rp.Reason)
}

func (r *routingPolicyResource) Create(ctx context.Context, req resource.CreateRequest, resp *resource.CreateResponse) {
	var plan routingPolicyResourceModel
	resp.Diagnostics.Append(req.Plan.Get(ctx, &plan)...)
	if resp.Diagnostics.HasError() {
		return
	}
	rp, err := r.client.CreateRoutingPolicy(ctx, plan.toRequest())
	if err != nil {
		resp.Diagnostics.AddError("Creating routing policy failed", err.Error())
		return
	}
	plan.applyAPI(rp)
	resp.Diagnostics.Append(resp.State.Set(ctx, &plan)...)
}

func (r *routingPolicyResource) Read(ctx context.Context, req resource.ReadRequest, resp *resource.ReadResponse) {
	var state routingPolicyResourceModel
	resp.Diagnostics.Append(req.State.Get(ctx, &state)...)
	if resp.Diagnostics.HasError() {
		return
	}
	rp, err := r.client.GetRoutingPolicy(ctx, state.ID.ValueString())
	if err != nil {
		if client.IsNotFound(err) {
			resp.State.RemoveResource(ctx)
			return
		}
		resp.Diagnostics.AddError("Reading routing policy failed", err.Error())
		return
	}
	state.applyAPI(rp)
	resp.Diagnostics.Append(resp.State.Set(ctx, &state)...)
}

func (r *routingPolicyResource) Update(ctx context.Context, req resource.UpdateRequest, resp *resource.UpdateResponse) {
	var plan routingPolicyResourceModel
	resp.Diagnostics.Append(req.Plan.Get(ctx, &plan)...)
	if resp.Diagnostics.HasError() {
		return
	}
	rp, err := r.client.UpdateRoutingPolicy(ctx, plan.ID.ValueString(), plan.toRequest())
	if err != nil {
		resp.Diagnostics.AddError("Updating routing policy failed", err.Error())
		return
	}
	plan.applyAPI(rp)
	resp.Diagnostics.Append(resp.State.Set(ctx, &plan)...)
}

func (r *routingPolicyResource) Delete(ctx context.Context, req resource.DeleteRequest, resp *resource.DeleteResponse) {
	var state routingPolicyResourceModel
	resp.Diagnostics.Append(req.State.Get(ctx, &state)...)
	if resp.Diagnostics.HasError() {
		return
	}
	if err := r.client.DeleteRoutingPolicy(ctx, state.ID.ValueString()); err != nil && !client.IsNotFound(err) {
		resp.Diagnostics.AddError("Deleting routing policy failed", err.Error())
	}
}

func (r *routingPolicyResource) ImportState(ctx context.Context, req resource.ImportStateRequest, resp *resource.ImportStateResponse) {
	resource.ImportStatePassthroughID(ctx, pathRoot("id"), req, resp)
}
