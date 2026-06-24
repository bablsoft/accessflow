package provider

import (
	"context"
	"testing"

	"github.com/hashicorp/terraform-plugin-framework/datasource"
	fwprovider "github.com/hashicorp/terraform-plugin-framework/provider"
	"github.com/hashicorp/terraform-plugin-framework/providerserver"
	"github.com/hashicorp/terraform-plugin-framework/resource"
	"github.com/hashicorp/terraform-plugin-go/tfprotov6"
)

func TestProvider_Metadata(t *testing.T) {
	p := New("1.2.3")()
	var resp fwprovider.MetadataResponse
	p.Metadata(context.Background(), fwprovider.MetadataRequest{}, &resp)
	if resp.TypeName != "accessflow" {
		t.Errorf("TypeName = %q, want accessflow", resp.TypeName)
	}
	if resp.Version != "1.2.3" {
		t.Errorf("Version = %q, want 1.2.3", resp.Version)
	}
}

func TestProvider_SchemaHasNoErrors(t *testing.T) {
	p := New("test")()
	var resp fwprovider.SchemaResponse
	p.Schema(context.Background(), fwprovider.SchemaRequest{}, &resp)
	if resp.Diagnostics.HasError() {
		t.Fatalf("provider schema diagnostics: %v", resp.Diagnostics)
	}
	if _, ok := resp.Schema.Attributes["endpoint"]; !ok {
		t.Error("missing endpoint attribute")
	}
	if _, ok := resp.Schema.Attributes["api_key"]; !ok {
		t.Error("missing api_key attribute")
	}
}

func TestProvider_ResourceAndDataSourceSchemasValid(t *testing.T) {
	p := New("test")()
	for _, factory := range p.Resources(context.Background()) {
		r := factory()
		var resp resource.SchemaResponse
		r.Schema(context.Background(), resource.SchemaRequest{}, &resp)
		if resp.Diagnostics.HasError() {
			var meta resource.MetadataResponse
			r.Metadata(context.Background(), resource.MetadataRequest{ProviderTypeName: "accessflow"}, &meta)
			t.Errorf("resource %s schema diagnostics: %v", meta.TypeName, resp.Diagnostics)
		}
	}
	for _, factory := range p.DataSources(context.Background()) {
		d := factory()
		var resp datasource.SchemaResponse
		d.Schema(context.Background(), datasource.SchemaRequest{}, &resp)
		if resp.Diagnostics.HasError() {
			t.Errorf("data source schema diagnostics: %v", resp.Diagnostics)
		}
	}
}

func TestProvider_RegistersExpectedCounts(t *testing.T) {
	p := New("test")()
	if got := len(p.Resources(context.Background())); got != 7 {
		t.Errorf("resource count = %d, want 7", got)
	}
	if got := len(p.DataSources(context.Background())); got != 2 {
		t.Errorf("data source count = %d, want 2", got)
	}
}

// testAccProtoV6ProviderFactories wires the provider for acceptance tests.
var testAccProtoV6ProviderFactories = map[string]func() (tfprotov6.ProviderServer, error){
	"accessflow": providerserver.NewProtocol6WithError(New("test")()),
}
