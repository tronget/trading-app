package main

import (
	"encoding/binary"
	"testing"
)

func TestParseTick(t *testing.T) {
	buf := make([]byte, tickRecordSize)
	binary.LittleEndian.PutUint64(buf[0:8], 42)
	binary.LittleEndian.PutUint64(buf[8:16], uint64(123456789))
	binary.LittleEndian.PutUint64(buf[16:24], uint64(10123456))
	binary.LittleEndian.PutUint32(buf[24:28], 1500)
	binary.LittleEndian.PutUint32(buf[28:32], 2)

	tick, err := parseTick(buf)
	if err != nil {
		t.Fatalf("parseTick returned error: %v", err)
	}

	if tick.Seq != 42 {
		t.Fatalf("unexpected seq: %d", tick.Seq)
	}
	if tick.TimestampNS != 123456789 {
		t.Fatalf("unexpected timestamp: %d", tick.TimestampNS)
	}
	if tick.PriceUDollar != 10123456 {
		t.Fatalf("unexpected price in microdollars: %d", tick.PriceUDollar)
	}
	if tick.Price != 10.123456 {
		t.Fatalf("unexpected price: %f", tick.Price)
	}
	if tick.Volume != 1500 {
		t.Fatalf("unexpected volume: %d", tick.Volume)
	}
	if tick.DeviceIndex != 2 {
		t.Fatalf("unexpected device index: %d", tick.DeviceIndex)
	}
}

func TestParseTickRejectsShortRecord(t *testing.T) {
	_, err := parseTick(make([]byte, tickRecordSize-1))
	if err == nil {
		t.Fatal("expected error for short record")
	}
}

func TestHistoryLimit(t *testing.T) {
	store := NewTickStore([]string{"/dev/stock0"}, 2)
	store.AddTick(StockTick{Seq: 1, DeviceIndex: 0})
	store.AddTick(StockTick{Seq: 2, DeviceIndex: 0})
	store.AddTick(StockTick{Seq: 3, DeviceIndex: 0})

	history, ok := store.History(0, 10)
	if !ok {
		t.Fatal("expected configured device history")
	}
	if len(history) != 2 {
		t.Fatalf("unexpected history length: %d", len(history))
	}
	if history[0].Seq != 2 || history[1].Seq != 3 {
		t.Fatalf("unexpected history: %+v", history)
	}
}
