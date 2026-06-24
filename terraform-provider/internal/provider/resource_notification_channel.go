package provider

import (
	"context"

	"github.com/bablsoft/terraform-provider-accessflow/internal/client"
	"github.com/hashicorp/terraform-plugin-framework/resource"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema/boolplanmodifier"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema/planmodifier"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema/stringplanmodifier"
	"github.com/hashicorp/terraform-plugin-framework/types"
)

var (
	_ resource.Resource                = (*notificationChannelResource)(nil)
	_ resource.ResourceWithImportState = (*notificationChannelResource)(nil)
)

func NewNotificationChannelResource() resource.Resource { return &notificationChannelResource{} }

type notificationChannelResource struct {
	client *client.Client
}

type notificationChannelResourceModel struct {
	ID             types.String `tfsdk:"id"`
	OrganizationID types.String `tfsdk:"organization_id"`
	Name           types.String `tfsdk:"name"`
	ChannelType    types.String `tfsdk:"channel_type"`
	Config         types.Map    `tfsdk:"config"`
	Active         types.Bool   `tfsdk:"active"`
}

func (r *notificationChannelResource) Metadata(_ context.Context, req resource.MetadataRequest, resp *resource.MetadataResponse) {
	resp.TypeName = req.ProviderTypeName + "_notification_channel"
}

func (r *notificationChannelResource) Schema(_ context.Context, _ resource.SchemaRequest, resp *resource.SchemaResponse) {
	resp.Schema = schema.Schema{
		MarkdownDescription: "A notification channel (Email, Slack, Webhook, Discord, Telegram, MS Teams, PagerDuty). " +
			"Sensitive `config` values are write-only — the provider keeps the configured map rather than refreshing it.",
		Attributes: map[string]schema.Attribute{
			"id":              schema.StringAttribute{Computed: true, PlanModifiers: []planmodifier.String{stringplanmodifier.UseStateForUnknown()}},
			"organization_id": schema.StringAttribute{Computed: true, PlanModifiers: []planmodifier.String{stringplanmodifier.UseStateForUnknown()}},
			"name":            schema.StringAttribute{Required: true},
			"channel_type": schema.StringAttribute{
				Required:            true,
				MarkdownDescription: "`EMAIL`, `SLACK`, `WEBHOOK`, `DISCORD`, `TELEGRAM`, `MS_TEAMS`, or `PAGERDUTY`. Changing forces replacement.",
				PlanModifiers:       []planmodifier.String{stringplanmodifier.RequiresReplace()},
			},
			"config": schema.MapAttribute{
				Required:            true,
				ElementType:         types.StringType,
				Sensitive:           true,
				MarkdownDescription: "Channel-type-specific settings as string values (e.g. `webhook_url`, `smtp_host`, `bot_token`).",
			},
			"active": schema.BoolAttribute{Optional: true, Computed: true, PlanModifiers: []planmodifier.Bool{boolplanmodifier.UseStateForUnknown()}},
		},
	}
}

func (r *notificationChannelResource) Configure(_ context.Context, req resource.ConfigureRequest, resp *resource.ConfigureResponse) {
	r.client = providerClient(req.ProviderData, &resp.Diagnostics)
}

func (r *notificationChannelResource) Create(ctx context.Context, req resource.CreateRequest, resp *resource.CreateResponse) {
	var plan notificationChannelResourceModel
	resp.Diagnostics.Append(req.Plan.Get(ctx, &plan)...)
	if resp.Diagnostics.HasError() {
		return
	}
	config := map[string]string{}
	resp.Diagnostics.Append(plan.Config.ElementsAs(ctx, &config, false)...)
	if resp.Diagnostics.HasError() {
		return
	}
	ch, err := r.client.CreateNotificationChannel(ctx, client.CreateNotificationChannelRequest{
		Name:        plan.Name.ValueString(),
		ChannelType: plan.ChannelType.ValueString(),
		Config:      config,
	})
	if err != nil {
		resp.Diagnostics.AddError("Creating notification channel failed", err.Error())
		return
	}
	plan.ID = types.StringValue(ch.ID)
	plan.OrganizationID = types.StringValue(ch.OrganizationID)
	plan.Active = types.BoolValue(ch.Active)
	resp.Diagnostics.Append(resp.State.Set(ctx, &plan)...)
}

func (r *notificationChannelResource) Read(ctx context.Context, req resource.ReadRequest, resp *resource.ReadResponse) {
	var state notificationChannelResourceModel
	resp.Diagnostics.Append(req.State.Get(ctx, &state)...)
	if resp.Diagnostics.HasError() {
		return
	}
	ch, err := r.client.GetNotificationChannel(ctx, state.ID.ValueString())
	if err != nil {
		if client.IsNotFound(err) {
			resp.State.RemoveResource(ctx)
			return
		}
		resp.Diagnostics.AddError("Reading notification channel failed", err.Error())
		return
	}
	// Config is write-only/masked — keep the configured map; refresh only safe fields.
	state.Name = types.StringValue(ch.Name)
	state.ChannelType = types.StringValue(ch.ChannelType)
	state.OrganizationID = types.StringValue(ch.OrganizationID)
	state.Active = types.BoolValue(ch.Active)
	resp.Diagnostics.Append(resp.State.Set(ctx, &state)...)
}

func (r *notificationChannelResource) Update(ctx context.Context, req resource.UpdateRequest, resp *resource.UpdateResponse) {
	var plan notificationChannelResourceModel
	resp.Diagnostics.Append(req.Plan.Get(ctx, &plan)...)
	if resp.Diagnostics.HasError() {
		return
	}
	config := map[string]string{}
	resp.Diagnostics.Append(plan.Config.ElementsAs(ctx, &config, false)...)
	if resp.Diagnostics.HasError() {
		return
	}
	name := plan.Name.ValueString()
	active := plan.Active.ValueBool()
	ch, err := r.client.UpdateNotificationChannel(ctx, plan.ID.ValueString(), client.UpdateNotificationChannelRequest{
		Name:   &name,
		Config: config,
		Active: &active,
	})
	if err != nil {
		resp.Diagnostics.AddError("Updating notification channel failed", err.Error())
		return
	}
	plan.OrganizationID = types.StringValue(ch.OrganizationID)
	plan.Active = types.BoolValue(ch.Active)
	resp.Diagnostics.Append(resp.State.Set(ctx, &plan)...)
}

func (r *notificationChannelResource) Delete(ctx context.Context, req resource.DeleteRequest, resp *resource.DeleteResponse) {
	var state notificationChannelResourceModel
	resp.Diagnostics.Append(req.State.Get(ctx, &state)...)
	if resp.Diagnostics.HasError() {
		return
	}
	if err := r.client.DeleteNotificationChannel(ctx, state.ID.ValueString()); err != nil && !client.IsNotFound(err) {
		resp.Diagnostics.AddError("Deleting notification channel failed", err.Error())
	}
}

func (r *notificationChannelResource) ImportState(ctx context.Context, req resource.ImportStateRequest, resp *resource.ImportStateResponse) {
	resource.ImportStatePassthroughID(ctx, pathRoot("id"), req, resp)
}
