// terraform-provider-accessflow is the Terraform / OpenTofu provider for AccessFlow,
// the open-source database access governance platform. It manages datasources, review
// plans, routing/row-security/masking policies, AI configs, and notification channels
// declaratively through the AccessFlow REST API using an API key.
package main

import (
	"context"
	"flag"
	"log"

	"github.com/bablsoft/terraform-provider-accessflow/internal/provider"
	"github.com/hashicorp/terraform-plugin-framework/providerserver"
)

// version is set at build time via -ldflags by GoReleaser; "dev" for local builds.
var version = "dev"

func main() {
	var debug bool
	flag.BoolVar(&debug, "debug", false, "set to true to run the provider with support for debuggers like delve")
	flag.Parse()

	opts := providerserver.ServeOpts{
		// Matches the registry namespace/type: registry.opentofu.org/bablsoft/accessflow
		// (and registry.terraform.io/bablsoft/accessflow — the same provider).
		Address: "registry.terraform.io/bablsoft/accessflow",
		Debug:   debug,
	}

	if err := providerserver.Serve(context.Background(), provider.New(version), opts); err != nil {
		log.Fatal(err.Error())
	}
}
