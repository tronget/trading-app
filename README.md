# Trading Platform — учебный проект «Разработка мобильных приложений»

Экосистема биржевого терминала: от драйвера-имитатора котировок в ядре Linux
до нативного Android-приложения. Цель — обслуживание **10 000 одновременных клиентов**.

Полный план — в [PLAN.md](PLAN.md).

## Состав

| Папка | Компонент | Стек |
|-------|-----------|------|
| `stock_gen_driver/` | #7 Драйвер-имитатор котировок (GBM) | C, Linux kernel |
| `quotes-go/` | #6 Сервис сбора котировок (REST/SSE, Redis Pub/Sub, ClickHouse) | Go |
| `data-service-ktor/` | #4 Сервис БД: счета, ордера, сделки, матчинг | Kotlin, Ktor, голый SQL |
| `gateway-ktor/` | #3 Gateway: JWT-auth, WebSocket-котировки, проксирование | Kotlin, Ktor |
| `android-app/` | #1 Нативное приложение | Kotlin, Jetpack Compose |
| `rn-app/` | #2 Кросс-платформенный клиент (опц.) | React Native, Expo |
| `load-simulator/` | #5 Нагрузочный имитатор 10 000 клиентов | Go |
| `deploy/` | Инфраструктура: docker-compose, init-SQL, OTel | Docker |
| `docs/` | Документация (Obsidian vault) | Markdown |
| `report/` | Отчёт по ГОСТ | Typst |

## Быстрый старт

```bash
cd deploy
docker compose up -d --build
```

Поднимается:

- **PostgreSQL** — `localhost:5432` (trading/trading, БД `trading`)
- **Redis** — `localhost:6379`
- **ClickHouse** — `localhost:8123` (HTTP), `localhost:9000` (native)
- **Jaeger UI** — http://localhost:16686
- **OTel Collector** — `localhost:4317` (gRPC), `localhost:4318` (HTTP)
- **quotes** (Go #6) — http://localhost:8081
- **data-service** (Ktor #4) — http://localhost:8082
- **gateway** (Ktor #3) — http://localhost:8080 ← публичный API для клиентов

Публичный контракт API — см. PLAN.md §6.

### Проверка потока котировок

```bash
docker exec trading-redis redis-cli SUBSCRIBE quotes:ticks       # поток тиков
curl 'http://localhost:8123/?user=trading&password=trading' \
  --data 'SELECT count() FROM trading.ticks'                     # история в ClickHouse
curl http://localhost:8080/quotes                                # REST через gateway
```

### Торговый сценарий через Gateway

```bash
# регистрация → токен
TOKEN=$(curl -s -X POST localhost:8080/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"login":"demo","password":"demo12345"}' | jq -r .accessToken)

# market-ордер на покупку
curl -s -X POST localhost:8080/orders \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"symbol":"AAPL","side":"BUY","type":"MARKET","qty":"10","clientOrderId":"demo-1"}'

# портфель
curl -s localhost:8080/portfolio -H "Authorization: Bearer $TOKEN"
```

## Драйвер (только Linux)

```bash
cd stock_gen_driver && make && sudo insmod stock_gen.ko
```

На macOS quotes-сервис работает без устройства: режим `degraded` либо встроенный
симулятор (`QUOTES_SIMULATE=1`), который генерирует те же GBM-тики в userspace.

## Нагрузочный тест

```bash
ulimit -n 65535
cd load-simulator
go run . -clients 10000 -ws-clients 1000 -ramp-up 60s -duration 90s -interval 5s
```

Зафиксированный результат (см. `report/load/load-test-10k.json`): 255 193
запроса за 90 с, 0.004 % ошибок, p99 ≤ 210 мс, 1000 WebSocket-соединений
без переподключений.
