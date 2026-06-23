package client

import "context"

// RowSecurityPolicy is nested under a datasource and has no GET /{id} — Read lists the
// datasource's policies and filters by id.
type RowSecurityPolicy struct {
	ID                string   `json:"id"`
	DatasourceID      string   `json:"datasource_id"`
	TableName         string   `json:"table_name"`
	ColumnName        string   `json:"column_name"`
	Operator          string   `json:"operator"`
	ValueType         string   `json:"value_type"`
	ValueExpression   string   `json:"value_expression"`
	AppliesToRoles    []string `json:"applies_to_roles"`
	AppliesToGroupIDs []string `json:"applies_to_group_ids"`
	AppliesToUserIDs  []string `json:"applies_to_user_ids"`
	Enabled           bool     `json:"enabled"`
}

type RowSecurityPolicyRequest struct {
	TableName         *string  `json:"table_name,omitempty"`
	ColumnName        *string  `json:"column_name,omitempty"`
	Operator          *string  `json:"operator,omitempty"`
	ValueType         *string  `json:"value_type,omitempty"`
	ValueExpression   *string  `json:"value_expression,omitempty"`
	AppliesToRoles    []string `json:"applies_to_roles,omitempty"`
	AppliesToGroupIDs []string `json:"applies_to_group_ids,omitempty"`
	AppliesToUserIDs  []string `json:"applies_to_user_ids,omitempty"`
	Enabled           *bool    `json:"enabled,omitempty"`
}

func (c *Client) CreateRowSecurityPolicy(ctx context.Context, datasourceID string, req RowSecurityPolicyRequest) (*RowSecurityPolicy, error) {
	var out RowSecurityPolicy
	if err := c.do(ctx, "POST", "/datasources/"+datasourceID+"/row-security-policies", req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) GetRowSecurityPolicy(ctx context.Context, datasourceID, id string) (*RowSecurityPolicy, error) {
	var list []RowSecurityPolicy
	if err := c.do(ctx, "GET", "/datasources/"+datasourceID+"/row-security-policies", nil, &list); err != nil {
		return nil, err
	}
	for i := range list {
		if list[i].ID == id {
			return &list[i], nil
		}
	}
	return nil, &APIError{StatusCode: 404, Title: "Not Found", Detail: "row security policy " + id + " not found"}
}

func (c *Client) UpdateRowSecurityPolicy(ctx context.Context, datasourceID, id string, req RowSecurityPolicyRequest) (*RowSecurityPolicy, error) {
	var out RowSecurityPolicy
	if err := c.do(ctx, "PUT", "/datasources/"+datasourceID+"/row-security-policies/"+id, req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) DeleteRowSecurityPolicy(ctx context.Context, datasourceID, id string) error {
	return c.do(ctx, "DELETE", "/datasources/"+datasourceID+"/row-security-policies/"+id, nil, nil)
}

// MaskingPolicy is nested under a datasource and has no GET /{id}.
type MaskingPolicy struct {
	ID               string            `json:"id"`
	DatasourceID     string            `json:"datasource_id"`
	ColumnRef        string            `json:"column_ref"`
	Strategy         string            `json:"strategy"`
	StrategyParams   map[string]string `json:"strategy_params"`
	RevealToRoles    []string          `json:"reveal_to_roles"`
	RevealToGroupIDs []string          `json:"reveal_to_group_ids"`
	RevealToUserIDs  []string          `json:"reveal_to_user_ids"`
	Enabled          bool              `json:"enabled"`
}

type MaskingPolicyRequest struct {
	ColumnRef        *string           `json:"column_ref,omitempty"`
	Strategy         *string           `json:"strategy,omitempty"`
	StrategyParams   map[string]string `json:"strategy_params,omitempty"`
	RevealToRoles    []string          `json:"reveal_to_roles,omitempty"`
	RevealToGroupIDs []string          `json:"reveal_to_group_ids,omitempty"`
	RevealToUserIDs  []string          `json:"reveal_to_user_ids,omitempty"`
	Enabled          *bool             `json:"enabled,omitempty"`
}

func (c *Client) CreateMaskingPolicy(ctx context.Context, datasourceID string, req MaskingPolicyRequest) (*MaskingPolicy, error) {
	var out MaskingPolicy
	if err := c.do(ctx, "POST", "/datasources/"+datasourceID+"/masking-policies", req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) GetMaskingPolicy(ctx context.Context, datasourceID, id string) (*MaskingPolicy, error) {
	var list []MaskingPolicy
	if err := c.do(ctx, "GET", "/datasources/"+datasourceID+"/masking-policies", nil, &list); err != nil {
		return nil, err
	}
	for i := range list {
		if list[i].ID == id {
			return &list[i], nil
		}
	}
	return nil, &APIError{StatusCode: 404, Title: "Not Found", Detail: "masking policy " + id + " not found"}
}

func (c *Client) UpdateMaskingPolicy(ctx context.Context, datasourceID, id string, req MaskingPolicyRequest) (*MaskingPolicy, error) {
	var out MaskingPolicy
	if err := c.do(ctx, "PUT", "/datasources/"+datasourceID+"/masking-policies/"+id, req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) DeleteMaskingPolicy(ctx context.Context, datasourceID, id string) error {
	return c.do(ctx, "DELETE", "/datasources/"+datasourceID+"/masking-policies/"+id, nil, nil)
}
