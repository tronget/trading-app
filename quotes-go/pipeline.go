package main

// Pipeline — единая точка входа тиков: обогащает символом, кладёт в память
// и раздаёт по неблокирующим каналам в Redis/ClickHouse-писатели.
type Pipeline struct {
	store   *TickStore
	symbols SymbolMap
	metrics *Metrics
	sinks   []chan<- StockTick
}

func NewPipeline(store *TickStore, symbols SymbolMap, metrics *Metrics) *Pipeline {
	return &Pipeline{store: store, symbols: symbols, metrics: metrics}
}

func (p *Pipeline) AddSink(sink chan<- StockTick) {
	p.sinks = append(p.sinks, sink)
}

func (p *Pipeline) Process(tick StockTick) {
	tick.Symbol = p.symbols.Symbol(int(tick.DeviceIndex))
	p.store.AddTick(tick)
	p.metrics.TicksTotal.Add(1)
	for _, sink := range p.sinks {
		select {
		case sink <- tick:
		default:
			p.metrics.TicksDropped.Add(1)
		}
	}
}
