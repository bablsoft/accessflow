package client

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestCreateDatasource_SendsAuthAndBody(t *testing.T) {
	var gotAuth, gotPath, gotMethod, gotContentType string
	var gotBody DatasourceRequest
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth = r.Header.Get("Authorization")
		gotContentType = r.Header.Get("Content-Type")
		gotPath = r.URL.Path
		gotMethod = r.Method
		_ = json.NewDecoder(r.Body).Decode(&gotBody)
		w.WriteHeader(http.StatusCreated)
		_, _ = w.Write([]byte(`{"id":"ds-1","organization_id":"org-1","name":"prod","db_type":"POSTGRESQL","ssl_mode":"DISABLE","active":true}`))
	}))
	defer srv.Close()

	c := New(srv.URL, "af_secret", srv.Client())
	name := "prod"
	ds, err := c.CreateDatasource(context.Background(), DatasourceRequest{Name: &name})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if gotAuth != "ApiKey af_secret" {
		t.Errorf("auth header = %q, want %q", gotAuth, "ApiKey af_secret")
	}
	if gotContentType != "application/json" {
		t.Errorf("content-type = %q", gotContentType)
	}
	if gotMethod != http.MethodPost || gotPath != "/api/v1/datasources" {
		t.Errorf("got %s %s", gotMethod, gotPath)
	}
	if gotBody.Name == nil || *gotBody.Name != "prod" {
		t.Errorf("body name not sent: %+v", gotBody)
	}
	if ds.ID != "ds-1" || ds.Name != "prod" || !ds.Active {
		t.Errorf("unexpected response: %+v", ds)
	}
}

func TestGetDatasource_NotFoundParsed(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte(`{"title":"Not Found","detail":"no such datasource","error":"NOT_FOUND"}`))
	}))
	defer srv.Close()

	c := New(srv.URL+"/", "k", srv.Client()) // trailing slash tolerated
	_, err := c.GetDatasource(context.Background(), "missing")
	if err == nil {
		t.Fatal("expected error")
	}
	if !IsNotFound(err) {
		t.Errorf("IsNotFound = false, want true (err=%v)", err)
	}
	apiErr, ok := err.(*APIError)
	if !ok {
		t.Fatalf("want *APIError, got %T", err)
	}
	if apiErr.ErrorCode != "NOT_FOUND" || apiErr.Detail != "no such datasource" {
		t.Errorf("unexpected APIError fields: %+v", apiErr)
	}
}

func TestAPIError_MessageIncludesFields(t *testing.T) {
	err := &APIError{StatusCode: 422, Title: "Unprocessable", ErrorCode: "SQL_PARSE_ERROR", Detail: "bad"}
	msg := err.Error()
	for _, want := range []string{"422", "Unprocessable", "SQL_PARSE_ERROR", "bad"} {
		if !contains(msg, want) {
			t.Errorf("error message %q missing %q", msg, want)
		}
	}
}

func TestGetNotificationChannel_FindsByIDOrNotFound(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(`[{"id":"c1","name":"ops","channel_type":"SLACK","active":true},{"id":"c2","name":"sec","channel_type":"EMAIL","active":true}]`))
	}))
	defer srv.Close()

	c := New(srv.URL, "k", srv.Client())
	ch, err := c.GetNotificationChannel(context.Background(), "c2")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if ch.Name != "sec" {
		t.Errorf("got channel %+v", ch)
	}
	_, err = c.GetNotificationChannel(context.Background(), "nope")
	if !IsNotFound(err) {
		t.Errorf("expected not-found for unknown id, got %v", err)
	}
}

func contains(s, sub string) bool {
	return len(sub) == 0 || (len(s) >= len(sub) && indexOf(s, sub) >= 0)
}

func indexOf(s, sub string) int {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return i
		}
	}
	return -1
}

func TestCreateSqlReviewRuleset_SendsRulesAndParams(t *testing.T) {
	var gotPath, gotMethod string
	var gotBody map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		gotMethod = r.Method
		_ = json.NewDecoder(r.Body).Decode(&gotBody)
		w.WriteHeader(http.StatusCreated)
		_, _ = w.Write([]byte(`{"id":"rs-1","organization_id":"org-1","name":"Production","environment":"PRODUCTION","enabled":true,
			"rules":[{"rule_id":"protected_table","severity":"BLOCK","params":{"globs":["payroll.*"]}},
			         {"rule_id":"select_star","severity":"OFF","params":{}}],
			"created_at":"2026-09-15T10:00:00Z","updated_at":"2026-09-15T10:00:00Z"}`))
	}))
	defer srv.Close()

	c := New(srv.URL, "k", srv.Client())
	name, env := "Production", "PRODUCTION"
	rs, err := c.CreateSqlReviewRuleset(context.Background(), SqlReviewRulesetRequest{
		Name:        &name,
		Environment: &env,
		Rules: []SqlReviewRuleConfig{
			{RuleID: "select_star", Severity: "OFF"},
			{RuleID: "protected_table", Severity: "BLOCK", Params: map[string][]string{"globs": {"payroll.*"}}},
		},
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if gotMethod != http.MethodPost || gotPath != "/api/v1/admin/sql-review-rulesets" {
		t.Errorf("got %s %s", gotMethod, gotPath)
	}
	rules, _ := gotBody["rules"].([]any)
	if len(rules) != 2 {
		t.Fatalf("rules not sent: %+v", gotBody)
	}
	first, _ := rules[0].(map[string]any)
	if first["rule_id"] != "select_star" || first["severity"] != "OFF" {
		t.Errorf("first rule = %+v", first)
	}
	if _, present := first["params"]; present {
		t.Errorf("empty params must be omitted, got %+v", first)
	}
	second, _ := rules[1].(map[string]any)
	if params, _ := second["params"].(map[string]any); params == nil || params["globs"] == nil {
		t.Errorf("params not sent: %+v", second)
	}
	if _, present := gotBody["description"]; present {
		t.Errorf("unset description must be omitted: %+v", gotBody)
	}
	if rs.ID != "rs-1" || rs.Environment == nil || *rs.Environment != "PRODUCTION" || !rs.Enabled {
		t.Errorf("unexpected response: %+v", rs)
	}
	if len(rs.Rules) != 2 || rs.Rules[0].Params["globs"][0] != "payroll.*" {
		t.Errorf("rules not decoded: %+v", rs.Rules)
	}
	if rs.Rules[1].Params == nil || len(rs.Rules[1].Params) != 0 {
		t.Errorf("empty params object should decode to an empty map: %+v", rs.Rules[1])
	}
	if rs.Description != nil {
		t.Errorf("absent description should be nil, got %q", *rs.Description)
	}
}

func TestSqlReviewRuleset_GetUpdateDelete_Paths(t *testing.T) {
	var calls []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls = append(calls, r.Method+" "+r.URL.Path)
		if r.Method == http.MethodDelete {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		_, _ = w.Write([]byte(`{"id":"rs-1","organization_id":"org-1","name":"Default","enabled":false,"rules":[]}`))
	}))
	defer srv.Close()

	c := New(srv.URL, "k", srv.Client())
	ctx := context.Background()
	rs, err := c.GetSqlReviewRuleset(ctx, "rs-1")
	if err != nil || rs.Environment != nil || rs.Enabled || len(rs.Rules) != 0 {
		t.Fatalf("get: err=%v rs=%+v", err, rs)
	}
	if _, err := c.UpdateSqlReviewRuleset(ctx, "rs-1", SqlReviewRulesetRequest{}); err != nil {
		t.Fatalf("update: %v", err)
	}
	if err := c.DeleteSqlReviewRuleset(ctx, "rs-1"); err != nil {
		t.Fatalf("delete: %v", err)
	}
	want := []string{"GET /api/v1/admin/sql-review-rulesets/rs-1", "PUT /api/v1/admin/sql-review-rulesets/rs-1", "DELETE /api/v1/admin/sql-review-rulesets/rs-1"}
	if len(calls) != len(want) {
		t.Fatalf("calls = %v", calls)
	}
	for i := range want {
		if calls[i] != want[i] {
			t.Errorf("call %d = %q, want %q", i, calls[i], want[i])
		}
	}
}

func TestUpdateDatasource_SendsClearEnvironment(t *testing.T) {
	var gotBody map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewDecoder(r.Body).Decode(&gotBody)
		_, _ = w.Write([]byte(`{"id":"ds-1","organization_id":"org-1","name":"prod","db_type":"POSTGRESQL","ssl_mode":"DISABLE","active":true}`))
	}))
	defer srv.Close()

	c := New(srv.URL, "k", srv.Client())
	clear := true
	ds, err := c.UpdateDatasource(context.Background(), "ds-1", DatasourceRequest{ClearEnvironment: &clear})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if gotBody["clear_environment"] != true {
		t.Errorf("clear_environment not sent: %+v", gotBody)
	}
	if _, present := gotBody["environment"]; present {
		t.Errorf("nil environment must be omitted: %+v", gotBody)
	}
	if ds.Environment != nil {
		t.Errorf("absent environment should be nil, got %q", *ds.Environment)
	}
}

// rateLimited serves `limited` 429s (each carrying retryAfterHeader when non-empty) before the
// given success body, recording every attempt's body and the sleeps the client asked for.
type rateLimited struct {
	limited          int
	retryAfterHeader string
	success          string
	attempts         []string
	sleeps           []time.Duration
}

func (r *rateLimited) handler(w http.ResponseWriter, req *http.Request) {
	raw, _ := io.ReadAll(req.Body)
	r.attempts = append(r.attempts, string(raw))
	if len(r.attempts) <= r.limited {
		if r.retryAfterHeader != "" {
			w.Header().Set("Retry-After", r.retryAfterHeader)
		}
		w.WriteHeader(http.StatusTooManyRequests)
		_, _ = w.Write([]byte(`{"title":"Too Many Requests","error":"SERVICE_ACCOUNT_RATE_LIMIT_EXCEEDED","detail":"limit is 120 per minute"}`))
		return
	}
	_, _ = w.Write([]byte(r.success))
}

func (r *rateLimited) client(t *testing.T) (*Client, func()) {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(r.handler))
	c := New(srv.URL, "k", srv.Client())
	c.sleep = func(_ context.Context, d time.Duration) error {
		r.sleeps = append(r.sleeps, d)
		return nil
	}
	return c, srv.Close
}

func TestDo_RetriesA429HonouringRetryAfter(t *testing.T) {
	r := &rateLimited{limited: 2, retryAfterHeader: "3", success: `{"id":"ds-1","name":"prod","active":true}`}
	c, closeSrv := r.client(t)
	defer closeSrv()

	name := "prod"
	ds, err := c.CreateDatasource(context.Background(), DatasourceRequest{Name: &name})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if ds.ID != "ds-1" {
		t.Errorf("unexpected response: %+v", ds)
	}
	if len(r.attempts) != 3 {
		t.Fatalf("attempts = %d, want 3", len(r.attempts))
	}
	for i, body := range r.attempts {
		if !contains(body, `"name":"prod"`) {
			t.Errorf("attempt %d did not re-send the body: %q", i, body)
		}
	}
	if len(r.sleeps) != 2 || r.sleeps[0] != 3*time.Second || r.sleeps[1] != 3*time.Second {
		t.Errorf("sleeps = %v, want [3s 3s]", r.sleeps)
	}
}

func TestDo_429WithoutRetryAfterUsesTheFallback(t *testing.T) {
	r := &rateLimited{limited: 1, success: `{"id":"ds-1","name":"prod","active":true}`}
	c, closeSrv := r.client(t)
	defer closeSrv()

	if _, err := c.GetDatasource(context.Background(), "ds-1"); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(r.sleeps) != 1 || r.sleeps[0] != defaultRetryAfter {
		t.Errorf("sleeps = %v, want [%v]", r.sleeps, defaultRetryAfter)
	}
}

func TestDo_429WithHTTPDateRetryAfterIsHonoured(t *testing.T) {
	at := time.Now().Add(20 * time.Second).UTC().Format(http.TimeFormat)
	r := &rateLimited{limited: 1, retryAfterHeader: at, success: `{"id":"ds-1","name":"prod","active":true}`}
	c, closeSrv := r.client(t)
	defer closeSrv()

	if _, err := c.GetDatasource(context.Background(), "ds-1"); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(r.sleeps) != 1 || r.sleeps[0] <= 15*time.Second || r.sleeps[0] > 20*time.Second {
		t.Errorf("sleeps = %v, want one wait of roughly 20s", r.sleeps)
	}
}

func TestDo_429BeyondTheRetryAfterCapFailsFast(t *testing.T) {
	r := &rateLimited{limited: 1, retryAfterHeader: "3600", success: `{}`}
	c, closeSrv := r.client(t)
	defer closeSrv()

	_, err := c.GetDatasource(context.Background(), "ds-1")
	apiErr, ok := err.(*APIError)
	if !ok || apiErr.StatusCode != http.StatusTooManyRequests || apiErr.ErrorCode != "SERVICE_ACCOUNT_RATE_LIMIT_EXCEEDED" {
		t.Fatalf("want a 429 APIError, got %v", err)
	}
	if len(r.attempts) != 1 || len(r.sleeps) != 0 {
		t.Errorf("attempts = %d, sleeps = %v — a %v window must not be waited out", len(r.attempts), r.sleeps, time.Hour)
	}
}

func TestDo_Persistent429SurfacesAfterBoundedRetries(t *testing.T) {
	r := &rateLimited{limited: 100, retryAfterHeader: "1", success: `{}`}
	c, closeSrv := r.client(t)
	defer closeSrv()

	_, err := c.GetDatasource(context.Background(), "ds-1")
	apiErr, ok := err.(*APIError)
	if !ok || apiErr.StatusCode != http.StatusTooManyRequests {
		t.Fatalf("want a 429 APIError, got %v", err)
	}
	if !contains(err.Error(), "limit is 120 per minute") {
		t.Errorf("error should carry the ProblemDetail: %v", err)
	}
	if len(r.attempts) != maxRateLimitRetries+1 || len(r.sleeps) != maxRateLimitRetries {
		t.Errorf("attempts = %d, sleeps = %d — want %d retries", len(r.attempts), len(r.sleeps), maxRateLimitRetries)
	}
}

func TestDo_CancelledContextStopsTheRetryWait(t *testing.T) {
	r := &rateLimited{limited: 1, retryAfterHeader: "30", success: `{}`}
	srv := httptest.NewServer(http.HandlerFunc(r.handler))
	defer srv.Close()
	c := New(srv.URL, "k", srv.Client()) // the real, context-aware sleep

	// The first attempt completes well inside the deadline; the 30s Retry-After wait cannot.
	ctx, cancel := context.WithTimeout(context.Background(), 500*time.Millisecond)
	defer cancel()
	started := time.Now()
	_, err := c.GetDatasource(ctx, "ds-1")
	if err == nil || !contains(err.Error(), "waiting to retry rate-limited GET") || !contains(err.Error(), context.DeadlineExceeded.Error()) {
		t.Fatalf("want a deadline error from the retry wait, got %v", err)
	}
	if time.Since(started) > 5*time.Second {
		t.Errorf("the retry wait ignored the context deadline")
	}
	if len(r.attempts) != 1 {
		t.Errorf("attempts = %d, want 1", len(r.attempts))
	}
}

func TestDo_Other4xxIsNotRetried(t *testing.T) {
	calls := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		calls++
		w.WriteHeader(http.StatusUnprocessableEntity)
		_, _ = w.Write([]byte(`{"title":"Unprocessable","error":"VALIDATION_FAILED"}`))
	}))
	defer srv.Close()
	c := New(srv.URL, "k", srv.Client())
	c.sleep = func(context.Context, time.Duration) error { t.Fatal("must not sleep on a 422"); return nil }

	_, err := c.GetDatasource(context.Background(), "ds-1")
	if apiErr, ok := err.(*APIError); !ok || apiErr.StatusCode != http.StatusUnprocessableEntity {
		t.Fatalf("want a 422 APIError, got %v", err)
	}
	if calls != 1 {
		t.Errorf("calls = %d, want 1", calls)
	}
}
