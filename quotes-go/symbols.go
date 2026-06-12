package main

import (
	"fmt"
	"strconv"
	"strings"
)

// SymbolMap — маппинг индекса устройства драйвера на биржевой тикер.
type SymbolMap map[int]string

const defaultSymbolSpec = "0:SBER,1:GAZP,2:AAPL,3:BTC"

// ParseSymbolMap разбирает строку вида "0:SBER,1:GAZP,2:AAPL,3:BTC".
func ParseSymbolMap(raw string) (SymbolMap, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		raw = defaultSymbolSpec
	}

	result := make(SymbolMap)
	for _, part := range strings.Split(raw, ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		idxStr, symbol, found := strings.Cut(part, ":")
		if !found {
			return nil, fmt.Errorf("symbol mapping %q must look like <index>:<symbol>", part)
		}
		idx, err := strconv.Atoi(strings.TrimSpace(idxStr))
		if err != nil || idx < 0 {
			return nil, fmt.Errorf("symbol mapping %q has invalid device index", part)
		}
		symbol = strings.ToUpper(strings.TrimSpace(symbol))
		if symbol == "" {
			return nil, fmt.Errorf("symbol mapping %q has empty symbol", part)
		}
		if _, exists := result[idx]; exists {
			return nil, fmt.Errorf("duplicate device index %d in symbol mapping", idx)
		}
		result[idx] = symbol
	}
	if len(result) == 0 {
		return nil, fmt.Errorf("symbol mapping is empty")
	}
	return result, nil
}

// Symbol возвращает тикер для устройства; для неизвестных индексов — DEV<idx>.
func (m SymbolMap) Symbol(index int) string {
	if symbol, ok := m[index]; ok {
		return symbol
	}
	return fmt.Sprintf("DEV%d", index)
}
