package main

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
)

const (
	chFlushInterval = time.Second
	chFlushSize     = 5000
)

// ClickhouseWriter батчами пишет тики в таблицу ticks через HTTP-интерфейс
// (INSERT ... FORMAT JSONEachRow) — без сторонних драйверов.
type ClickhouseWriter struct {
	endpoint string // http://host:8123/?database=...&query=INSERT...
	user     string
	password string
	client   *http.Client
	input    chan StockTick
	metrics  *Metrics
}

// NewClickhouseWriter принимает адрес вида http://user:pass@host:8123/db.
func NewClickhouseWriter(addr string, metrics *Metrics) (*ClickhouseWriter, error) {
	parsed, err := url.Parse(addr)
	if err != nil {
		return nil, fmt.Errorf("clickhouse address: %w", err)
	}
	if parsed.Scheme != "http" && parsed.Scheme != "https" {
		return nil, fmt.Errorf("clickhouse address must be http(s) URL, got %q", addr)
	}
	database := strings.Trim(parsed.Path, "/")
	if database == "" {
		database = "default"
	}

	user := parsed.User.Username()
	password, _ := parsed.User.Password()

	query := url.Values{}
	query.Set("database", database)
	query.Set("query", "INSERT INTO ticks (symbol, ts, price, volume, seq) FORMAT JSONEachRow")
	endpoint := fmt.Sprintf("%s://%s/?%s", parsed.Scheme, parsed.Host, query.Encode())

	return &ClickhouseWriter{
		endpoint: endpoint,
		user:     user,
		password: password,
		client:   &http.Client{Timeout: 10 * time.Second},
		input:    make(chan StockTick, 16384),
		metrics:  metrics,
	}, nil
}

func (w *ClickhouseWriter) Input() chan<- StockTick { return w.input }

func (w *ClickhouseWriter) Run(ctx context.Context) {
	ticker := time.NewTicker(chFlushInterval)
	defer ticker.Stop()

	batch := make([]StockTick, 0, chFlushSize)
	for {
		select {
		case <-ctx.Done():
			w.flush(context.Background(), batch)
			return
		case tick := <-w.input:
			batch = append(batch, tick)
			if len(batch) >= chFlushSize {
				w.flush(ctx, batch)
				batch = batch[:0]
			}
		case <-ticker.C:
			if len(batch) > 0 {
				w.flush(ctx, batch)
				batch = batch[:0]
			}
		}
	}
}

func (w *ClickhouseWriter) flush(ctx context.Context, batch []StockTick) {
	if len(batch) == 0 {
		return
	}

	tracer := otel.Tracer("quotes")
	flushCtx, span := tracer.Start(ctx, "clickhouse.flush_ticks")
	span.SetAttributes(attribute.Int("rows", len(batch)))
	defer span.End()

	if err := w.insert(flushCtx, encodeTickRows(batch)); err != nil {
		w.metrics.ClickhouseFails.Add(1)
		span.RecordError(err)
		span.SetStatus(codes.Error, "flush failed")
		logThrottled("clickhouse flush (%d rows): %v", len(batch), err)
		return
	}
	w.metrics.ClickhouseRows.Add(uint64(len(batch)))
}

func (w *ClickhouseWriter) insert(ctx context.Context, body []byte) error {
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, w.endpoint, bytes.NewReader(body))
	if err != nil {
		return err
	}
	if w.user != "" {
		request.Header.Set("X-ClickHouse-User", w.user)
		request.Header.Set("X-ClickHouse-Key", w.password)
	}

	response, err := w.client.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusOK {
		message, _ := io.ReadAll(io.LimitReader(response.Body, 512))
		return fmt.Errorf("clickhouse returned %d: %s", response.StatusCode, strings.TrimSpace(string(message)))
	}
	return nil
}

// encodeTickRows сериализует батч в JSONEachRow: по строке-объекту на тик.
func encodeTickRows(batch []StockTick) []byte {
	var buf bytes.Buffer
	for _, tick := range batch {
		fmt.Fprintf(&buf, `{"symbol":%q,"ts":%q,"price":%.6f,"volume":%d,"seq":%d}`+"\n",
			tick.Symbol,
			tick.ReceivedAtUTC.UTC().Format("2006-01-02 15:04:05.000"),
			tick.Price,
			tick.Volume,
			tick.Seq,
		)
	}
	return buf.Bytes()
}
