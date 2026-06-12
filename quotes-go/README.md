# Stock Quote Go Service

HTTP microservice that reads synthetic stock quotes from the Linux character
driver in `stock_gen_driver` and exposes them as JSON and Server-Sent Events.

The driver emits 32-byte little-endian records from `/dev/stockN`; this service
parses those records, keeps the last tick plus a bounded in-memory history, and
is ready to be consumed by future Kotlin/Ktor, Android, React Native, and load
testing services.

## Run

Build and load the driver on Linux first:

```bash
cd stock_gen_driver
make
sudo insmod stock_gen.ko
```

Start the Go service:

```bash
go run . -addr :8080
```

Useful flags:

```bash
go run . -addr :8080 -devices /dev/stock0,/dev/stock1 -history 1000
```

If `/dev/stock*` is not available, the service still starts and reports
`degraded` health. This makes local development possible without the kernel
module.

## API

```http
GET /health
GET /v1/devices
GET /v1/ticks/latest
GET /v1/ticks/latest?device=0
GET /v1/ticks/history?device=0&limit=100
GET /v1/stream
GET /v1/stream?device=0
```

`/v1/stream` uses Server-Sent Events. Each event has type `tick` and JSON data:

```json
{
  "seq": 1,
  "timestampNs": 123456789,
  "priceUDollar": 10000000,
  "price": 10,
  "volume": 1500,
  "deviceIndex": 0,
  "devicePath": "/dev/stock0",
  "receivedAtUtc": "2026-06-11T12:00:00Z"
}
```

## Test

```bash
go test ./...
```
