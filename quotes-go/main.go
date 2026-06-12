// Сервис котировок (#6): читает тики из символьных устройств драйвера
// stock_gen (или встроенного GBM-симулятора), хранит снапшоты в памяти,
// отдаёт REST/SSE, публикует тики в Redis Pub/Sub и батчами пишет в ClickHouse.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"
)

type Config struct {
	Addr           string
	Devices        string
	HistorySize    int
	Symbols        string
	RedisAddr      string
	ClickhouseAddr string
	Simulate       bool
	SimInterval    time.Duration
}

func loadConfig() Config {
	cfg := Config{}
	flag.StringVar(&cfg.Addr, "addr", envOr("QUOTES_ADDR", ":8081"), "HTTP listen address")
	flag.StringVar(&cfg.Devices, "devices", os.Getenv("QUOTES_DEVICES"), "comma-separated stock device paths; defaults to discovered /dev/stock*")
	flag.IntVar(&cfg.HistorySize, "history", defaultHistory, "ticks kept in memory per device")
	flag.StringVar(&cfg.Symbols, "symbols", envOr("QUOTES_SYMBOLS", defaultSymbolSpec), "device-to-symbol mapping, e.g. 0:SBER,1:GAZP")
	flag.StringVar(&cfg.RedisAddr, "redis", os.Getenv("QUOTES_REDIS_ADDR"), "Redis address host:port; empty disables publishing")
	flag.StringVar(&cfg.ClickhouseAddr, "clickhouse", os.Getenv("QUOTES_CLICKHOUSE_ADDR"), "ClickHouse HTTP URL http://user:pass@host:8123/db; empty disables writing")
	flag.BoolVar(&cfg.Simulate, "simulate", os.Getenv("QUOTES_SIMULATE") == "1", "generate ticks with built-in GBM simulator instead of reading devices")
	flag.DurationVar(&cfg.SimInterval, "sim-interval", 200*time.Millisecond, "simulator tick interval per device")
	healthcheck := flag.Bool("healthcheck", false, "probe local /health and exit (for docker HEALTHCHECK)")
	flag.Parse()

	if *healthcheck {
		os.Exit(runHealthcheck(cfg.Addr))
	}
	return cfg
}

func envOr(key, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(key)); value != "" {
		return value
	}
	return fallback
}

// runHealthcheck опрашивает собственный /health; используется docker-healthcheck'ом.
func runHealthcheck(addr string) int {
	if strings.HasPrefix(addr, ":") {
		addr = "localhost" + addr
	}
	client := http.Client{Timeout: 2 * time.Second}
	response, err := client.Get("http://" + addr + "/health")
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		fmt.Fprintf(os.Stderr, "health returned %d\n", response.StatusCode)
		return 1
	}
	return 0
}

func main() {
	cfg := loadConfig()

	symbols, err := ParseSymbolMap(cfg.Symbols)
	if err != nil {
		log.Fatal(err)
	}
	if cfg.HistorySize <= 0 {
		log.Fatal("history must be positive")
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	shutdownTracing, err := setupTracing(ctx)
	if err != nil {
		log.Fatalf("otel setup: %v", err)
	}

	metrics := &Metrics{}
	go metrics.RunRateLoop(ctx)

	var store *TickStore
	var pipeline *Pipeline

	if cfg.Simulate {
		paths := make([]string, 0, len(symbols))
		for idx := range symbols {
			paths = append(paths, fmt.Sprintf("sim://stock%d", idx))
		}
		store = NewTickStore(paths, cfg.HistorySize)
		pipeline = NewPipeline(store, symbols, metrics)
	} else {
		paths := discoverDevicePaths(cfg.Devices)
		if len(paths) == 0 {
			log.Fatal("no stock device paths configured")
		}
		store = NewTickStore(paths, cfg.HistorySize)
		pipeline = NewPipeline(store, symbols, metrics)
		for i, path := range paths {
			go readDevice(ctx, store, pipeline, i, path)
		}
	}

	if cfg.RedisAddr != "" {
		publisher := NewRedisPublisher(cfg.RedisAddr, metrics)
		pipeline.AddSink(publisher.Input())
		go publisher.Run(ctx)
		log.Printf("publishing ticks to redis %s (channel %s)", cfg.RedisAddr, ticksChannel)
	}

	if cfg.ClickhouseAddr != "" {
		writer, err := NewClickhouseWriter(cfg.ClickhouseAddr, metrics)
		if err != nil {
			log.Fatal(err)
		}
		pipeline.AddSink(writer.Input())
		go writer.Run(ctx)
		log.Printf("writing ticks to clickhouse")
	}

	if cfg.Simulate {
		NewSimulator(pipeline, symbols, cfg.SimInterval).Run(ctx)
		log.Printf("simulator enabled: %d instruments, interval %s", len(symbols), cfg.SimInterval)
	}

	server := &http.Server{
		Addr:              cfg.Addr,
		Handler:           (&Server{store: store, metrics: metrics}).routes(),
		ReadHeaderTimeout: 5 * time.Second,
	}

	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = server.Shutdown(shutdownCtx)
		_ = shutdownTracing(shutdownCtx)
	}()

	log.Printf("stock quote service listening on %s, symbols=%s", cfg.Addr, cfg.Symbols)
	if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatal(err)
	}
}
