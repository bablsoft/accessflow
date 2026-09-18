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
	"strconv"
	"strings"
	"time"
)

// Every API key is rate-limited per identity (#873): over the cap the server answers 429 with a
// Retry-After header. The client retries such a call — the request never reached the controller,
// so a replay is safe — but keeps the wait bounded so a plan/apply cannot hang on a long window.
const (
	// maxRateLimitRetries is how many 429s one call absorbs before surfacing the error.
	maxRateLimitRetries = 5
	// maxRetryAfter caps a single honoured Retry-After; a longer window (the daily cap, say)
	// fails fast rather than sleeping it out.
	maxRetryAfter = 2 * time.Minute
	// defaultRetryAfter is the pause when a 429 carries no usable Retry-After.
	defaultRetryAfter = 5 * time.Second
)

// Client talks to an AccessFlow instance's /api/v1 surface with an API key.
type Client struct {
	baseURL    string
	apiKey     string
	httpClient *http.Client
	// sleep waits out a Retry-After (context-aware); tests swap it to avoid real delays.
	sleep func(ctx context.Context, d time.Duration) error
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
		sleep:      sleepCtx,
	}
}

func sleepCtx(ctx context.Context, d time.Duration) error {
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
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
	var encoded []byte
	if body != nil {
		var err error
		if encoded, err = json.Marshal(body); err != nil {
			return fmt.Errorf("marshalling request body: %w", err)
		}
	}

	for retries := 0; ; retries++ {
		status, header, raw, err := c.send(ctx, method, path, encoded)
		if err != nil {
			return err
		}
		if status == http.StatusTooManyRequests && retries < maxRateLimitRetries {
			wait, ok := retryAfter(header)
			if ok {
				if err := c.sleep(ctx, wait); err != nil {
					return fmt.Errorf("waiting to retry rate-limited %s %s: %w", method, path, err)
				}
				continue
			}
		}
		if status >= 400 {
			return parseAPIError(status, raw)
		}
		if out != nil && len(raw) > 0 {
			if err := json.Unmarshal(raw, out); err != nil {
				return fmt.Errorf("decoding response from %s %s: %w", method, path, err)
			}
		}
		return nil
	}
}

// send performs one attempt and returns the status, headers and body. A fresh request is built per
// attempt so a retried body is re-sent from the start.
func (c *Client) send(ctx context.Context, method, path string, encoded []byte) (int, http.Header, []byte, error) {
	var reader io.Reader
	if encoded != nil {
		reader = bytes.NewReader(encoded)
	}
	req, err := http.NewRequestWithContext(ctx, method, c.baseURL+"/api/v1"+path, reader)
	if err != nil {
		return 0, nil, nil, fmt.Errorf("building request: %w", err)
	}
	req.Header.Set("Authorization", "ApiKey "+c.apiKey)
	req.Header.Set("Accept", "application/json")
	if encoded != nil {
		req.Header.Set("Content-Type", "application/json")
	}

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return 0, nil, nil, fmt.Errorf("calling %s %s: %w", method, path, err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, resp.Header, raw, nil
}

// retryAfter reads a 429's Retry-After — delay-seconds (what AccessFlow sends) or an HTTP-date —
// and reports whether the call should be retried after that wait: false when the header is
// longer than maxRetryAfter, so a long window surfaces as the 429 instead of a stalled apply. A
// missing or unparseable header falls back to defaultRetryAfter.
func retryAfter(header http.Header) (time.Duration, bool) {
	value := strings.TrimSpace(header.Get("Retry-After"))
	wait := defaultRetryAfter
	if secs, err := strconv.Atoi(value); err == nil && secs >= 0 {
		wait = time.Duration(secs) * time.Second
	} else if at, err := http.ParseTime(value); err == nil {
		wait = time.Until(at)
		if wait < 0 {
			wait = 0
		}
	}
	return wait, wait <= maxRetryAfter
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
