package main

import (
	"fmt"
	"sort"
	"sync"
	"time"
)

// ActionStats — латентности и ошибки одного типа действия.
type ActionStats struct {
	mu        sync.Mutex
	latencies []float64 // миллисекунды
	errors    int
}

func (s *ActionStats) Record(d time.Duration, err bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if err {
		s.errors++
		return
	}
	s.latencies = append(s.latencies, float64(d.Microseconds())/1000)
}

// Summary — итоговые показатели действия.
type Summary struct {
	Action string  `json:"action"`
	Count  int     `json:"count"`
	Errors int     `json:"errors"`
	P50    float64 `json:"p50Ms"`
	P95    float64 `json:"p95Ms"`
	P99    float64 `json:"p99Ms"`
	Max    float64 `json:"maxMs"`
}

func (s *ActionStats) Summarize(action string) Summary {
	s.mu.Lock()
	defer s.mu.Unlock()
	sorted := make([]float64, len(s.latencies))
	copy(sorted, s.latencies)
	sort.Float64s(sorted)
	return Summary{
		Action: action,
		Count:  len(sorted),
		Errors: s.errors,
		P50:    percentile(sorted, 0.50),
		P95:    percentile(sorted, 0.95),
		P99:    percentile(sorted, 0.99),
		Max:    percentile(sorted, 1.0),
	}
}

// percentile ожидает отсортированный срез; p в диапазоне [0,1].
func percentile(sorted []float64, p float64) float64 {
	if len(sorted) == 0 {
		return 0
	}
	idx := int(p*float64(len(sorted))) - 1
	if idx < 0 {
		idx = 0
	}
	if idx >= len(sorted) {
		idx = len(sorted) - 1
	}
	return sorted[idx]
}

// Metrics — реестр статистик по действиям + счётчики прогресса.
type Metrics struct {
	mu      sync.Mutex
	actions map[string]*ActionStats
}

func NewMetrics() *Metrics {
	return &Metrics{actions: make(map[string]*ActionStats)}
}

func (m *Metrics) stats(action string) *ActionStats {
	m.mu.Lock()
	defer m.mu.Unlock()
	s, ok := m.actions[action]
	if !ok {
		s = &ActionStats{}
		m.actions[action] = s
	}
	return s
}

func (m *Metrics) Record(action string, d time.Duration, err bool) {
	m.stats(action).Record(d, err)
}

func (m *Metrics) Summaries() []Summary {
	m.mu.Lock()
	names := make([]string, 0, len(m.actions))
	for name := range m.actions {
		names = append(names, name)
	}
	m.mu.Unlock()
	sort.Strings(names)

	result := make([]Summary, 0, len(names))
	for _, name := range names {
		result = append(result, m.stats(name).Summarize(name))
	}
	return result
}

func (m *Metrics) PrintTable() {
	fmt.Printf("%-14s %10s %8s %10s %10s %10s %10s\n",
		"action", "count", "errors", "p50,ms", "p95,ms", "p99,ms", "max,ms")
	total, errors := 0, 0
	for _, s := range m.Summaries() {
		fmt.Printf("%-14s %10d %8d %10.1f %10.1f %10.1f %10.1f\n",
			s.Action, s.Count, s.Errors, s.P50, s.P95, s.P99, s.Max)
		total += s.Count + s.Errors
		errors += s.Errors
	}
	rate := 0.0
	if total > 0 {
		rate = float64(errors) / float64(total) * 100
	}
	fmt.Printf("total requests: %d, errors: %d (%.2f%%)\n", total, errors, rate)
}
