default: build

.PHONY: build
build:
	go build ./...

.PHONY: install
install:
	go install .

.PHONY: test
test:
	go test ./... -count=1

# Acceptance tests require a live AccessFlow stack. Set ACCESSFLOW_ENDPOINT and
# ACCESSFLOW_API_KEY (an admin / service-account key) before running.
.PHONY: testacc
testacc:
	TF_ACC=1 go test ./internal/provider/ -v -count=1 -timeout 30m

.PHONY: vet
vet:
	go vet ./...

.PHONY: lint
lint:
	golangci-lint run

# Regenerate docs/ (registry input) from templates/ (provider overview), the schema
# MarkdownDescriptions, and examples/. Edit templates/index.md.tmpl, not docs/index.md.
.PHONY: docs
docs:
	go run github.com/hashicorp/terraform-plugin-docs/cmd/tfplugindocs generate \
		--provider-name accessflow
