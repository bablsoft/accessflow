package provider

import (
	"context"

	"github.com/bablsoft/terraform-provider-accessflow/internal/client"
	"github.com/hashicorp/terraform-plugin-framework/resource"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema/int64planmodifier"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema/planmodifier"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema/stringplanmodifier"
	"github.com/hashicorp/terraform-plugin-framework/types"
)

var (
	_ resource.Resource                = (*aiConfigResource)(nil)
	_ resource.ResourceWithImportState = (*aiConfigResource)(nil)
)

func NewAIConfigResource() resource.Resource { return &aiConfigResource{} }

type aiConfigResource struct {
	client *client.Client
}

type aiConfigResourceModel struct {
	ID                   types.String `tfsdk:"id"`
	OrganizationID       types.String `tfsdk:"organization_id"`
	Name                 types.String `tfsdk:"name"`
	Provider             types.String `tfsdk:"provider"`
	Model                types.String `tfsdk:"model"`
	Endpoint             types.String `tfsdk:"endpoint"`
	APIKey               types.String `tfsdk:"api_key"`
	TimeoutMs            types.Int64  `tfsdk:"timeout_ms"`
	MaxPromptTokens      types.Int64  `tfsdk:"max_prompt_tokens"`
	MaxCompletionTokens  types.Int64  `tfsdk:"max_completion_tokens"`
	SystemPromptTemplate types.String `tfsdk:"system_prompt_template"`
}

func (r *aiConfigResource) Metadata(_ context.Context, req resource.MetadataRequest, resp *resource.MetadataResponse) {
	resp.TypeName = req.ProviderTypeName + "_ai_config"
}

func (r *aiConfigResource) Schema(_ context.Context, _ resource.SchemaRequest, resp *resource.SchemaResponse) {
	resp.Schema = schema.Schema{
		MarkdownDescription: "An AI analyzer configuration (provider + model + connection). The API key is write-only.",
		Attributes: map[string]schema.Attribute{
			"id":              schema.StringAttribute{Computed: true, PlanModifiers: []planmodifier.String{stringplanmodifier.UseStateForUnknown()}},
			"organization_id": schema.StringAttribute{Computed: true, PlanModifiers: []planmodifier.String{stringplanmodifier.UseStateForUnknown()}},
			"name":            schema.StringAttribute{Required: true},
			"provider":        schema.StringAttribute{Required: true, MarkdownDescription: "`OPENAI`, `ANTHROPIC`, `OLLAMA`, `OPENAI_COMPATIBLE`, or `HUGGING_FACE`."},
			"model":           schema.StringAttribute{Required: true},
			"endpoint":        schema.StringAttribute{Optional: true},
			"api_key": schema.StringAttribute{
				Optional:            true,
				Sensitive:           true,
				MarkdownDescription: "Write-only. Never returned by the API, so changes are applied but not detected as drift.",
			},
			"timeout_ms":             schema.Int64Attribute{Optional: true, Computed: true, PlanModifiers: []planmodifier.Int64{int64planmodifier.UseStateForUnknown()}},
			"max_prompt_tokens":      schema.Int64Attribute{Optional: true, Computed: true, PlanModifiers: []planmodifier.Int64{int64planmodifier.UseStateForUnknown()}},
			"max_completion_tokens":  schema.Int64Attribute{Optional: true, Computed: true, PlanModifiers: []planmodifier.Int64{int64planmodifier.UseStateForUnknown()}},
			"system_prompt_template": schema.StringAttribute{Optional: true, MarkdownDescription: "Custom analyzer prompt; must contain `{{sql}}`. Omit for the built-in default."},
		},
	}
}

func (r *aiConfigResource) Configure(_ context.Context, req resource.ConfigureRequest, resp *resource.ConfigureResponse) {
	r.client = providerClient(req.ProviderData, &resp.Diagnostics)
}

func (m *aiConfigResourceModel) toRequest() client.AIConfigRequest {
	return client.AIConfigRequest{
		Name:                 strPtr(m.Name),
		Provider:             strPtr(m.Provider),
		Model:                strPtr(m.Model),
		Endpoint:             strPtr(m.Endpoint),
		APIKey:               strPtr(m.APIKey),
		TimeoutMs:            int64Ptr(m.TimeoutMs),
		MaxPromptTokens:      int64Ptr(m.MaxPromptTokens),
		MaxCompletionTokens:  int64Ptr(m.MaxCompletionTokens),
		SystemPromptTemplate: strPtr(m.SystemPromptTemplate),
	}
}

func (m *aiConfigResourceModel) applyAPI(a *client.AIConfig) {
	m.ID = types.StringValue(a.ID)
	m.OrganizationID = types.StringValue(a.OrganizationID)
	m.Name = types.StringValue(a.Name)
	m.Provider = types.StringValue(a.Provider)
	m.Model = types.StringValue(a.Model)
	m.Endpoint = strVal(a.Endpoint)
	m.TimeoutMs = int64Val(a.TimeoutMs)
	m.MaxPromptTokens = int64Val(a.MaxPromptTokens)
	m.MaxCompletionTokens = int64Val(a.MaxCompletionTokens)
	m.SystemPromptTemplate = strVal(a.SystemPromptTemplate)
}

func (r *aiConfigResource) Create(ctx context.Context, req resource.CreateRequest, resp *resource.CreateResponse) {
	var plan aiConfigResourceModel
	resp.Diagnostics.Append(req.Plan.Get(ctx, &plan)...)
	if resp.Diagnostics.HasError() {
		return
	}
	a, err := r.client.CreateAIConfig(ctx, plan.toRequest())
	if err != nil {
		resp.Diagnostics.AddError("Creating AI config failed", err.Error())
		return
	}
	apiKey := plan.APIKey
	plan.applyAPI(a)
	plan.APIKey = apiKey
	resp.Diagnostics.Append(resp.State.Set(ctx, &plan)...)
}

func (r *aiConfigResource) Read(ctx context.Context, req resource.ReadRequest, resp *resource.ReadResponse) {
	var state aiConfigResourceModel
	resp.Diagnostics.Append(req.State.Get(ctx, &state)...)
	if resp.Diagnostics.HasError() {
		return
	}
	a, err := r.client.GetAIConfig(ctx, state.ID.ValueString())
	if err != nil {
		if client.IsNotFound(err) {
			resp.State.RemoveResource(ctx)
			return
		}
		resp.Diagnostics.AddError("Reading AI config failed", err.Error())
		return
	}
	apiKey := state.APIKey
	state.applyAPI(a)
	state.APIKey = apiKey
	resp.Diagnostics.Append(resp.State.Set(ctx, &state)...)
}

func (r *aiConfigResource) Update(ctx context.Context, req resource.UpdateRequest, resp *resource.UpdateResponse) {
	var plan aiConfigResourceModel
	resp.Diagnostics.Append(req.Plan.Get(ctx, &plan)...)
	if resp.Diagnostics.HasError() {
		return
	}
	a, err := r.client.UpdateAIConfig(ctx, plan.ID.ValueString(), plan.toRequest())
	if err != nil {
		resp.Diagnostics.AddError("Updating AI config failed", err.Error())
		return
	}
	apiKey := plan.APIKey
	plan.applyAPI(a)
	plan.APIKey = apiKey
	resp.Diagnostics.Append(resp.State.Set(ctx, &plan)...)
}

func (r *aiConfigResource) Delete(ctx context.Context, req resource.DeleteRequest, resp *resource.DeleteResponse) {
	var state aiConfigResourceModel
	resp.Diagnostics.Append(req.State.Get(ctx, &state)...)
	if resp.Diagnostics.HasError() {
		return
	}
	if err := r.client.DeleteAIConfig(ctx, state.ID.ValueString()); err != nil && !client.IsNotFound(err) {
		resp.Diagnostics.AddError("Deleting AI config failed", err.Error())
	}
}

func (r *aiConfigResource) ImportState(ctx context.Context, req resource.ImportStateRequest, resp *resource.ImportStateResponse) {
	resource.ImportStatePassthroughID(ctx, pathRoot("id"), req, resp)
}
