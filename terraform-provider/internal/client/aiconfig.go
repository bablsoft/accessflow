package client

import "context"

// AIConfig response. The api_key is write-only; the API returns the sentinel "***" when
// a key is set (or null when never set), so it is not authoritative for refresh.
type AIConfig struct {
	ID                   string  `json:"id"`
	OrganizationID       string  `json:"organization_id"`
	Name                 string  `json:"name"`
	Provider             string  `json:"provider"`
	Model                string  `json:"model"`
	Endpoint             *string `json:"endpoint"`
	TimeoutMs            *int64  `json:"timeout_ms"`
	MaxPromptTokens      *int64  `json:"max_prompt_tokens"`
	MaxCompletionTokens  *int64  `json:"max_completion_tokens"`
	SystemPromptTemplate *string `json:"system_prompt_template"`
}

type AIConfigRequest struct {
	Name                 *string `json:"name,omitempty"`
	Provider             *string `json:"provider,omitempty"`
	Model                *string `json:"model,omitempty"`
	Endpoint             *string `json:"endpoint,omitempty"`
	APIKey               *string `json:"api_key,omitempty"`
	TimeoutMs            *int64  `json:"timeout_ms,omitempty"`
	MaxPromptTokens      *int64  `json:"max_prompt_tokens,omitempty"`
	MaxCompletionTokens  *int64  `json:"max_completion_tokens,omitempty"`
	SystemPromptTemplate *string `json:"system_prompt_template,omitempty"`
}

func (c *Client) CreateAIConfig(ctx context.Context, req AIConfigRequest) (*AIConfig, error) {
	var out AIConfig
	if err := c.do(ctx, "POST", "/admin/ai-configs", req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) GetAIConfig(ctx context.Context, id string) (*AIConfig, error) {
	var out AIConfig
	if err := c.do(ctx, "GET", "/admin/ai-configs/"+id, nil, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) UpdateAIConfig(ctx context.Context, id string, req AIConfigRequest) (*AIConfig, error) {
	var out AIConfig
	if err := c.do(ctx, "PUT", "/admin/ai-configs/"+id, req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) DeleteAIConfig(ctx context.Context, id string) error {
	return c.do(ctx, "DELETE", "/admin/ai-configs/"+id, nil, nil)
}
