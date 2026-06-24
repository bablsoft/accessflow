// Package client is a thin REST client for the AccessFlow API used by the Terraform
// provider. It authenticates with an API key (Authorization: ApiKey <key>) and surfaces
// RFC 9457 ProblemDetail errors. All request/response bodies are snake_case JSON.
package client

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// Client talks to an AccessFlow instance's /api/v1 surface with an API key.
type Client struct {
	baseURL    string
	apiKey     string
	httpClient *http.Client
}

// New builds a Client. endpoint is the AccessFlow base URL (e.g. https://accessflow.example);
// the /api/v1 prefix is added automatically. A trailing slash on endpoint is tolerated.
func New(endpoint, apiKey string, httpClient *http.Client) *Client {
	if httpClient == nil {
		httpClient = &http.Client{Timeout: 60 * time.Second}
	}
	return &Client{
		baseURL:    strings.TrimRight(endpoint, "/"),
		apiKey:     apiKey,
		httpClient: httpClient,
	}
}

// APIError is a typed error carrying the HTTP status and the parsed ProblemDetail fields.
type APIError struct {
	StatusCode int
	Title      string
	Detail     string
	ErrorCode  string
	Body       string
}

func (e *APIError) Error() string {
	parts := make([]string, 0, 3)
	if e.Title != "" {
		parts = append(parts, e.Title)
	}
	if e.ErrorCode != "" {
		parts = append(parts, "["+e.ErrorCode+"]")
	}
	if e.Detail != "" {
		parts = append(parts, e.Detail)
	}
	if len(parts) == 0 {
		parts = append(parts, e.Body)
	}
	return fmt.Sprintf("accessflow API error (HTTP %d): %s", e.StatusCode, strings.Join(parts, " "))
}

// IsNotFound reports whether err is an APIError with HTTP 404 — used by Read to drop a
// resource from state when it has been deleted out-of-band.
func IsNotFound(err error) bool {
	var apiErr *APIError
	if e, ok := err.(*APIError); ok {
		apiErr = e
	}
	return apiErr != nil && apiErr.StatusCode == http.StatusNotFound
}

func (c *Client) do(ctx context.Context, method, path string, body any, out any) error {
	var reader io.Reader
	if body != nil {
		encoded, err := json.Marshal(body)
		if err != nil {
			return fmt.Errorf("marshalling request body: %w", err)
		}
		reader = bytes.NewReader(encoded)
	}

	req, err := http.NewRequestWithContext(ctx, method, c.baseURL+"/api/v1"+path, reader)
	if err != nil {
		return fmt.Errorf("building request: %w", err)
	}
	req.Header.Set("Authorization", "ApiKey "+c.apiKey)
	req.Header.Set("Accept", "application/json")
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("calling %s %s: %w", method, path, err)
	}
	defer resp.Body.Close()

	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode >= 400 {
		return parseAPIError(resp.StatusCode, raw)
	}
	if out != nil && len(raw) > 0 {
		if err := json.Unmarshal(raw, out); err != nil {
			return fmt.Errorf("decoding response from %s %s: %w", method, path, err)
		}
	}
	return nil
}

func parseAPIError(status int, raw []byte) *APIError {
	apiErr := &APIError{StatusCode: status, Body: string(raw)}
	var pd struct {
		Title  string `json:"title"`
		Detail string `json:"detail"`
		Error  string `json:"error"`
	}
	if json.Unmarshal(raw, &pd) == nil {
		apiErr.Title = pd.Title
		apiErr.Detail = pd.Detail
		apiErr.ErrorCode = pd.Error
	}
	return apiErr
}
