package main

import (
	"context"
	"strings"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"
)

// WSStats — активные соединения и принятые тики.
type WSStats struct {
	Active        atomic.Int64
	TicksReceived atomic.Uint64
	Failures      atomic.Uint64
}

// runWebSocket держит одно WS-соединение с подпиской на все символы
// и читает тики до отмены контекста, переподключаясь при ошибках.
func runWebSocket(ctx context.Context, gatewayURL, token string, stats *WSStats) {
	wsURL := strings.Replace(gatewayURL, "http", "ws", 1) + "/ws?token=" + token

	for ctx.Err() == nil {
		conn, _, err := websocket.Dial(ctx, wsURL, nil)
		if err != nil {
			stats.Failures.Add(1)
			if !sleepCtx(ctx, 2*time.Second) {
				return
			}
			continue
		}
		conn.SetReadLimit(1 << 20)

		subscribe := `{"action":"subscribe","symbols":["SBER","GAZP","AAPL","BTC"]}`
		if err := conn.Write(ctx, websocket.MessageText, []byte(subscribe)); err != nil {
			_ = conn.CloseNow()
			stats.Failures.Add(1)
			continue
		}

		stats.Active.Add(1)
		for ctx.Err() == nil {
			if _, _, err := conn.Read(ctx); err != nil {
				break
			}
			stats.TicksReceived.Add(1)
		}
		stats.Active.Add(-1)
		_ = conn.CloseNow()
	}
}
