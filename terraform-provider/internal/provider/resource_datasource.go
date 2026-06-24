package provider

import (
	"context"

	"github.com/bablsoft/terraform-provider-accessflow/internal/client"
	"github.com/hashicorp/terraform-plugin-framework/resource"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema/boolplanmodifier"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema/int64planmodifier"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema/planmodifier"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema/stringplanmodifier"
	"github.com/hashicorp/terraform-plugin-framework/types"
)

var (
	_ resource.Resource                = (*datasourceResource)(nil)
	_ resource.ResourceWithImportState = (*datasourceResource)(nil)
)

func NewDatasourceResource() resource.Resource { return &datasourceResource{} }

type datasourceResource struct {
	client *client.Client
}

type datasourceResourceModel struct {
	ID                  types.String `tfsdk:"id"`
	OrganizationID      types.String `tfsdk:"organization_id"`
	Name                types.String `tfsdk:"name"`
	DBType              types.String `tfsdk:"db_type"`
	Host                types.String `tfsdk:"host"`
	Port                types.Int64  `tfsdk:"port"`
	DatabaseName        types.String `tfsdk:"database_name"`
	Username            types.String `tfsdk:"username"`
	Password            types.String `tfsdk:"password"`
	SSLMode             types.String `tfsdk:"ssl_mode"`
	ConnectionPoolSize  types.Int64  `tfsdk:"connection_pool_size"`
	MaxRowsPerQuery     types.Int64  `tfsdk:"max_rows_per_query"`
	RequireReviewReads  types.Bool   `tfsdk:"require_review_reads"`
	RequireReviewWrites types.Bool   `tfsdk:"require_review_writes"`
	ReviewPlanID        types.String `tfsdk:"review_plan_id"`
	AIAnalysisEnabled   types.Bool   `tfsdk:"ai_analysis_enabled"`
	AIConfigID          types.String `tfsdk:"ai_config_id"`
	TextToSQLEnabled    types.Bool   `tfsdk:"text_to_sql_enabled"`
	JDBCURLOverride     types.String `tfsdk:"jdbc_url_override"`
	LocalDatacenter     types.String `tfsdk:"local_datacenter"`
	Active              types.Bool   `tfsdk:"active"`
}

func (r *datasourceResource) Metadata(_ context.Context, req resource.MetadataRequest, resp *resource.MetadataResponse) {
	resp.TypeName = req.ProviderTypeName + "_datasource"
}

func (r *datasourceResource) Schema(_ context.Context, _ resource.SchemaRequest, resp *resource.SchemaResponse) {
	resp.Schema = schema.Schema{
		MarkdownDescription: "A governed database connection (relational or NoSQL). Credentials are write-only.",
		Attributes: map[string]schema.Attribute{
			"id": schema.StringAttribute{
				Computed:            true,
				MarkdownDescription: "Datasource UUID.",
				PlanModifiers:       []planmodifier.String{stringplanmodifier.UseStateForUnknown()},
			},
			"organization_id": schema.StringAttribute{
				Computed:      true,
				PlanModifiers: []planmodifier.String{stringplanmodifier.UseStateForUnknown()},
			},
			"name":          schema.StringAttribute{Required: true, MarkdownDescription: "Unique datasource name within the organization."},
			"db_type":       schema.StringAttribute{Required: true, MarkdownDescription: "Engine, e.g. `POSTGRESQL`, `MYSQL`, `MONGODB`, `NEO4J`."},
			"host":          schema.StringAttribute{Optional: true},
			"port":          schema.Int64Attribute{Optional: true},
			"database_name": schema.StringAttribute{Optional: true},
			"username":      schema.StringAttribute{Optional: true},
			"password": schema.StringAttribute{
				Optional:            true,
				Sensitive:           true,
				MarkdownDescription: "Write-only. Never returned by the API, so changes are applied but not detected as drift.",
			},
			"ssl_mode":              schema.StringAttribute{Required: true, MarkdownDescription: "`DISABLE`, `REQUIRE`, `VERIFY_CA`, or `VERIFY_FULL`."},
			"connection_pool_size":  schema.Int64Attribute{Optional: true, Computed: true, PlanModifiers: []planmodifier.Int64{int64planmodifier.UseStateForUnknown()}},
			"max_rows_per_query":    schema.Int64Attribute{Optional: true, Computed: true, PlanModifiers: []planmodifier.Int64{int64planmodifier.UseStateForUnknown()}},
			"require_review_reads":  schema.BoolAttribute{Optional: true, Computed: true, PlanModifiers: []planmodifier.Bool{boolplanmodifier.UseStateForUnknown()}},
			"require_review_writes": schema.BoolAttribute{Optional: true, Computed: true, PlanModifiers: []planmodifier.Bool{boolplanmodifier.UseStateForUnknown()}},
			"review_plan_id":        schema.StringAttribute{Optional: true},
			"ai_analysis_enabled":   schema.BoolAttribute{Optional: true, Computed: true, PlanModifiers: []planmodifier.Bool{boolplanmodifier.UseStateForUnknown()}},
			"ai_config_id":          schema.StringAttribute{Optional: true},
			"text_to_sql_enabled":   schema.BoolAttribute{Optional: true, Computed: true, PlanModifiers: []planmodifier.Bool{boolplanmodifier.UseStateForUnknown()}},
			"jdbc_url_override":     schema.StringAttribute{Optional: true},
			"local_datacenter":      schema.StringAttribute{Optional: true, MarkdownDescription: "Required for Cassandra/ScyllaDB datasources."},
			"active":                schema.BoolAttribute{Optional: true, Computed: true, PlanModifiers: []planmodifier.Bool{boolplanmodifier.UseStateForUnknown()}},
		},
	}
}

func (r *datasourceResource) Configure(_ context.Context, req resource.ConfigureRequest, resp *resource.ConfigureResponse) {
	r.client = providerClient(req.ProviderData, &resp.Diagnostics)
}

func (m *datasourceResourceModel) toRequest() client.DatasourceRequest {
	return client.DatasourceRequest{
		Name:                strPtr(m.Name),
		DBType:              strPtr(m.DBType),
		Host:                strPtr(m.Host),
		Port:                int64Ptr(m.Port),
		DatabaseName:        strPtr(m.DatabaseName),
		Username:            strPtr(m.Username),
		Password:            strPtr(m.Password),
		SSLMode:             strPtr(m.SSLMode),
		ConnectionPoolSize:  int64Ptr(m.ConnectionPoolSize),
		MaxRowsPerQuery:     int64Ptr(m.MaxRowsPerQuery),
		RequireReviewReads:  boolPtr(m.RequireReviewReads),
		RequireReviewWrites: boolPtr(m.RequireReviewWrites),
		ReviewPlanID:        strPtr(m.ReviewPlanID),
		AIAnalysisEnabled:   boolPtr(m.AIAnalysisEnabled),
		AIConfigID:          strPtr(m.AIConfigID),
		TextToSQLEnabled:    boolPtr(m.TextToSQLEnabled),
		JDBCURLOverride:     strPtr(m.JDBCURLOverride),
		LocalDatacenter:     strPtr(m.LocalDatacenter),
	}
}

// applyAPI maps an API response onto the model. The write-only password is preserved.
func (m *datasourceResourceModel) applyAPI(ds *client.Datasource) {
	m.ID = types.StringValue(ds.ID)
	m.OrganizationID = types.StringValue(ds.OrganizationID)
	m.Name = types.StringValue(ds.Name)
	m.DBType = types.StringValue(ds.DBType)
	m.Host = strVal(ds.Host)
	m.Port = int64Val(ds.Port)
	m.DatabaseName = strVal(ds.DatabaseName)
	m.Username = strVal(ds.Username)
	m.SSLMode = types.StringValue(ds.SSLMode)
	m.ConnectionPoolSize = int64Val(ds.ConnectionPoolSize)
	m.MaxRowsPerQuery = int64Val(ds.MaxRowsPerQuery)
	m.RequireReviewReads = types.BoolValue(ds.RequireReviewReads)
	m.RequireReviewWrites = types.BoolValue(ds.RequireReviewWrites)
	m.ReviewPlanID = strVal(ds.ReviewPlanID)
	m.AIAnalysisEnabled = types.BoolValue(ds.AIAnalysisEnabled)
	m.AIConfigID = strVal(ds.AIConfigID)
	m.TextToSQLEnabled = types.BoolValue(ds.TextToSQLEnabled)
	m.JDBCURLOverride = strVal(ds.JDBCURLOverride)
	m.LocalDatacenter = strVal(ds.LocalDatacenter)
	m.Active = types.BoolValue(ds.Active)
}

func (r *datasourceResource) Create(ctx context.Context, req resource.CreateRequest, resp *resource.CreateResponse) {
	var plan datasourceResourceModel
	resp.Diagnostics.Append(req.Plan.Get(ctx, &plan)...)
	if resp.Diagnostics.HasError() {
		return
	}
	ds, err := r.client.CreateDatasource(ctx, plan.toRequest())
	if err != nil {
		resp.Diagnostics.AddError("Creating datasource failed", err.Error())
		return
	}
	password := plan.Password
	plan.applyAPI(ds)
	plan.Password = password
	resp.Diagnostics.Append(resp.State.Set(ctx, &plan)...)
}

func (r *datasourceResource) Read(ctx context.Context, req resource.ReadRequest, resp *resource.ReadResponse) {
	var state datasourceResourceModel
	resp.Diagnostics.Append(req.State.Get(ctx, &state)...)
	if resp.Diagnostics.HasError() {
		return
	}
	ds, err := r.client.GetDatasource(ctx, state.ID.ValueString())
	if err != nil {
		if client.IsNotFound(err) {
			resp.State.RemoveResource(ctx)
			return
		}
		resp.Diagnostics.AddError("Reading datasource failed", err.Error())
		return
	}
	password := state.Password
	state.applyAPI(ds)
	state.Password = password
	resp.Diagnostics.Append(resp.State.Set(ctx, &state)...)
}

func (r *datasourceResource) Update(ctx context.Context, req resource.UpdateRequest, resp *resource.UpdateResponse) {
	var plan datasourceResourceModel
	resp.Diagnostics.Append(req.Plan.Get(ctx, &plan)...)
	if resp.Diagnostics.HasError() {
		return
	}
	ds, err := r.client.UpdateDatasource(ctx, plan.ID.ValueString(), plan.toRequest())
	if err != nil {
		resp.Diagnostics.AddError("Updating datasource failed", err.Error())
		return
	}
	password := plan.Password
	plan.applyAPI(ds)
	plan.Password = password
	resp.Diagnostics.Append(resp.State.Set(ctx, &plan)...)
}

func (r *datasourceResource) Delete(ctx context.Context, req resource.DeleteRequest, resp *resource.DeleteResponse) {
	var state datasourceResourceModel
	resp.Diagnostics.Append(req.State.Get(ctx, &state)...)
	if resp.Diagnostics.HasError() {
		return
	}
	if err := r.client.DeleteDatasource(ctx, state.ID.ValueString()); err != nil && !client.IsNotFound(err) {
		resp.Diagnostics.AddError("Deleting datasource failed", err.Error())
	}
}

func (r *datasourceResource) ImportState(ctx context.Context, req resource.ImportStateRequest, resp *resource.ImportStateResponse) {
	resource.ImportStatePassthroughID(ctx, pathRoot("id"), req, resp)
}
