package provider

import (
	"context"

	"github.com/bablsoft/terraform-provider-accessflow/internal/client"
	"github.com/hashicorp/terraform-plugin-framework/datasource"
	"github.com/hashicorp/terraform-plugin-framework/datasource/schema"
	"github.com/hashicorp/terraform-plugin-framework/types"
)

var _ datasource.DataSource = (*reviewPlanDataSource)(nil)

func NewReviewPlanDataSource() datasource.DataSource { return &reviewPlanDataSource{} }

type reviewPlanDataSource struct {
	client *client.Client
}

type reviewPlanDataSourceModel struct {
	ID                    types.String `tfsdk:"id"`
	OrganizationID        types.String `tfsdk:"organization_id"`
	Name                  types.String `tfsdk:"name"`
	Description           types.String `tfsdk:"description"`
	RequiresAIReview      types.Bool   `tfsdk:"requires_ai_review"`
	RequiresHumanApproval types.Bool   `tfsdk:"requires_human_approval"`
	MinApprovalsRequired  types.Int64  `tfsdk:"min_approvals_required"`
	ApprovalTimeoutHours  types.Int64  `tfsdk:"approval_timeout_hours"`
	AutoApproveReads      types.Bool   `tfsdk:"auto_approve_reads"`
	NotifyChannels        types.List   `tfsdk:"notify_channels"`
}

func (d *reviewPlanDataSource) Metadata(_ context.Context, req datasource.MetadataRequest, resp *datasource.MetadataResponse) {
	resp.TypeName = req.ProviderTypeName + "_review_plan"
}

func (d *reviewPlanDataSource) Schema(_ context.Context, _ datasource.SchemaRequest, resp *datasource.SchemaResponse) {
	resp.Schema = schema.Schema{
		MarkdownDescription: "Look up an existing review plan by id.",
		Attributes: map[string]schema.Attribute{
			"id":                      schema.StringAttribute{Required: true, MarkdownDescription: "Review plan UUID."},
			"organization_id":         schema.StringAttribute{Computed: true},
			"name":                    schema.StringAttribute{Computed: true},
			"description":             schema.StringAttribute{Computed: true},
			"requires_ai_review":      schema.BoolAttribute{Computed: true},
			"requires_human_approval": schema.BoolAttribute{Computed: true},
			"min_approvals_required":  schema.Int64Attribute{Computed: true},
			"approval_timeout_hours":  schema.Int64Attribute{Computed: true},
			"auto_approve_reads":      schema.BoolAttribute{Computed: true},
			"notify_channels":         schema.ListAttribute{Computed: true, ElementType: types.StringType},
		},
	}
}

func (d *reviewPlanDataSource) Configure(_ context.Context, req datasource.ConfigureRequest, resp *datasource.ConfigureResponse) {
	d.client = providerClient(req.ProviderData, &resp.Diagnostics)
}

func (d *reviewPlanDataSource) Read(ctx context.Context, req datasource.ReadRequest, resp *datasource.ReadResponse) {
	var model reviewPlanDataSourceModel
	resp.Diagnostics.Append(req.Config.Get(ctx, &model)...)
	if resp.Diagnostics.HasError() {
		return
	}
	rp, err := d.client.GetReviewPlan(ctx, model.ID.ValueString())
	if err != nil {
		resp.Diagnostics.AddError("Reading review plan failed", err.Error())
		return
	}
	model.OrganizationID = types.StringValue(rp.OrganizationID)
	model.Name = types.StringValue(rp.Name)
	model.Description = strVal(rp.Description)
	model.RequiresAIReview = types.BoolValue(rp.RequiresAIReview)
	model.RequiresHumanApproval = types.BoolValue(rp.RequiresHumanApproval)
	model.MinApprovalsRequired = types.Int64Value(rp.MinApprovalsRequired)
	model.ApprovalTimeoutHours = int64Val(rp.ApprovalTimeoutHours)
	model.AutoApproveReads = types.BoolValue(rp.AutoApproveReads)
	model.NotifyChannels = sliceToStringList(rp.NotifyChannels)
	resp.Diagnostics.Append(resp.State.Set(ctx, &model)...)
}
