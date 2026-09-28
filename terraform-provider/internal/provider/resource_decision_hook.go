package provider

import (
	"context"

	"github.com/bablsoft/terraform-provider-accessflow/internal/client"
	"github.com/hashicorp/terraform-plugin-framework/resource"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema/booldefault"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema/int64default"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema/planmodifier"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema/stringplanmodifier"
	"github.com/hashicorp/terraform-plugin-framework/types"
)

var (
	_ resource.Resource                = (*decisionHookResource)(nil)
	_ resource.ResourceWithImportState = (*decisionHookResource)(nil)
)

func NewDecisionHookResource() resource.Resource { return &decisionHookResource{} }

type decisionHookResource struct {
	client *client.Client
}

type decisionHookResourceModel struct {
	ID             types.String `tfsdk:"id"`
	OrganizationID types.String `tfsdk:"organization_id"`
	DatasourceID   types.String `tfsdk:"datasource_id"`
	Name           types.String `tfsdk:"name"`
	EndpointURL    types.String `tfsdk:"endpoint_url"`
	TimeoutMs      types.Int64  `tfsdk:"timeout_ms"`
	Secret         types.String `tfsdk:"secret"`
	IncludeSQL     types.Bool   `tfsdk:"include_sql"`
	Enabled        types.Bool   `tfsdk:"enabled"`
}

func (r *decisionHookResource) Metadata(_ context.Context, req resource.MetadataRequest, resp *resource.MetadataResponse) {
	resp.TypeName = req.ProviderTypeName + "_decision_hook"
}

func (r *decisionHookResource) Schema(_ context.Context, _ resource.SchemaRequest, resp *resource.SchemaResponse) {
	resp.Schema = schema.Schema{
		MarkdownDescription: "An external policy decision hook: an HTTPS endpoint (an OPA sidecar, an in-house policy " +
			"service) consulted when no routing policy matched. It may allow, escalate, require more approvals or " +
			"reject a query — never approve — and any failure sends the query to human review. At most one " +
			"organization default (no `datasource_id`) and one hook per datasource. The `secret` is write-only: " +
			"the provider keeps the configured value rather than refreshing it.",
		Attributes: map[string]schema.Attribute{
			"id":              schema.StringAttribute{Computed: true, PlanModifiers: []planmodifier.String{stringplanmodifier.UseStateForUnknown()}},
			"organization_id": schema.StringAttribute{Computed: true, PlanModifiers: []planmodifier.String{stringplanmodifier.UseStateForUnknown()}},
			"datasource_id": schema.StringAttribute{
				Optional:            true,
				MarkdownDescription: "The datasource this hook applies to. Omit for the organization default.",
			},
			"name": schema.StringAttribute{Required: true},
			"endpoint_url": schema.StringAttribute{
				Required: true,
				MarkdownDescription: "`https://` URL. Private, loopback and link-local addresses are refused unless the " +
					"deployment sets `ACCESSFLOW_WORKFLOW_DECISION_HOOK_ALLOW_PRIVATE_NETWORK=true`.",
			},
			"timeout_ms": schema.Int64Attribute{
				Optional:            true,
				Computed:            true,
				Default:             int64default.StaticInt64(2000),
				MarkdownDescription: "Whole-call timeout in milliseconds, 100–10000. Defaults to 2000.",
			},
			"secret": schema.StringAttribute{
				Required:            true,
				Sensitive:           true,
				MarkdownDescription: "HMAC-SHA256 signing secret, 32–512 characters. Signs every request and must sign every response.",
			},
			"include_sql": schema.BoolAttribute{
				Optional:            true,
				Computed:            true,
				Default:             booldefault.StaticBool(false),
				MarkdownDescription: "Send the SQL text in the payload. Off by default: it can carry literal values.",
			},
			"enabled": schema.BoolAttribute{Optional: true, Computed: true, Default: booldefault.StaticBool(true)},
		},
	}
}

func (r *decisionHookResource) Configure(_ context.Context, req resource.ConfigureRequest, resp *resource.ConfigureResponse) {
	r.client = providerClient(req.ProviderData, &resp.Diagnostics)
}

func (m decisionHookResourceModel) toRequest(withSecret bool) client.DecisionHookRequest {
	req := client.DecisionHookRequest{
		Name:         m.Name.ValueString(),
		DatasourceID: strPtr(m.DatasourceID),
		EndpointURL:  m.EndpointURL.ValueString(),
		TimeoutMs:    int64Ptr(m.TimeoutMs),
		IncludeSQL:   boolPtr(m.IncludeSQL),
		Enabled:      boolPtr(m.Enabled),
	}
	if withSecret {
		req.Secret = strPtr(m.Secret)
	}
	return req
}

// applyAPI refreshes every attribute the API returns. The secret is never returned, so it is
// left as configured.
func (m *decisionHookResourceModel) applyAPI(h *client.DecisionHook) {
	m.ID = types.StringValue(h.ID)
	m.OrganizationID = types.StringValue(h.OrganizationID)
	m.DatasourceID = strVal(h.DatasourceID)
	m.Name = types.StringValue(h.Name)
	m.EndpointURL = types.StringValue(h.EndpointURL)
	m.TimeoutMs = types.Int64Value(h.TimeoutMs)
	m.IncludeSQL = types.BoolValue(h.IncludeSQL)
	m.Enabled = types.BoolValue(h.Enabled)
}

func (r *decisionHookResource) Create(ctx context.Context, req resource.CreateRequest, resp *resource.CreateResponse) {
	var plan decisionHookResourceModel
	resp.Diagnostics.Append(req.Plan.Get(ctx, &plan)...)
	if resp.Diagnostics.HasError() {
		return
	}
	h, err := r.client.CreateDecisionHook(ctx, plan.toRequest(true))
	if err != nil {
		resp.Diagnostics.AddError("Creating decision hook failed", err.Error())
		return
	}
	plan.applyAPI(h)
	resp.Diagnostics.Append(resp.State.Set(ctx, &plan)...)
}

func (r *decisionHookResource) Read(ctx context.Context, req resource.ReadRequest, resp *resource.ReadResponse) {
	var state decisionHookResourceModel
	resp.Diagnostics.Append(req.State.Get(ctx, &state)...)
	if resp.Diagnostics.HasError() {
		return
	}
	h, err := r.client.GetDecisionHook(ctx, state.ID.ValueString())
	if err != nil {
		if client.IsNotFound(err) {
			resp.State.RemoveResource(ctx)
			return
		}
		resp.Diagnostics.AddError("Reading decision hook failed", err.Error())
		return
	}
	state.applyAPI(h)
	resp.Diagnostics.Append(resp.State.Set(ctx, &state)...)
}

func (r *decisionHookResource) Update(ctx context.Context, req resource.UpdateRequest, resp *resource.UpdateResponse) {
	var plan, state decisionHookResourceModel
	resp.Diagnostics.Append(req.Plan.Get(ctx, &plan)...)
	resp.Diagnostics.Append(req.State.Get(ctx, &state)...)
	if resp.Diagnostics.HasError() {
		return
	}
	// Send the secret only when it changed, so an unrelated edit never rotates it needlessly.
	rotate := !plan.Secret.Equal(state.Secret)
	h, err := r.client.UpdateDecisionHook(ctx, plan.ID.ValueString(), plan.toRequest(rotate))
	if err != nil {
		resp.Diagnostics.AddError("Updating decision hook failed", err.Error())
		return
	}
	plan.applyAPI(h)
	resp.Diagnostics.Append(resp.State.Set(ctx, &plan)...)
}

func (r *decisionHookResource) Delete(ctx context.Context, req resource.DeleteRequest, resp *resource.DeleteResponse) {
	var state decisionHookResourceModel
	resp.Diagnostics.Append(req.State.Get(ctx, &state)...)
	if resp.Diagnostics.HasError() {
		return
	}
	if err := r.client.DeleteDecisionHook(ctx, state.ID.ValueString()); err != nil && !client.IsNotFound(err) {
		resp.Diagnostics.AddError("Deleting decision hook failed", err.Error())
	}
}

func (r *decisionHookResource) ImportState(ctx context.Context, req resource.ImportStateRequest, resp *resource.ImportStateResponse) {
	resource.ImportStatePassthroughID(ctx, pathRoot("id"), req, resp)
}
