package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"math/rand/v2"
	"net/http"
	"time"
)

// Action — тип запроса виртуального клиента; вероятности задаются в Scenario.
type Action string

const (
	ActionQuotes    Action = "quotes"
	ActionPortfolio Action = "portfolio"
	ActionOrder     Action = "order"
	ActionTrades    Action = "trades"
	ActionHistory   Action = "history"
)

// Scenario — веса действий (не обязаны суммироваться к 1 — нормализуются).
type Scenario []WeightedAction

type WeightedAction struct {
	Action Action
	Weight float64
}

var defaultScenario = Scenario{
	{ActionQuotes, 0.45},
	{ActionPortfolio, 0.20},
	{ActionOrder, 0.15},
	{ActionTrades, 0.10},
	{ActionHistory, 0.10},
}

// Pick выбирает действие пропорционально весам; r — равномерное [0,1).
func (s Scenario) Pick(r float64) Action {
	total := 0.0
	for _, wa := range s {
		total += wa.Weight
	}
	target := r * total
	acc := 0.0
	for _, wa := range s {
		acc += wa.Weight
		if target < acc {
			return wa.Action
		}
	}
	return s[len(s)-1].Action
}

var symbols = []string{"SBER", "GAZP", "AAPL", "BTC"}

// Client — один виртуальный пользователь: логин и цикл случайных запросов.
type Client struct {
	id       int
	login    string
	password string
	token    string
	base     string
	http     *http.Client
	metrics  *Metrics
	scenario Scenario
	rng      *rand.Rand
}

func (c *Client) register(ctx context.Context) error {
	body := fmt.Sprintf(`{"login":%q,"password":%q}`, c.login, c.password)
	started := time.Now()
	status, response, err := c.post(ctx, "/auth/register", body)
	if err != nil {
		c.metrics.Record("register", 0, true)
		return err
	}
	if status == http.StatusConflict {
		// пользователь остался с прошлого запуска — логинимся
		status, response, err = c.post(ctx, "/auth/login", body)
		if err != nil || status != http.StatusOK {
			c.metrics.Record("register", 0, true)
			return fmt.Errorf("login failed: status %d err %v", status, err)
		}
	} else if status != http.StatusCreated {
		c.metrics.Record("register", 0, true)
		return fmt.Errorf("register failed: status %d", status)
	}
	c.metrics.Record("register", time.Since(started), false)

	var parsed struct {
		AccessToken string `json:"accessToken"`
	}
	if err := json.Unmarshal(response, &parsed); err != nil || parsed.AccessToken == "" {
		return fmt.Errorf("no access token in response")
	}
	c.token = parsed.AccessToken
	return nil
}

// Run крутит цикл случайных действий до отмены контекста.
func (c *Client) Run(ctx context.Context, interval time.Duration) {
	if err := c.register(ctx); err != nil {
		return
	}
	// рассинхронизация клиентов, чтобы не било залпами
	jitter := time.Duration(c.rng.Int64N(int64(interval)))
	if !sleepCtx(ctx, jitter) {
		return
	}

	for ctx.Err() == nil {
		c.step(ctx)
		if !sleepCtx(ctx, interval) {
			return
		}
	}
}

func (c *Client) step(ctx context.Context) {
	action := c.scenario.Pick(c.rng.Float64())
	started := time.Now()
	var status int
	var err error

	switch action {
	case ActionQuotes:
		status, _, err = c.get(ctx, "/quotes")
	case ActionPortfolio:
		status, _, err = c.get(ctx, "/portfolio")
	case ActionTrades:
		status, _, err = c.get(ctx, "/trades?limit=20")
	case ActionHistory:
		symbol := symbols[c.rng.IntN(len(symbols))]
		status, _, err = c.get(ctx, "/quotes/"+symbol+"/history?interval=1m&limit=60")
	case ActionOrder:
		symbol := symbols[c.rng.IntN(len(symbols))]
		side := "BUY"
		if c.rng.IntN(2) == 0 {
			side = "SELL"
		}
		qty := c.rng.IntN(5) + 1
		body := fmt.Sprintf(`{"symbol":%q,"side":%q,"type":"MARKET","qty":"%d"}`, symbol, side, qty)
		status, _, err = c.post(ctx, "/orders", body)
	}

	// 429 (rate limit) и 2xx — штатные ответы под нагрузкой; ошибкой считаем
	// сетевые сбои и 5xx.
	failed := err != nil || status >= 500
	c.metrics.Record(string(action), time.Since(started), failed)
}

func (c *Client) get(ctx context.Context, path string) (int, []byte, error) {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, c.base+path, nil)
	if err != nil {
		return 0, nil, err
	}
	return c.do(request)
}

func (c *Client) post(ctx context.Context, path, body string) (int, []byte, error) {
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, c.base+path, bytes.NewBufferString(body))
	if err != nil {
		return 0, nil, err
	}
	request.Header.Set("Content-Type", "application/json")
	return c.do(request)
}

func (c *Client) do(request *http.Request) (int, []byte, error) {
	if c.token != "" {
		request.Header.Set("Authorization", "Bearer "+c.token)
	}
	response, err := c.http.Do(request)
	if err != nil {
		return 0, nil, err
	}
	defer response.Body.Close()
	body, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	return response.StatusCode, body, err
}

func sleepCtx(ctx context.Context, d time.Duration) bool {
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-timer.C:
		return true
	}
}
