package main

import (
	"context"
	"fmt"
	"math"
	"math/rand/v2"
	"sort"
	"time"
)

// Simulator генерирует тики геометрическим броуновским движением — та же модель,
// что и в драйвере stock_gen.c. Используется, когда /dev/stock* недоступны
// (разработка на macOS, контейнер без модуля ядра).
type Simulator struct {
	pipeline *Pipeline
	indices  []int
	interval time.Duration
}

func NewSimulator(pipeline *Pipeline, symbols SymbolMap, interval time.Duration) *Simulator {
	indices := make([]int, 0, len(symbols))
	for idx := range symbols {
		indices = append(indices, idx)
	}
	sort.Ints(indices)
	return &Simulator{pipeline: pipeline, indices: indices, interval: interval}
}

func (s *Simulator) Run(ctx context.Context) {
	for _, idx := range s.indices {
		go s.runDevice(ctx, idx)
	}
}

func (s *Simulator) runDevice(ctx context.Context, index int) {
	// Базовые цены различаются по устройствам, как в драйвере.
	price := 100.0 * float64(index+1)
	const (
		volatility = 0.3                // годовая волатильность
		drift      = 0.05               // годовой дрифт
		yearTicks  = 252 * 8 * 3600 * 5 // условное число тиков в году при 5 тиках/сек
	)
	dt := 1.0 / float64(yearTicks)

	seq := uint64(0)
	ticker := time.NewTicker(s.interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			z := rand.NormFloat64()
			price *= math.Exp((drift-0.5*volatility*volatility)*dt + volatility*math.Sqrt(dt)*z)
			seq++

			now := time.Now().UTC()
			s.pipeline.Process(StockTick{
				Seq:           seq,
				TimestampNS:   now.UnixNano(),
				PriceUDollar:  int64(price * 1_000_000),
				Price:         math.Round(price*1_000_000) / 1_000_000,
				Volume:        uint32(rand.IntN(1000) + 1),
				DeviceIndex:   uint32(index),
				DevicePath:    fmt.Sprintf("sim://stock%d", index),
				ReceivedAtUTC: now,
			})
		}
	}
}
