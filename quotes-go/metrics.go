package main

import (
	"context"
	"fmt"
	"net/http"
	"sync/atomic"
	"time"
)

// Metrics — счётчики сервиса, экспонируются в формате Prometheus на /metrics.
type Metrics struct {
	TicksTotal      atomic.Uint64
	TicksDropped    atomic.Uint64
	RedisPublished  atomic.Uint64
	RedisErrors     atomic.Uint64
	ClickhouseRows  atomic.Uint64
	ClickhouseFails atomic.Uint64

	ticksPerSecond atomic.Uint64
}

// RunRateLoop раз в секунду пересчитывает «тиков/сек».
func (m *Metrics) RunRateLoop(ctx context.Context) {
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()

	previous := m.TicksTotal.Load()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			current := m.TicksTotal.Load()
			m.ticksPerSecond.Store(current - previous)
			previous = current
		}
	}
}

func (m *Metrics) TicksPerSecond() uint64 {
	return m.ticksPerSecond.Load()
}

func (m *Metrics) Handler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain; version=0.0.4")
		fmt.Fprintf(w, "# HELP quotes_ticks_total Ticks read from devices/simulator.\n")
		fmt.Fprintf(w, "# TYPE quotes_ticks_total counter\n")
		fmt.Fprintf(w, "quotes_ticks_total %d\n", m.TicksTotal.Load())
		fmt.Fprintf(w, "# HELP quotes_ticks_per_second Ticks processed during the last second.\n")
		fmt.Fprintf(w, "# TYPE quotes_ticks_per_second gauge\n")
		fmt.Fprintf(w, "quotes_ticks_per_second %d\n", m.TicksPerSecond())
		fmt.Fprintf(w, "# HELP quotes_ticks_dropped_total Ticks dropped because a sink buffer was full.\n")
		fmt.Fprintf(w, "# TYPE quotes_ticks_dropped_total counter\n")
		fmt.Fprintf(w, "quotes_ticks_dropped_total %d\n", m.TicksDropped.Load())
		fmt.Fprintf(w, "# HELP quotes_redis_published_total Ticks published to Redis quotes:ticks.\n")
		fmt.Fprintf(w, "# TYPE quotes_redis_published_total counter\n")
		fmt.Fprintf(w, "quotes_redis_published_total %d\n", m.RedisPublished.Load())
		fmt.Fprintf(w, "# HELP quotes_redis_errors_total Redis publish errors.\n")
		fmt.Fprintf(w, "# TYPE quotes_redis_errors_total counter\n")
		fmt.Fprintf(w, "quotes_redis_errors_total %d\n", m.RedisErrors.Load())
		fmt.Fprintf(w, "# HELP quotes_clickhouse_rows_total Rows written to ClickHouse ticks table.\n")
		fmt.Fprintf(w, "# TYPE quotes_clickhouse_rows_total counter\n")
		fmt.Fprintf(w, "quotes_clickhouse_rows_total %d\n", m.ClickhouseRows.Load())
		fmt.Fprintf(w, "# HELP quotes_clickhouse_errors_total ClickHouse flush errors.\n")
		fmt.Fprintf(w, "# TYPE quotes_clickhouse_errors_total counter\n")
		fmt.Fprintf(w, "quotes_clickhouse_errors_total %d\n", m.ClickhouseFails.Load())
	})
}
