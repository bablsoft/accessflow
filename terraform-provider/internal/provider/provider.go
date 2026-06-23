package provider

import (
	"context"
	"os"

	"github.com/bablsoft/terraform-provider-accessflow/internal/client"
	"github.com/hashicorp/terraform-plugin-framework/datasource"
	"github.com/hashicorp/terraform-plugin-framework/provider"
	"github.com/hashicorp/terraform-plugin-framework/provider/schema"
	"github.com/hashicorp/terraform-plugin-framework/resource"
	"github.com/hashicorp/terraform-plugin-framework/types"
)

// Ensure the provider satisfies the framework interface.
var _ provider.Provider = (*accessflowProvider)(nil)

type accessflowProvider struct {
	version string
}

// New returns a provider factory bound to a build version.
func New(version string) func() provider.Provider {
	return func() provider.Provider {
		return &accessflowProvider{version: version}
	}
}

type providerModel struct {
	Endpoint types.String `tfsdk:"endpoint"`
	APIKey   types.String `tfsdk:"api_key"`
}

func (p *accessflowProvider) Metadata(_ context.Context, _ provider.MetadataRequest, resp *provider.MetadataResponse) {
	resp.TypeName = "accessflow"
	resp.Version = p.version
}

func (p *accessflowProvider) Schema(_ context.Context, _ provider.SchemaRequest, resp *provider.SchemaResponse) {
	resp.Schema = schema.Schema{
		MarkdownDescription: "Manage AccessFlow database access governance resources (datasources, review plans, " +
			"routing/row-security/masking policies, AI configs, notification channels) via the REST API.",
		Attributes: map[string]schema.Attribute{
			"endpoint": schema.StringAttribute{
				Optional: true,
				MarkdownDescription: "Base URL of the AccessFlow backend, e.g. `https://accessflow.example`. " +
					"May also be set with the `ACCESSFLOW_ENDPOINT` environment variable.",
			},
			"api_key": schema.StringAttribute{
				Optional:  true,
				Sensitive: true,
				MarkdownDescription: "AccessFlow API key (the `af_`-prefixed token) used for `Authorization: ApiKey`. " +
					"May also be set with the `ACCESSFLOW_API_KEY` environment variable. Bootstrap one declaratively " +
					"with a service account (see the provider docs).",
			},
		},
	}
}

func (p *accessflowProvider) Configure(ctx context.Context, req provider.ConfigureRequest, resp *provider.ConfigureResponse) {
	var config providerModel
	resp.Diagnostics.Append(req.Config.Get(ctx, &config)...)
	if resp.Diagnostics.HasError() {
		return
	}

	endpoint := os.Getenv("ACCESSFLOW_ENDPOINT")
	if !config.Endpoint.IsNull() {
		endpoint = config.Endpoint.ValueString()
	}
	apiKey := os.Getenv("ACCESSFLOW_API_KEY")
	if !config.APIKey.IsNull() {
		apiKey = config.APIKey.ValueString()
	}

	if endpoint == "" {
		resp.Diagnostics.AddAttributeError(pathRoot("endpoint"),
			"Missing AccessFlow endpoint",
			"Set the provider `endpoint` attribute or the ACCESSFLOW_ENDPOINT environment variable.")
	}
	if apiKey == "" {
		resp.Diagnostics.AddAttributeError(pathRoot("api_key"),
			"Missing AccessFlow API key",
			"Set the provider `api_key` attribute or the ACCESSFLOW_API_KEY environment variable.")
	}
	if resp.Diagnostics.HasError() {
		return
	}

	c := client.New(endpoint, apiKey, nil)
	resp.DataSourceData = c
	resp.ResourceData = c
}

func (p *accessflowProvider) Resources(_ context.Context) []func() resource.Resource {
	return []func() resource.Resource{
		NewDatasourceResource,
		NewReviewPlanResource,
		NewRoutingPolicyResource,
		NewAIConfigResource,
		NewNotificationChannelResource,
		NewRowSecurityPolicyResource,
		NewMaskingPolicyResource,
	}
}

func (p *accessflowProvider) DataSources(_ context.Context) []func() datasource.DataSource {
	return []func() datasource.DataSource{
		NewDatasourceDataSource,
		NewReviewPlanDataSource,
	}
}
