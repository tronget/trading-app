package main

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestClickhouseWriterFlush(t *testing.T) {
	var gotBody string
	var gotUser string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		gotBody = string(body)
		gotUser = r.Header.Get("X-ClickHouse-User")
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	metrics := &Metrics{}
	addr := strings.Replace(server.URL, "http://", "http://trading:trading@", 1) + "/trading"
	writer, err := NewClickhouseWriter(addr, metrics)
	if err != nil {
		t.Fatalf("NewClickhouseWriter: %v", err)
	}

	at := time.Date(2026, 6, 12, 12, 0, 0, 0, time.UTC)
	writer.flush(context.Background(), []StockTick{
		{Symbol: "SBER", Price: 300, Volume: 1, Seq: 1, ReceivedAtUTC: at},
		{Symbol: "GAZP", Price: 150, Volume: 2, Seq: 2, ReceivedAtUTC: at},
	})

	if metrics.ClickhouseRows.Load() != 2 {
		t.Fatalf("rows written = %d, want 2", metrics.ClickhouseRows.Load())
	}
	if gotUser != "trading" {
		t.Fatalf("user header = %q", gotUser)
	}
	if !strings.Contains(gotBody, `"symbol":"SBER"`) || !strings.Contains(gotBody, `"symbol":"GAZP"`) {
		t.Fatalf("unexpected body: %s", gotBody)
	}
}

func TestClickhouseWriterFlushError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "table not found", http.StatusInternalServerError)
	}))
	defer server.Close()

	metrics := &Metrics{}
	writer, err := NewClickhouseWriter(server.URL+"/trading", metrics)
	if err != nil {
		t.Fatalf("NewClickhouseWriter: %v", err)
	}

	writer.flush(context.Background(), []StockTick{{Symbol: "SBER", ReceivedAtUTC: time.Now()}})

	if metrics.ClickhouseFails.Load() != 1 {
		t.Fatalf("errors = %d, want 1", metrics.ClickhouseFails.Load())
	}
	if metrics.ClickhouseRows.Load() != 0 {
		t.Fatalf("rows must stay 0 on error, got %d", metrics.ClickhouseRows.Load())
	}
}
