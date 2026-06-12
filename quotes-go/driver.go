package main

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// readDevice блокирующе читает /dev/stockN и отдаёт тики в pipeline,
// переподключаясь с экспоненциальным backoff при ошибках.
func readDevice(ctx context.Context, store *TickStore, pipeline *Pipeline, index int, path string) {
	backoff := 500 * time.Millisecond
	buf := make([]byte, tickRecordSize*16)

	for {
		if ctx.Err() != nil {
			return
		}

		file, err := os.Open(path)
		if err != nil {
			store.SetDeviceStatus(index, false, err)
			sleepContext(ctx, backoff)
			backoff = min(backoff*2, 10*time.Second)
			continue
		}

		backoff = 500 * time.Millisecond
		store.SetDeviceStatus(index, true, nil)

		for {
			n, readErr := file.Read(buf)
			if n > 0 {
				parseBatch(pipeline, path, buf[:n])
			}
			if readErr != nil {
				_ = file.Close()
				if !errors.Is(readErr, io.EOF) {
					store.SetDeviceStatus(index, false, readErr)
				}
				break
			}
			if ctx.Err() != nil {
				_ = file.Close()
				return
			}
		}
	}
}

func parseBatch(pipeline *Pipeline, path string, data []byte) {
	completeBytes := len(data) - len(data)%tickRecordSize
	for offset := 0; offset < completeBytes; offset += tickRecordSize {
		tick, err := parseTick(data[offset : offset+tickRecordSize])
		if err != nil {
			continue
		}
		tick.DevicePath = path
		tick.ReceivedAtUTC = time.Now().UTC()
		pipeline.Process(tick)
	}
}

func parseTick(data []byte) (StockTick, error) {
	if len(data) < tickRecordSize {
		return StockTick{}, fmt.Errorf("stock tick record must be %d bytes, got %d", tickRecordSize, len(data))
	}

	priceUDollar := int64(binary.LittleEndian.Uint64(data[16:24]))
	return StockTick{
		Seq:          binary.LittleEndian.Uint64(data[0:8]),
		TimestampNS:  int64(binary.LittleEndian.Uint64(data[8:16])),
		PriceUDollar: priceUDollar,
		Price:        float64(priceUDollar) / 1_000_000,
		Volume:       binary.LittleEndian.Uint32(data[24:28]),
		DeviceIndex:  binary.LittleEndian.Uint32(data[28:32]),
	}, nil
}

func discoverDevicePaths(raw string) []string {
	if strings.TrimSpace(raw) != "" {
		parts := strings.Split(raw, ",")
		paths := make([]string, 0, len(parts))
		for _, part := range parts {
			path := strings.TrimSpace(part)
			if path != "" {
				paths = append(paths, path)
			}
		}
		return paths
	}

	matches, err := filepath.Glob("/dev/stock[0-9]*")
	if err == nil && len(matches) > 0 {
		sort.Strings(matches)
		return matches
	}

	return []string{"/dev/stock0", "/dev/stock1", "/dev/stock2", "/dev/stock3"}
}

func sleepContext(ctx context.Context, duration time.Duration) {
	timer := time.NewTimer(duration)
	defer timer.Stop()
	select {
	case <-ctx.Done():
	case <-timer.C:
	}
}
