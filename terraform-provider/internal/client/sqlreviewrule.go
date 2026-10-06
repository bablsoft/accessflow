package client

import (
	"context"
	"encoding/json"
)

// SqlReviewRule is the API response shape for /admin/sql-review-rules — an organization-defined
// SQL review rule. Description is absent (nil) when unset; condition is the typed condition tree
// as raw JSON.
type SqlReviewRule struct {
	ID              string          `json:"id"`
	OrganizationID  string          `json:"organization_id"`
	RuleID          string          `json:"rule_id"`
	Name            string          `json:"name"`
	Description     *string         `json:"description"`
	Message         string          `json:"message"`
	Category        string          `json:"category"`
	DefaultSeverity string          `json:"default_severity"`
	Enabled         bool            `json:"enabled"`
	Condition       json.RawMessage `json:"condition"`
	CreatedAt       string          `json:"created_at"`
	UpdatedAt       string          `json:"updated_at"`
}

// SqlReviewRuleRequest is the create/update body. PUT is a total replace on the API side: an
// omitted description clears it and an omitted enabled means true. rule_id is immutable — the
// API answers 422 when an update changes it.
type SqlReviewRuleRequest struct {
	RuleID          *string         `json:"rule_id,omitempty"`
	Name            *string         `json:"name,omitempty"`
	Description     *string         `json:"description,omitempty"`
	Message         *string         `json:"message,omitempty"`
	Category        *string         `json:"category,omitempty"`
	DefaultSeverity *string         `json:"default_severity,omitempty"`
	Enabled         *bool           `json:"enabled,omitempty"`
	Condition       json.RawMessage `json:"condition,omitempty"`
}

func (c *Client) CreateSqlReviewRule(ctx context.Context, req SqlReviewRuleRequest) (*SqlReviewRule, error) {
	var out SqlReviewRule
	if err := c.do(ctx, "POST", "/admin/sql-review-rules", req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) GetSqlReviewRule(ctx context.Context, id string) (*SqlReviewRule, error) {
	var out SqlReviewRule
	if err := c.do(ctx, "GET", "/admin/sql-review-rules/"+id, nil, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) UpdateSqlReviewRule(ctx context.Context, id string, req SqlReviewRuleRequest) (*SqlReviewRule, error) {
	var out SqlReviewRule
	if err := c.do(ctx, "PUT", "/admin/sql-review-rules/"+id, req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) DeleteSqlReviewRule(ctx context.Context, id string) error {
	return c.do(ctx, "DELETE", "/admin/sql-review-rules/"+id, nil, nil)
}
