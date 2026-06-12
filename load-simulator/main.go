// Нагрузочный имитатор (#5): поднимает тысячи виртуальных клиентов,
// каждый регистрируется через Gateway и шлёт случайные запросы
// (котировки/портфель/ордера/история) с заданной интенсивностью.
// Часть клиентов дополнительно держит WebSocket-подписку на тики.
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"math/rand/v2"
	"net/http"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"
)

type RunConfig struct {
	Gateway     string        `json:"gateway"`
	Clients     int           `json:"clients"`
	WSClients   int           `json:"wsClients"`
	Duration    time.Duration `json:"-"`
	Interval    time.Duration `json:"-"`
	RampUp      time.Duration `json:"-"`
	RunID       string        `json:"runId"`
	DurationStr string        `json:"duration"`
	IntervalStr string        `json:"interval"`
}

func main() {
	cfg := RunConfig{}
	flag.StringVar(&cfg.Gateway, "gateway", "http://localhost:8080", "gateway base URL")
	flag.IntVar(&cfg.Clients, "clients", 10000, "number of virtual clients")
	flag.IntVar(&cfg.WSClients, "ws-clients", 1000, "clients that also hold a WebSocket subscription")
	flag.DurationVar(&cfg.Duration, "duration", 60*time.Second, "test duration after ramp-up")
	flag.DurationVar(&cfg.Interval, "interval", 5*time.Second, "delay between requests of one client")
	flag.DurationVar(&cfg.RampUp, "ramp-up", 30*time.Second, "time to start all clients gradually")
	output := flag.String("output", "", "write JSON report to file")
	flag.Parse()

	cfg.RunID = fmt.Sprintf("run%d", time.Now().Unix())
	cfg.DurationStr = cfg.Duration.String()
	cfg.IntervalStr = cfg.Interval.String()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	transport := &http.Transport{
		MaxIdleConns:        cfg.Clients,
		MaxIdleConnsPerHost: cfg.Clients,
		MaxConnsPerHost:     0,
		IdleConnTimeout:     90 * time.Second,
	}
	httpClient := &http.Client{Transport: transport, Timeout: 15 * time.Second}

	metrics := NewMetrics()
	wsStats := &WSStats{}

	log.Printf("starting %d clients (%d with WS) against %s, ramp-up %s, duration %s",
		cfg.Clients, cfg.WSClients, cfg.Gateway, cfg.RampUp, cfg.Duration)

	var wg sync.WaitGroup
	startDelay := time.Duration(0)
	if cfg.Clients > 1 {
		startDelay = cfg.RampUp / time.Duration(cfg.Clients)
	}

	runCtx, cancel := context.WithCancel(ctx)
	for i := 0; i < cfg.Clients; i++ {
		client := &Client{
			id:       i,
			login:    fmt.Sprintf("load_%s_%d", cfg.RunID, i),
			password: "load-test-pass",
			base:     cfg.Gateway,
			http:     httpClient,
			metrics:  metrics,
			scenario: defaultScenario,
			rng:      rand.New(rand.NewPCG(uint64(i), 42)),
		}
		wg.Add(1)
		go func(c *Client, withWS bool) {
			defer wg.Done()
			if withWS {
				go func() {
					// WS стартует после успешного логина
					for c.token == "" && runCtx.Err() == nil {
						time.Sleep(500 * time.Millisecond)
					}
					if runCtx.Err() == nil {
						runWebSocket(runCtx, c.base, c.token, wsStats)
					}
				}()
			}
			c.Run(runCtx, cfg.Interval)
		}(client, i < cfg.WSClients)

		if !sleepCtx(ctx, startDelay) {
			break
		}
	}

	log.Printf("all clients started, running for %s", cfg.Duration)

	progress := time.NewTicker(10 * time.Second)
	deadline := time.After(cfg.Duration)
loop:
	for {
		select {
		case <-ctx.Done():
			break loop
		case <-deadline:
			break loop
		case <-progress.C:
			log.Printf("ws active=%d ticks=%d failures=%d",
				wsStats.Active.Load(), wsStats.TicksReceived.Load(), wsStats.Failures.Load())
		}
	}
	progress.Stop()
	cancel()

	done := make(chan struct{})
	go func() { wg.Wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(15 * time.Second):
		log.Print("some clients did not stop in time")
	}

	fmt.Println()
	metrics.PrintTable()
	fmt.Printf("ws: peak ticks received=%d, connect failures=%d\n",
		wsStats.TicksReceived.Load(), wsStats.Failures.Load())

	if *output != "" {
		report := map[string]any{
			"config":            cfg,
			"actions":           metrics.Summaries(),
			"wsTicksReceived":   wsStats.TicksReceived.Load(),
			"wsConnectFailures": wsStats.Failures.Load(),
			"finishedAt":        time.Now().UTC().Format(time.RFC3339),
		}
		data, _ := json.MarshalIndent(report, "", "  ")
		if err := os.WriteFile(*output, data, 0o644); err != nil {
			log.Printf("write report: %v", err)
		} else {
			log.Printf("report written to %s", *output)
		}
	}
}
