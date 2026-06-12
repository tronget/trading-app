package main

import (
	"strings"
	"testing"
	"time"
)

func TestParseSymbolMap(t *testing.T) {
	symbols, err := ParseSymbolMap("0:SBER, 1:gazp ,3:BTC")
	if err != nil {
		t.Fatalf("ParseSymbolMap returned error: %v", err)
	}
	if symbols.Symbol(0) != "SBER" {
		t.Fatalf("unexpected symbol for 0: %s", symbols.Symbol(0))
	}
	if symbols.Symbol(1) != "GAZP" {
		t.Fatalf("symbol must be upper-cased, got %s", symbols.Symbol(1))
	}
	if symbols.Symbol(2) != "DEV2" {
		t.Fatalf("unknown index must fall back to DEV<idx>, got %s", symbols.Symbol(2))
	}
}

func TestParseSymbolMapDefault(t *testing.T) {
	symbols, err := ParseSymbolMap("")
	if err != nil {
		t.Fatalf("default mapping must parse: %v", err)
	}
	if symbols.Symbol(0) != "SBER" || symbols.Symbol(3) != "BTC" {
		t.Fatalf("unexpected default mapping: %v", symbols)
	}
}

func TestParseSymbolMapRejectsInvalid(t *testing.T) {
	for _, raw := range []string{"SBER", "x:SBER", "-1:SBER", "0:", "0:SBER,0:GAZP"} {
		if _, err := ParseSymbolMap(raw); err == nil {
			t.Fatalf("expected error for %q", raw)
		}
	}
}

func TestPipelineAssignsSymbolAndFansOut(t *testing.T) {
	store := NewTickStore([]string{"/dev/stock0"}, 8)
	symbols := SymbolMap{0: "SBER"}
	metrics := &Metrics{}
	pipeline := NewPipeline(store, symbols, metrics)

	sink := make(chan StockTick, 1)
	pipeline.AddSink(sink)

	pipeline.Process(StockTick{Seq: 1, DeviceIndex: 0, Price: 101.5})

	select {
	case tick := <-sink:
		if tick.Symbol != "SBER" {
			t.Fatalf("sink tick must carry symbol, got %q", tick.Symbol)
		}
	default:
		t.Fatal("tick was not delivered to sink")
	}

	latest, ok := store.Latest(nil)
	if !ok || latest[0].Symbol != "SBER" {
		t.Fatalf("store tick must carry symbol: %+v", latest)
	}
	if metrics.TicksTotal.Load() != 1 {
		t.Fatalf("ticks total = %d, want 1", metrics.TicksTotal.Load())
	}
}

func TestPipelineDropsWhenSinkFull(t *testing.T) {
	store := NewTickStore([]string{"/dev/stock0"}, 8)
	metrics := &Metrics{}
	pipeline := NewPipeline(store, SymbolMap{0: "SBER"}, metrics)

	sink := make(chan StockTick) // небуферизованный, читателя нет
	pipeline.AddSink(sink)

	pipeline.Process(StockTick{Seq: 1, DeviceIndex: 0})

	if metrics.TicksDropped.Load() != 1 {
		t.Fatalf("dropped = %d, want 1", metrics.TicksDropped.Load())
	}
}

func TestNewTickMessage(t *testing.T) {
	at := time.Date(2026, 6, 12, 10, 30, 0, 500_000_000, time.UTC)
	msg := NewTickMessage(StockTick{
		Symbol:        "AAPL",
		Price:         199.99,
		Volume:        42,
		Seq:           7,
		ReceivedAtUTC: at,
	})
	if msg.Symbol != "AAPL" || msg.Price != 199.99 || msg.Volume != 42 || msg.Seq != 7 {
		t.Fatalf("unexpected message: %+v", msg)
	}
	if msg.TS != at.UnixMilli() {
		t.Fatalf("ts = %d, want %d", msg.TS, at.UnixMilli())
	}
}

func TestEncodeTickRows(t *testing.T) {
	at := time.Date(2026, 6, 12, 10, 30, 1, 123_000_000, time.UTC)
	rows := string(encodeTickRows([]StockTick{
		{Symbol: "SBER", Price: 305.5, Volume: 10, Seq: 1, ReceivedAtUTC: at},
		{Symbol: "BTC", Price: 64000.123456, Volume: 2, Seq: 2, ReceivedAtUTC: at},
	}))

	lines := strings.Split(strings.TrimSpace(rows), "\n")
	if len(lines) != 2 {
		t.Fatalf("expected 2 rows, got %d: %q", len(lines), rows)
	}
	want := `{"symbol":"SBER","ts":"2026-06-12 10:30:01.123","price":305.500000,"volume":10,"seq":1}`
	if lines[0] != want {
		t.Fatalf("row mismatch:\n got %s\nwant %s", lines[0], want)
	}
	if !strings.Contains(lines[1], `"price":64000.123456`) {
		t.Fatalf("price lost precision: %s", lines[1])
	}
}

func TestClickhouseWriterEndpoint(t *testing.T) {
	writer, err := NewClickhouseWriter("http://trading:secret@clickhouse:8123/trading", &Metrics{})
	if err != nil {
		t.Fatalf("NewClickhouseWriter returned error: %v", err)
	}
	if !strings.Contains(writer.endpoint, "database=trading") {
		t.Fatalf("endpoint must carry database: %s", writer.endpoint)
	}
	if writer.user != "trading" || writer.password != "secret" {
		t.Fatalf("credentials not parsed: %q %q", writer.user, writer.password)
	}
	if strings.Contains(writer.endpoint, "secret") {
		t.Fatalf("password must not leak into endpoint: %s", writer.endpoint)
	}

	if _, err := NewClickhouseWriter("clickhouse:9000", &Metrics{}); err == nil {
		t.Fatal("expected error for non-http address")
	}
}
