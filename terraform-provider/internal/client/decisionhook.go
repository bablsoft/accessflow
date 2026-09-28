package client

import "context"

// DecisionHook is the API response shape for /admin/decision-hooks (#945). The signing secret is
// never returned; SecretConfigured only says whether one is stored. DatasourceID is absent (nil)
// for the organization default.
type DecisionHook struct {
	ID               string  `json:"id"`
	OrganizationID   string  `json:"organization_id"`
	DatasourceID     *string `json:"datasource_id"`
	Name             string  `json:"name"`
	EndpointURL      string  `json:"endpoint_url"`
	TimeoutMs        int64   `json:"timeout_ms"`
	IncludeSQL       bool    `json:"include_sql"`
	Enabled          bool    `json:"enabled"`
	SecretConfigured bool    `json:"secret_configured"`
	CreatedAt        string  `json:"created_at"`
	UpdatedAt        string  `json:"updated_at"`
}

// DecisionHookRequest is the create/update body. PUT is a full replace except for the secret: an
// omitted secret keeps the stored one. An omitted datasource_id makes the hook the organization
// default; omitted timeout_ms means 2000, include_sql false and enabled true.
type DecisionHookRequest struct {
	Name         string  `json:"name"`
	DatasourceID *string `json:"datasource_id,omitempty"`
	EndpointURL  string  `json:"endpoint_url"`
	TimeoutMs    *int64  `json:"timeout_ms,omitempty"`
	Secret       *string `json:"secret,omitempty"`
	IncludeSQL   *bool   `json:"include_sql,omitempty"`
	Enabled      *bool   `json:"enabled,omitempty"`
}

func (c *Client) CreateDecisionHook(ctx context.Context, req DecisionHookRequest) (*DecisionHook, error) {
	var out DecisionHook
	if err := c.do(ctx, "POST", "/admin/decision-hooks", req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) GetDecisionHook(ctx context.Context, id string) (*DecisionHook, error) {
	var out DecisionHook
	if err := c.do(ctx, "GET", "/admin/decision-hooks/"+id, nil, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) UpdateDecisionHook(ctx context.Context, id string, req DecisionHookRequest) (*DecisionHook, error) {
	var out DecisionHook
	if err := c.do(ctx, "PUT", "/admin/decision-hooks/"+id, req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) DeleteDecisionHook(ctx context.Context, id string) error {
	return c.do(ctx, "DELETE", "/admin/decision-hooks/"+id, nil, nil)
}
