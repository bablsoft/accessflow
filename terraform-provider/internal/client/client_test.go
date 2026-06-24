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
