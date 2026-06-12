package main

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

const (
	tickRecordSize = 32
	defaultHistory = 512
)

type StockTick struct {
	Seq           uint64    `json:"seq"`
	TimestampNS   int64     `json:"timestampNs"`
	PriceUDollar  int64     `json:"priceUDollar"`
	Price         float64   `json:"price"`
	Volume        uint32    `json:"volume"`
	DeviceIndex   uint32    `json:"deviceIndex"`
	DevicePath    string    `json:"devicePath"`
	ReceivedAtUTC time.Time `json:"receivedAtUtc"`
}

type DeviceSnapshot struct {
	Index         int        `json:"index"`
	Path          string     `json:"path"`
	Connected     bool       `json:"connected"`
	LastError     string     `json:"lastError,omitempty"`
	TicksReceived uint64     `json:"ticksReceived"`
	LastTick      *StockTick `json:"lastTick,omitempty"`
}

type TickStore struct {
	mu          sync.RWMutex
	devices     map[int]*DeviceState
	subscribers map[chan StockTick]struct{}
	historySize int
}

type DeviceState struct {
	Index         int
	Path          string
	Connected     bool
	LastError     string
	TicksReceived uint64
	LastTick      *StockTick
	History       []StockTick
}

func NewTickStore(paths []string, historySize int) *TickStore {
	devices := make(map[int]*DeviceState, len(paths))
	for i, path := range paths {
		devices[i] = &DeviceState{Index: i, Path: path}
	}
	return &TickStore{
		devices:     devices,
		subscribers: make(map[chan StockTick]struct{}),
		historySize: historySize,
	}
}

func (s *TickStore) SetDeviceStatus(index int, connected bool, err error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	device := s.devices[index]
	if device == nil {
		return
	}
	device.Connected = connected
	if err != nil {
		device.LastError = err.Error()
	} else {
		device.LastError = ""
	}
}

func (s *TickStore) AddTick(tick StockTick) {
	s.mu.Lock()
	device := s.devices[int(tick.DeviceIndex)]
	if device == nil {
		device = &DeviceState{
			Index: int(tick.DeviceIndex),
			Path:  tick.DevicePath,
		}
		s.devices[device.Index] = device
	}
	device.Connected = true
	device.LastError = ""
	device.TicksReceived++
	device.LastTick = copyTick(tick)
	device.History = append(device.History, tick)
	if len(device.History) > s.historySize {
		copy(device.History, device.History[len(device.History)-s.historySize:])
		device.History = device.History[:s.historySize]
	}

	subscribers := make([]chan StockTick, 0, len(s.subscribers))
	for ch := range s.subscribers {
		subscribers = append(subscribers, ch)
	}
	s.mu.Unlock()

	for _, ch := range subscribers {
		select {
		case ch <- tick:
		default:
		}
	}
}

func (s *TickStore) Subscribe(buffer int) chan StockTick {
	ch := make(chan StockTick, buffer)
	s.mu.Lock()
	s.subscribers[ch] = struct{}{}
	s.mu.Unlock()
	return ch
}

func (s *TickStore) Unsubscribe(ch chan StockTick) {
	s.mu.Lock()
	delete(s.subscribers, ch)
	close(ch)
	s.mu.Unlock()
}

func (s *TickStore) Snapshots() []DeviceSnapshot {
	s.mu.RLock()
	defer s.mu.RUnlock()

	indexes := make([]int, 0, len(s.devices))
	for idx := range s.devices {
		indexes = append(indexes, idx)
	}
	sort.Ints(indexes)

	result := make([]DeviceSnapshot, 0, len(indexes))
	for _, idx := range indexes {
		device := s.devices[idx]
		result = append(result, snapshotDevice(device))
	}
	return result
}

func (s *TickStore) Latest(index *int) ([]StockTick, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	if index != nil {
		device := s.devices[*index]
		if device == nil || device.LastTick == nil {
			return nil, false
		}
		return []StockTick{*device.LastTick}, true
	}

	indexes := make([]int, 0, len(s.devices))
	for idx := range s.devices {
		indexes = append(indexes, idx)
	}
	sort.Ints(indexes)

	ticks := make([]StockTick, 0, len(indexes))
	for _, idx := range indexes {
		if tick := s.devices[idx].LastTick; tick != nil {
			ticks = append(ticks, *tick)
		}
	}
	return ticks, len(ticks) > 0
}

func (s *TickStore) History(index int, limit int) ([]StockTick, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	device := s.devices[index]
	if device == nil {
		return nil, false
	}
	if limit <= 0 || limit > len(device.History) {
		limit = len(device.History)
	}
	start := len(device.History) - limit
	result := make([]StockTick, limit)
	copy(result, device.History[start:])
	return result, true
}

func (s *TickStore) Ready() bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, device := range s.devices {
		if device.Connected && device.LastTick != nil {
			return true
		}
	}
	return false
}

func copyTick(tick StockTick) *StockTick {
	copied := tick
	return &copied
}

func snapshotDevice(device *DeviceState) DeviceSnapshot {
	return DeviceSnapshot{
		Index:         device.Index,
		Path:          device.Path,
		Connected:     device.Connected,
		LastError:     device.LastError,
		TicksReceived: device.TicksReceived,
		LastTick:      device.LastTick,
	}
}

func readDevice(ctx context.Context, store *TickStore, index int, path string) {
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
			backoff = minDuration(backoff*2, 10*time.Second)
			continue
		}

		backoff = 500 * time.Millisecond
		store.SetDeviceStatus(index, true, nil)

		for {
			n, readErr := file.Read(buf)
			if n > 0 {
				parseBatch(store, path, buf[:n])
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

func parseBatch(store *TickStore, path string, data []byte) {
	completeBytes := len(data) - len(data)%tickRecordSize
	for offset := 0; offset < completeBytes; offset += tickRecordSize {
		tick, err := parseTick(data[offset : offset+tickRecordSize])
		if err != nil {
			continue
		}
		tick.DevicePath = path
		tick.ReceivedAtUTC = time.Now().UTC()
		store.AddTick(tick)
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

type Server struct {
	store *TickStore
}

func (s *Server) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", s.handleHealth)
	mux.HandleFunc("GET /v1/devices", s.handleDevices)
	mux.HandleFunc("GET /v1/ticks/latest", s.handleLatest)
	mux.HandleFunc("GET /v1/ticks/history", s.handleHistory)
	mux.HandleFunc("GET /v1/stream", s.handleStream)
	return logRequests(mux)
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	status := http.StatusOK
	response := map[string]any{
		"status":  "ok",
		"ready":   s.store.Ready(),
		"devices": s.store.Snapshots(),
	}
	if !s.store.Ready() {
		status = http.StatusServiceUnavailable
		response["status"] = "degraded"
	}
	writeJSON(w, status, response)
}

func (s *Server) handleDevices(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"devices": s.store.Snapshots()})
}

func (s *Server) handleLatest(w http.ResponseWriter, r *http.Request) {
	index, err := optionalDeviceIndex(r)
	if err != nil {
		writeError(w, http.StatusBadRequest, err)
		return
	}

	ticks, ok := s.store.Latest(index)
	if !ok {
		writeError(w, http.StatusNotFound, errors.New("no ticks available"))
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ticks": ticks})
}

func (s *Server) handleHistory(w http.ResponseWriter, r *http.Request) {
	index, err := requiredDeviceIndex(r)
	if err != nil {
		writeError(w, http.StatusBadRequest, err)
		return
	}
	limit := queryInt(r, "limit", 100, 1, 1000)

	ticks, ok := s.store.History(index, limit)
	if !ok {
		writeError(w, http.StatusNotFound, fmt.Errorf("device %d is not configured", index))
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ticks": ticks})
}

func (s *Server) handleStream(w http.ResponseWriter, r *http.Request) {
	flusher, ok := w.(http.Flusher)
	if !ok {
		writeError(w, http.StatusInternalServerError, errors.New("streaming is not supported"))
		return
	}

	index, err := optionalDeviceIndex(r)
	if err != nil {
		writeError(w, http.StatusBadRequest, err)
		return
	}

	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")

	ch := s.store.Subscribe(64)
	defer s.store.Unsubscribe(ch)

	_, _ = io.WriteString(w, ": connected\n\n")
	flusher.Flush()

	for {
		select {
		case <-r.Context().Done():
			return
		case tick := <-ch:
			if index != nil && int(tick.DeviceIndex) != *index {
				continue
			}
			payload, err := json.Marshal(tick)
			if err != nil {
				continue
			}
			_, _ = fmt.Fprintf(w, "event: tick\ndata: %s\n\n", payload)
			flusher.Flush()
		}
	}
}

func optionalDeviceIndex(r *http.Request) (*int, error) {
	raw := strings.TrimSpace(r.URL.Query().Get("device"))
	if raw == "" {
		return nil, nil
	}
	value, err := strconv.Atoi(raw)
	if err != nil || value < 0 {
		return nil, fmt.Errorf("device must be a non-negative integer")
	}
	return &value, nil
}

func requiredDeviceIndex(r *http.Request) (int, error) {
	index, err := optionalDeviceIndex(r)
	if err != nil {
		return 0, err
	}
	if index == nil {
		return 0, errors.New("device query parameter is required")
	}
	return *index, nil
}

func queryInt(r *http.Request, key string, fallback, minValue, maxValue int) int {
	raw := strings.TrimSpace(r.URL.Query().Get(key))
	if raw == "" {
		return fallback
	}
	value, err := strconv.Atoi(raw)
	if err != nil {
		return fallback
	}
	if value < minValue {
		return minValue
	}
	if value > maxValue {
		return maxValue
	}
	return value
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(value); err != nil {
		log.Printf("write json response: %v", err)
	}
}

func writeError(w http.ResponseWriter, status int, err error) {
	writeJSON(w, status, map[string]string{"error": err.Error()})
}

func logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		started := time.Now()
		next.ServeHTTP(w, r)
		log.Printf("%s %s %s", r.Method, r.URL.RequestURI(), time.Since(started).Round(time.Millisecond))
	})
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

func minDuration(a, b time.Duration) time.Duration {
	if a < b {
		return a
	}
	return b
}

func main() {
	addr := flag.String("addr", ":8080", "HTTP listen address")
	devices := flag.String("devices", "", "comma-separated stock device paths; defaults to /dev/stock0../dev/stock3 or discovered /dev/stock*")
	historySize := flag.Int("history", defaultHistory, "ticks kept in memory per device")
	flag.Parse()

	paths := discoverDevicePaths(*devices)
	if len(paths) == 0 {
		log.Fatal("no stock device paths configured")
	}
	if *historySize <= 0 {
		log.Fatal("history must be positive")
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	store := NewTickStore(paths, *historySize)
	for i, path := range paths {
		go readDevice(ctx, store, i, path)
	}

	server := &http.Server{
		Addr:              *addr,
		Handler:           (&Server{store: store}).routes(),
		ReadHeaderTimeout: 5 * time.Second,
	}

	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = server.Shutdown(shutdownCtx)
	}()

	log.Printf("stock quote service listening on %s, devices=%s", *addr, strings.Join(paths, ","))
	if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatal(err)
	}
}
