# Quotes Go Service (#6)

HTTP microservice that reads synthetic stock quotes from the Linux character
driver in `stock_gen_driver` (or generates them with a built-in GBM simulator),
exposes them as JSON/SSE, publishes every tick to **Redis Pub/Sub**, and batches
ticks into **ClickHouse** for history/candles.

The driver emits 32-byte little-endian records from `/dev/stockN`; each device
index is mapped to a ticker symbol (`0:SBER,1:GAZP,2:AAPL,3:BTC` by default).

## Data flow

```
/dev/stock0..3 (or simulator)
        │
        ▼
   Pipeline ──► in-memory store ──► REST + SSE (/v1/*)
        ├─────► Redis  PUBLISH quotes:ticks + SET quote:last:<symbol>
        └─────► ClickHouse INSERT INTO ticks (batch: 1s / 5000 rows)
```

Redis message / `quote:last:<symbol>` value (consumed by Gateway #3 and Data #4):

```json
{ "symbol": "SBER", "price": 305.5, "ts": 1781297920237, "volume": 158, "seq": 25 }
```

## Run

On Linux with the kernel module:

```bash
cd ../stock_gen_driver && make && sudo insmod stock_gen.ko
go run . -addr :8081
```

Without the driver (macOS / plain container) use the simulator:

```bash
go run . -simulate -addr :8081
```

All flags have env counterparts (env is read as the flag default):

| Flag          | Env                      | Default                       |
|---------------|--------------------------|-------------------------------|
| `-addr`       | `QUOTES_ADDR`            | `:8081`                       |
| `-devices`    | `QUOTES_DEVICES`         | discovered `/dev/stock*`      |
| `-symbols`    | `QUOTES_SYMBOLS`         | `0:SBER,1:GAZP,2:AAPL,3:BTC`  |
| `-redis`      | `QUOTES_REDIS_ADDR`      | empty → publishing disabled   |
| `-clickhouse` | `QUOTES_CLICKHOUSE_ADDR` | empty → writing disabled      |
| `-simulate`   | `QUOTES_SIMULATE=1`      | off                           |
| `-healthcheck`| —                        | probe `/health` and exit      |

ClickHouse address format: `http://user:pass@host:8123/database` (HTTP
interface, `INSERT ... FORMAT JSONEachRow`).

Tracing is enabled when `OTEL_EXPORTER_OTLP_ENDPOINT` is set (OTLP/HTTP,
service name `quotes`): spans for Redis publishes and ClickHouse flushes.

## API

```http
GET /health                              # 200 ok / 503 degraded
GET /metrics                             # Prometheus text format
GET /v1/devices
GET /v1/ticks/latest[?device=0]
GET /v1/ticks/history?device=0&limit=100
GET /v1/stream[?device=0]                # Server-Sent Events, event: tick
```

Tick JSON (REST/SSE):

```json
{
  "seq": 25, "timestampNs": 1781297920237175070,
  "priceUDollar": 100043141, "price": 100.043141,
  "volume": 158, "deviceIndex": 0, "symbol": "SBER",
  "devicePath": "sim://stock0", "receivedAtUtc": "2026-06-12T20:58:40Z"
}
```

## Verify the pipeline

```bash
docker compose -f ../deploy/docker-compose.yml up -d redis clickhouse quotes
docker exec trading-redis redis-cli SUBSCRIBE quotes:ticks   # live ticks
docker exec trading-clickhouse clickhouse-client -u trading --password trading \
  -q 'SELECT count() FROM trading.ticks'                     # growing count
```

## Test

```bash
go test ./...
```
