package provider

import (
	"context"

	"github.com/bablsoft/terraform-provider-accessflow/internal/client"
	"github.com/hashicorp/terraform-plugin-framework/datasource"
	"github.com/hashicorp/terraform-plugin-framework/datasource/schema"
	"github.com/hashicorp/terraform-plugin-framework/types"
)

var _ datasource.DataSource = (*datasourceDataSource)(nil)

func NewDatasourceDataSource() datasource.DataSource { return &datasourceDataSource{} }

type datasourceDataSource struct {
	client *client.Client
}

type datasourceDataSourceModel struct {
	ID                types.String `tfsdk:"id"`
	OrganizationID    types.String `tfsdk:"organization_id"`
	Name              types.String `tfsdk:"name"`
	DBType            types.String `tfsdk:"db_type"`
	Host              types.String `tfsdk:"host"`
	Port              types.Int64  `tfsdk:"port"`
	DatabaseName      types.String `tfsdk:"database_name"`
	Username          types.String `tfsdk:"username"`
	SSLMode           types.String `tfsdk:"ssl_mode"`
	ReviewPlanID      types.String `tfsdk:"review_plan_id"`
	AIAnalysisEnabled types.Bool   `tfsdk:"ai_analysis_enabled"`
	AIConfigID        types.String `tfsdk:"ai_config_id"`
	Active            types.Bool   `tfsdk:"active"`
}

func (d *datasourceDataSource) Metadata(_ context.Context, req datasource.MetadataRequest, resp *datasource.MetadataResponse) {
	resp.TypeName = req.ProviderTypeName + "_datasource"
}

func (d *datasourceDataSource) Schema(_ context.Context, _ datasource.SchemaRequest, resp *datasource.SchemaResponse) {
	resp.Schema = schema.Schema{
		MarkdownDescription: "Look up an existing datasource by id.",
		Attributes: map[string]schema.Attribute{
			"id":                  schema.StringAttribute{Required: true, MarkdownDescription: "Datasource UUID."},
			"organization_id":     schema.StringAttribute{Computed: true},
			"name":                schema.StringAttribute{Computed: true},
			"db_type":             schema.StringAttribute{Computed: true},
			"host":                schema.StringAttribute{Computed: true},
			"port":                schema.Int64Attribute{Computed: true},
			"database_name":       schema.StringAttribute{Computed: true},
			"username":            schema.StringAttribute{Computed: true},
			"ssl_mode":            schema.StringAttribute{Computed: true},
			"review_plan_id":      schema.StringAttribute{Computed: true},
			"ai_analysis_enabled": schema.BoolAttribute{Computed: true},
			"ai_config_id":        schema.StringAttribute{Computed: true},
			"active":              schema.BoolAttribute{Computed: true},
		},
	}
}

func (d *datasourceDataSource) Configure(_ context.Context, req datasource.ConfigureRequest, resp *datasource.ConfigureResponse) {
	d.client = providerClient(req.ProviderData, &resp.Diagnostics)
}

func (d *datasourceDataSource) Read(ctx context.Context, req datasource.ReadRequest, resp *datasource.ReadResponse) {
	var model datasourceDataSourceModel
	resp.Diagnostics.Append(req.Config.Get(ctx, &model)...)
	if resp.Diagnostics.HasError() {
		return
	}
	ds, err := d.client.GetDatasource(ctx, model.ID.ValueString())
	if err != nil {
		resp.Diagnostics.AddError("Reading datasource failed", err.Error())
		return
	}
	model.OrganizationID = types.StringValue(ds.OrganizationID)
	model.Name = types.StringValue(ds.Name)
	model.DBType = types.StringValue(ds.DBType)
	model.Host = strVal(ds.Host)
	model.Port = int64Val(ds.Port)
	model.DatabaseName = strVal(ds.DatabaseName)
	model.Username = strVal(ds.Username)
	model.SSLMode = types.StringValue(ds.SSLMode)
	model.ReviewPlanID = strVal(ds.ReviewPlanID)
	model.AIAnalysisEnabled = types.BoolValue(ds.AIAnalysisEnabled)
	model.AIConfigID = strVal(ds.AIConfigID)
	model.Active = types.BoolValue(ds.Active)
	resp.Diagnostics.Append(resp.State.Set(ctx, &model)...)
}
