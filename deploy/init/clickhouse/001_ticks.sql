-- История котировок: сырые тики + материализованные свечи 1m.

CREATE TABLE IF NOT EXISTS trading.ticks
(
    symbol LowCardinality(String),
    ts     DateTime64(3),
    price  Decimal(18, 6),
    volume UInt32,
    seq    UInt64
)
ENGINE = MergeTree
ORDER BY (symbol, ts);

CREATE TABLE IF NOT EXISTS trading.candles_1m
(
    symbol LowCardinality(String),
    t      DateTime,
    open   AggregateFunction(argMin, Decimal(18, 6), DateTime64(3)),
    high   SimpleAggregateFunction(max, Decimal(18, 6)),
    low    SimpleAggregateFunction(min, Decimal(18, 6)),
    close  AggregateFunction(argMax, Decimal(18, 6), DateTime64(3)),
    volume SimpleAggregateFunction(sum, UInt64)
)
ENGINE = AggregatingMergeTree
ORDER BY (symbol, t);

CREATE MATERIALIZED VIEW IF NOT EXISTS trading.candles_1m_mv TO trading.candles_1m AS
SELECT
    symbol,
    toStartOfMinute(ts)        AS t,
    argMinState(price, ts)     AS open,
    max(price)                 AS high,
    min(price)                 AS low,
    argMaxState(price, ts)     AS close,
    sum(toUInt64(volume))      AS volume
FROM trading.ticks
GROUP BY symbol, t;
