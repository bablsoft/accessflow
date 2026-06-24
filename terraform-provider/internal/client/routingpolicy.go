package client

import (
	"context"
	"encoding/json"
)

type RoutingPolicy struct {
	ID                string          `json:"id"`
	OrganizationID    string          `json:"organization_id"`
	DatasourceID      *string         `json:"datasource_id"`
	Name              string          `json:"name"`
	Description       *string         `json:"description"`
	Priority          int64           `json:"priority"`
	Enabled           bool            `json:"enabled"`
	Condition         json.RawMessage `json:"condition"`
	Action            string          `json:"action"`
	RequiredApprovals *int64          `json:"required_approvals"`
	Reason            *string         `json:"reason"`
}

type RoutingPolicyRequest struct {
	Name              *string         `json:"name,omitempty"`
	Description       *string         `json:"description,omitempty"`
	DatasourceID      *string         `json:"datasource_id,omitempty"`
	Priority          *int64          `json:"priority,omitempty"`
	Enabled           *bool           `json:"enabled,omitempty"`
	Condition         json.RawMessage `json:"condition,omitempty"`
	Action            *string         `json:"action,omitempty"`
	RequiredApprovals *int64          `json:"required_approvals,omitempty"`
	Reason            *string         `json:"reason,omitempty"`
}

func (c *Client) CreateRoutingPolicy(ctx context.Context, req RoutingPolicyRequest) (*RoutingPolicy, error) {
	var out RoutingPolicy
	if err := c.do(ctx, "POST", "/admin/routing-policies", req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) GetRoutingPolicy(ctx context.Context, id string) (*RoutingPolicy, error) {
	var out RoutingPolicy
	if err := c.do(ctx, "GET", "/admin/routing-policies/"+id, nil, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) UpdateRoutingPolicy(ctx context.Context, id string, req RoutingPolicyRequest) (*RoutingPolicy, error) {
	var out RoutingPolicy
	if err := c.do(ctx, "PUT", "/admin/routing-policies/"+id, req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) DeleteRoutingPolicy(ctx context.Context, id string) error {
	return c.do(ctx, "DELETE", "/admin/routing-policies/"+id, nil, nil)
}
