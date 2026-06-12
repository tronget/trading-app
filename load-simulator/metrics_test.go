package main

import (
	"testing"
	"time"
)

func TestPercentile(t *testing.T) {
	sorted := []float64{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}
	if p := percentile(sorted, 0.5); p != 5 {
		t.Fatalf("p50 = %v, want 5", p)
	}
	if p := percentile(sorted, 1.0); p != 10 {
		t.Fatalf("p100 = %v, want 10", p)
	}
	if p := percentile(nil, 0.5); p != 0 {
		t.Fatalf("empty percentile = %v, want 0", p)
	}
}

func TestScenarioPick(t *testing.T) {
	scenario := Scenario{
		{ActionQuotes, 0.5},
		{ActionOrder, 0.5},
	}
	if a := scenario.Pick(0.0); a != ActionQuotes {
		t.Fatalf("pick(0) = %s, want quotes", a)
	}
	if a := scenario.Pick(0.99); a != ActionOrder {
		t.Fatalf("pick(0.99) = %s, want order", a)
	}
}

func TestMetricsSummary(t *testing.T) {
	m := NewMetrics()
	for i := 1; i <= 100; i++ {
		m.Record("quotes", time.Duration(i)*time.Millisecond, false)
	}
	m.Record("quotes", 0, true)

	summaries := m.Summaries()
	if len(summaries) != 1 {
		t.Fatalf("expected one action, got %d", len(summaries))
	}
	s := summaries[0]
	if s.Count != 100 || s.Errors != 1 {
		t.Fatalf("count=%d errors=%d", s.Count, s.Errors)
	}
	if s.P50 < 49 || s.P50 > 51 {
		t.Fatalf("p50 = %v", s.P50)
	}
	if s.P99 < 98 || s.P99 > 100 {
		t.Fatalf("p99 = %v", s.P99)
	}
}
