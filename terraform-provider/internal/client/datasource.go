package client

import "context"

// Datasource is the API response shape for a datasource. Write-only fields (password,
// each read_replicas[].password, api_key) are never returned and are absent here.
type Datasource struct {
	ID                  string  `json:"id"`
	OrganizationID      string  `json:"organization_id"`
	Name                string  `json:"name"`
	DBType              string  `json:"db_type"`
	Host                *string `json:"host"`
	Port                *int64  `json:"port"`
	DatabaseName        *string `json:"database_name"`
	Username            *string `json:"username"`
	SSLMode             string  `json:"ssl_mode"`
	ConnectionPoolSize  *int64  `json:"connection_pool_size"`
	MaxRowsPerQuery     *int64  `json:"max_rows_per_query"`
	RequireReviewReads  bool    `json:"require_review_reads"`
	RequireReviewWrites bool    `json:"require_review_writes"`
	ReviewPlanID        *string `json:"review_plan_id"`
	AIAnalysisEnabled   bool    `json:"ai_analysis_enabled"`
	AIConfigID          *string `json:"ai_config_id"`
	TextToSQLEnabled    bool    `json:"text_to_sql_enabled"`
	JDBCURLOverride     *string `json:"jdbc_url_override"`
	LocalDatacenter     *string `json:"local_datacenter"`
	Active              bool    `json:"active"`
}

// DatasourceRequest is the create/update body. Pointer + omitempty so only configured
// attributes are sent; the secret fields are write-only.
type DatasourceRequest struct {
	Name                *string `json:"name,omitempty"`
	DBType              *string `json:"db_type,omitempty"`
	Host                *string `json:"host,omitempty"`
	Port                *int64  `json:"port,omitempty"`
	DatabaseName        *string `json:"database_name,omitempty"`
	Username            *string `json:"username,omitempty"`
	Password            *string `json:"password,omitempty"`
	SSLMode             *string `json:"ssl_mode,omitempty"`
	ConnectionPoolSize  *int64  `json:"connection_pool_size,omitempty"`
	MaxRowsPerQuery     *int64  `json:"max_rows_per_query,omitempty"`
	RequireReviewReads  *bool   `json:"require_review_reads,omitempty"`
	RequireReviewWrites *bool   `json:"require_review_writes,omitempty"`
	ReviewPlanID        *string `json:"review_plan_id,omitempty"`
	AIAnalysisEnabled   *bool   `json:"ai_analysis_enabled,omitempty"`
	AIConfigID          *string `json:"ai_config_id,omitempty"`
	TextToSQLEnabled    *bool   `json:"text_to_sql_enabled,omitempty"`
	JDBCURLOverride     *string `json:"jdbc_url_override,omitempty"`
	LocalDatacenter     *string `json:"local_datacenter,omitempty"`
	Active              *bool   `json:"active,omitempty"`
}

func (c *Client) CreateDatasource(ctx context.Context, req DatasourceRequest) (*Datasource, error) {
	var out Datasource
	if err := c.do(ctx, "POST", "/datasources", req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) GetDatasource(ctx context.Context, id string) (*Datasource, error) {
	var out Datasource
	if err := c.do(ctx, "GET", "/datasources/"+id, nil, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) UpdateDatasource(ctx context.Context, id string, req DatasourceRequest) (*Datasource, error) {
	var out Datasource
	if err := c.do(ctx, "PUT", "/datasources/"+id, req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) DeleteDatasource(ctx context.Context, id string) error {
	return c.do(ctx, "DELETE", "/datasources/"+id, nil, nil)
}
