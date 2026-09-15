package client

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
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
