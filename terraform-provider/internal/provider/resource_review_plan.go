package provider

import (
	"context"

	"github.com/bablsoft/terraform-provider-accessflow/internal/client"
	"github.com/hashicorp/terraform-plugin-framework/diag"
	"github.com/hashicorp/terraform-plugin-framework/resource"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema/boolplanmodifier"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema/int64planmodifier"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema/planmodifier"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema/stringplanmodifier"
	"github.com/hashicorp/terraform-plugin-framework/types"
)

var (
	_ resource.Resource                = (*reviewPlanResource)(nil)
	_ resource.ResourceWithImportState = (*reviewPlanResource)(nil)
)

func NewReviewPlanResource() resource.Resource { return &reviewPlanResource{} }

type reviewPlanResource struct {
	client *client.Client
}

type reviewPlanApproverModel struct {
	UserID types.String `tfsdk:"user_id"`
	Role   types.String `tfsdk:"role"`
	Stage  types.Int64  `tfsdk:"stage"`
}

type reviewPlanResourceModel struct {
	ID                    types.String              `tfsdk:"id"`
	OrganizationID        types.String              `tfsdk:"organization_id"`
	Name                  types.String              `tfsdk:"name"`
	Description           types.String              `tfsdk:"description"`
	RequiresAIReview      types.Bool                `tfsdk:"requires_ai_review"`
	RequiresHumanApproval types.Bool                `tfsdk:"requires_human_approval"`
	MinApprovalsRequired  types.Int64               `tfsdk:"min_approvals_required"`
	ApprovalTimeoutHours  types.Int64               `tfsdk:"approval_timeout_hours"`
	AutoApproveReads      types.Bool                `tfsdk:"auto_approve_reads"`
	NotifyChannels        types.List                `tfsdk:"notify_channels"`
	Approvers             []reviewPlanApproverModel `tfsdk:"approvers"`
}

func (r *reviewPlanResource) Metadata(_ context.Context, req resource.MetadataRequest, resp *resource.MetadataResponse) {
	resp.TypeName = req.ProviderTypeName + "_review_plan"
}

func (r *reviewPlanResource) Schema(_ context.Context, _ resource.SchemaRequest, resp *resource.SchemaResponse) {
	resp.Schema = schema.Schema{
		MarkdownDescription: "A review plan: AI review + multi-stage human approval policy attached to datasources.",
		Attributes: map[string]schema.Attribute{
			"id":                      schema.StringAttribute{Computed: true, PlanModifiers: []planmodifier.String{stringplanmodifier.UseStateForUnknown()}},
			"organization_id":         schema.StringAttribute{Computed: true, PlanModifiers: []planmodifier.String{stringplanmodifier.UseStateForUnknown()}},
			"name":                    schema.StringAttribute{Required: true},
			"description":             schema.StringAttribute{Optional: true},
			"requires_ai_review":      schema.BoolAttribute{Optional: true, Computed: true, PlanModifiers: []planmodifier.Bool{boolplanmodifier.UseStateForUnknown()}},
			"requires_human_approval": schema.BoolAttribute{Optional: true, Computed: true, PlanModifiers: []planmodifier.Bool{boolplanmodifier.UseStateForUnknown()}},
			"min_approvals_required":  schema.Int64Attribute{Optional: true, Computed: true, PlanModifiers: []planmodifier.Int64{int64planmodifier.UseStateForUnknown()}},
			"approval_timeout_hours":  schema.Int64Attribute{Optional: true},
			"auto_approve_reads":      schema.BoolAttribute{Optional: true, Computed: true, PlanModifiers: []planmodifier.Bool{boolplanmodifier.UseStateForUnknown()}},
			"notify_channels": schema.ListAttribute{
				Optional:            true,
				ElementType:         types.StringType,
				MarkdownDescription: "Notification channel IDs to notify on review events.",
			},
			"approvers": schema.ListNestedAttribute{
				Optional:            true,
				MarkdownDescription: "Ordered approver stages.",
				NestedObject: schema.NestedAttributeObject{
					Attributes: map[string]schema.Attribute{
						"user_id": schema.StringAttribute{Optional: true},
						"role":    schema.StringAttribute{Optional: true, MarkdownDescription: "`ADMIN` or `USER`."},
						"stage":   schema.Int64Attribute{Required: true},
					},
				},
			},
		},
	}
}

func (r *reviewPlanResource) Configure(_ context.Context, req resource.ConfigureRequest, resp *resource.ConfigureResponse) {
	r.client = providerClient(req.ProviderData, &resp.Diagnostics)
}

func (m *reviewPlanResourceModel) toRequest(ctx context.Context, diags *diag.Diagnostics) client.ReviewPlanRequest {
	req := client.ReviewPlanRequest{
		Name:                  strPtr(m.Name),
		Description:           strPtr(m.Description),
		RequiresAIReview:      boolPtr(m.RequiresAIReview),
		RequiresHumanApproval: boolPtr(m.RequiresHumanApproval),
		MinApprovalsRequired:  int64Ptr(m.MinApprovalsRequired),
		ApprovalTimeoutHours:  int64Ptr(m.ApprovalTimeoutHours),
		AutoApproveReads:      boolPtr(m.AutoApproveReads),
		NotifyChannels:        stringListToSlice(ctx, m.NotifyChannels, diags),
	}
	if len(m.Approvers) > 0 {
		req.Approvers = make([]client.ReviewPlanApprover, 0, len(m.Approvers))
		for _, a := range m.Approvers {
			req.Approvers = append(req.Approvers, client.ReviewPlanApprover{
				UserID: strPtr(a.UserID),
				Role:   strPtr(a.Role),
				Stage:  a.Stage.ValueInt64(),
			})
		}
	}
	return req
}

func (m *reviewPlanResourceModel) applyAPI(rp *client.ReviewPlan) {
	m.ID = types.StringValue(rp.ID)
	m.OrganizationID = types.StringValue(rp.OrganizationID)
	m.Name = types.StringValue(rp.Name)
	m.Description = strVal(rp.Description)
	m.RequiresAIReview = types.BoolValue(rp.RequiresAIReview)
	m.RequiresHumanApproval = types.BoolValue(rp.RequiresHumanApproval)
	m.MinApprovalsRequired = types.Int64Value(rp.MinApprovalsRequired)
	m.ApprovalTimeoutHours = int64Val(rp.ApprovalTimeoutHours)
	m.AutoApproveReads = types.BoolValue(rp.AutoApproveReads)
	m.NotifyChannels = sliceToStringList(rp.NotifyChannels)
	if len(rp.Approvers) == 0 {
		m.Approvers = nil
		return
	}
	m.Approvers = make([]reviewPlanApproverModel, 0, len(rp.Approvers))
	for _, a := range rp.Approvers {
		m.Approvers = append(m.Approvers, reviewPlanApproverModel{
			UserID: strVal(a.UserID),
			Role:   strVal(a.Role),
			Stage:  types.Int64Value(a.Stage),
		})
	}
}

func (r *reviewPlanResource) Create(ctx context.Context, req resource.CreateRequest, resp *resource.CreateResponse) {
	var plan reviewPlanResourceModel
	resp.Diagnostics.Append(req.Plan.Get(ctx, &plan)...)
	if resp.Diagnostics.HasError() {
		return
	}
	rp, err := r.client.CreateReviewPlan(ctx, plan.toRequest(ctx, &resp.Diagnostics))
	if err != nil {
		resp.Diagnostics.AddError("Creating review plan failed", err.Error())
		return
	}
	plan.applyAPI(rp)
	resp.Diagnostics.Append(resp.State.Set(ctx, &plan)...)
}

func (r *reviewPlanResource) Read(ctx context.Context, req resource.ReadRequest, resp *resource.ReadResponse) {
	var state reviewPlanResourceModel
	resp.Diagnostics.Append(req.State.Get(ctx, &state)...)
	if resp.Diagnostics.HasError() {
		return
	}
	rp, err := r.client.GetReviewPlan(ctx, state.ID.ValueString())
	if err != nil {
		if client.IsNotFound(err) {
			resp.State.RemoveResource(ctx)
			return
		}
		resp.Diagnostics.AddError("Reading review plan failed", err.Error())
		return
	}
	state.applyAPI(rp)
	resp.Diagnostics.Append(resp.State.Set(ctx, &state)...)
}

func (r *reviewPlanResource) Update(ctx context.Context, req resource.UpdateRequest, resp *resource.UpdateResponse) {
	var plan reviewPlanResourceModel
	resp.Diagnostics.Append(req.Plan.Get(ctx, &plan)...)
	if resp.Diagnostics.HasError() {
		return
	}
	rp, err := r.client.UpdateReviewPlan(ctx, plan.ID.ValueString(), plan.toRequest(ctx, &resp.Diagnostics))
	if err != nil {
		resp.Diagnostics.AddError("Updating review plan failed", err.Error())
		return
	}
	plan.applyAPI(rp)
	resp.Diagnostics.Append(resp.State.Set(ctx, &plan)...)
}

func (r *reviewPlanResource) Delete(ctx context.Context, req resource.DeleteRequest, resp *resource.DeleteResponse) {
	var state reviewPlanResourceModel
	resp.Diagnostics.Append(req.State.Get(ctx, &state)...)
	if resp.Diagnostics.HasError() {
		return
	}
	if err := r.client.DeleteReviewPlan(ctx, state.ID.ValueString()); err != nil && !client.IsNotFound(err) {
		resp.Diagnostics.AddError("Deleting review plan failed", err.Error())
	}
}

func (r *reviewPlanResource) ImportState(ctx context.Context, req resource.ImportStateRequest, resp *resource.ImportStateResponse) {
	resource.ImportStatePassthroughID(ctx, pathRoot("id"), req, resp)
}
