//go:build tools

// Package tools pins the doc-generation toolchain so `go run` / `make docs` resolve a
// known version. Not compiled into the provider binary (the `tools` build tag excludes it).
package tools

import (
	_ "github.com/hashicorp/terraform-plugin-docs/cmd/tfplugindocs"
)
