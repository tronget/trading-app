package main

import (
	"context"
	"encoding/json"
	"log"
	"sync/atomic"
	"time"

	"github.com/redis/go-redis/v9"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
)

const (
	ticksChannel    = "quotes:ticks"
	lastPriceKeyFmt = "quote:last:"
)

// TickMessage — контракт сообщения в Redis (канал quotes:ticks и ключи quote:last:<symbol>).
// На него подписаны Gateway (#3) и Data-сервис (#4).
type TickMessage struct {
	Symbol string  `json:"symbol"`
	Price  float64 `json:"price"`
	TS     int64   `json:"ts"` // unix millis
	Volume uint32  `json:"volume"`
	Seq    uint64  `json:"seq"`
}

func NewTickMessage(tick StockTick) TickMessage {
	return TickMessage{
		Symbol: tick.Symbol,
		Price:  tick.Price,
		TS:     tick.ReceivedAtUTC.UnixMilli(),
		Volume: tick.Volume,
		Seq:    tick.Seq,
	}
}

// RedisPublisher публикует тики в Pub/Sub и поддерживает кэш последней цены.
type RedisPublisher struct {
	client  *redis.Client
	input   chan StockTick
	metrics *Metrics
}

func NewRedisPublisher(addr string, metrics *Metrics) *RedisPublisher {
	return &RedisPublisher{
		client:  redis.NewClient(&redis.Options{Addr: addr}),
		input:   make(chan StockTick, 4096),
		metrics: metrics,
	}
}

func (p *RedisPublisher) Input() chan<- StockTick { return p.input }

func (p *RedisPublisher) Run(ctx context.Context) {
	tracer := otel.Tracer("quotes")
	for {
		select {
		case <-ctx.Done():
			_ = p.client.Close()
			return
		case tick := <-p.input:
			payload, err := json.Marshal(NewTickMessage(tick))
			if err != nil {
				p.metrics.RedisErrors.Add(1)
				continue
			}

			publishCtx, span := tracer.Start(ctx, "redis.publish_tick")
			span.SetAttributes(
				attribute.String("symbol", tick.Symbol),
				attribute.Float64("price", tick.Price),
			)

			pipe := p.client.Pipeline()
			pipe.Publish(publishCtx, ticksChannel, payload)
			pipe.Set(publishCtx, lastPriceKeyFmt+tick.Symbol, payload, 0)
			if _, err := pipe.Exec(publishCtx); err != nil {
				p.metrics.RedisErrors.Add(1)
				span.RecordError(err)
				span.SetStatus(codes.Error, "publish failed")
				logThrottled("redis publish: %v", err)
			} else {
				p.metrics.RedisPublished.Add(1)
			}
			span.End()
		}
	}
}

var lastErrorLogNS atomic.Int64

// logThrottled пишет ошибку не чаще раза в 5 секунд, чтобы не заливать лог
// при недоступном Redis/ClickHouse.
func logThrottled(format string, args ...any) {
	now := time.Now().UnixNano()
	last := lastErrorLogNS.Load()
	if now-last < int64(5*time.Second) || !lastErrorLogNS.CompareAndSwap(last, now) {
		return
	}
	log.Printf(format, args...)
}
