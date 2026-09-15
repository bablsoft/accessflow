package client

import "context"

// SqlReviewRuleset is the API response shape for /admin/sql-review-rulesets. Description and
// environment are absent (nil) when unset; rules come back ordered by rule_id and a
// parameterless rule carries an empty params object.
type SqlReviewRuleset struct {
	ID             string                `json:"id"`
	OrganizationID string                `json:"organization_id"`
	Name           string                `json:"name"`
	Description    *string               `json:"description"`
	Environment    *string               `json:"environment"`
	Enabled        bool                  `json:"enabled"`
	Rules          []SqlReviewRuleConfig `json:"rules"`
	CreatedAt      string                `json:"created_at"`
	UpdatedAt      string                `json:"updated_at"`
}

// SqlReviewRuleConfig is one explicitly configured rule of a ruleset.
type SqlReviewRuleConfig struct {
	RuleID   string              `json:"rule_id"`
	Severity string              `json:"severity"`
	Params   map[string][]string `json:"params,omitempty"`
}

// SqlReviewRulesetRequest is the create/update body. PUT is a total replace on the API side:
// an omitted description clears it, an omitted environment makes the ruleset the
// organization-wide default, an omitted enabled means true, and rules is the complete set.
type SqlReviewRulesetRequest struct {
	Name        *string               `json:"name,omitempty"`
	Description *string               `json:"description,omitempty"`
	Environment *string               `json:"environment,omitempty"`
	Enabled     *bool                 `json:"enabled,omitempty"`
	Rules       []SqlReviewRuleConfig `json:"rules"`
}

func (c *Client) CreateSqlReviewRuleset(ctx context.Context, req SqlReviewRulesetRequest) (*SqlReviewRuleset, error) {
	var out SqlReviewRuleset
	if err := c.do(ctx, "POST", "/admin/sql-review-rulesets", req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) GetSqlReviewRuleset(ctx context.Context, id string) (*SqlReviewRuleset, error) {
	var out SqlReviewRuleset
	if err := c.do(ctx, "GET", "/admin/sql-review-rulesets/"+id, nil, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) UpdateSqlReviewRuleset(ctx context.Context, id string, req SqlReviewRulesetRequest) (*SqlReviewRuleset, error) {
	var out SqlReviewRuleset
	if err := c.do(ctx, "PUT", "/admin/sql-review-rulesets/"+id, req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) DeleteSqlReviewRuleset(ctx context.Context, id string) error {
	return c.do(ctx, "DELETE", "/admin/sql-review-rulesets/"+id, nil, nil)
}
