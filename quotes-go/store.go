package main

import (
	"sort"
	"sync"
	"time"
)

const (
	tickRecordSize = 32
	defaultHistory = 512
)

// StockTick — одна котировка от драйвера (или симулятора), обогащённая символом.
type StockTick struct {
	Seq           uint64    `json:"seq"`
	TimestampNS   int64     `json:"timestampNs"`
	PriceUDollar  int64     `json:"priceUDollar"`
	Price         float64   `json:"price"`
	Volume        uint32    `json:"volume"`
	DeviceIndex   uint32    `json:"deviceIndex"`
	Symbol        string    `json:"symbol"`
	DevicePath    string    `json:"devicePath"`
	ReceivedAtUTC time.Time `json:"receivedAtUtc"`
}

type DeviceSnapshot struct {
	Index         int        `json:"index"`
	Path          string     `json:"path"`
	Symbol        string     `json:"symbol"`
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
	Symbol        string
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
	device.Symbol = tick.Symbol
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
		Symbol:        device.Symbol,
		Connected:     device.Connected,
		LastError:     device.LastError,
		TicksReceived: device.TicksReceived,
		LastTick:      device.LastTick,
	}
}
