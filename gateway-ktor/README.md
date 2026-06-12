# Gateway (#3)

Единая точка входа для мобильных клиентов и нагрузочного имитатора:
JWT-аутентификация, rate limiting, WebSocket-стрим котировок, история свечей
из ClickHouse, проксирование торговых операций в Data-сервис (#4).

## Публичный API

**Аутентификация** (пароли — bcrypt, токены — JWT HS256; access 15 мин, refresh 7 дней):

```http
POST /auth/register   { "login", "password" }        → 201 { userId, accessToken, refreshToken }
POST /auth/login      { "login", "password" }        → 200 { accessToken, refreshToken } | 401
POST /auth/refresh    { "refreshToken" }             → 200 { accessToken } | 401
```

**Котировки:**

```http
GET /quotes                                          → [ { symbol, name, last, changePct, volume } ]
GET /quotes/{symbol}/history?from&to&interval&limit  → [ { t, open, high, low, close, volume } ]
GET /ws?token=<accessToken>      (WebSocket)
    → { "action":"subscribe", "symbols":["SBER","BTC"] }   // и "unsubscribe"
    ← { symbol, price, ts, volume, seq }                    // поток тиков
```

`interval` ∈ `1m | 5m | 15m | 1h | 1d`; `from`/`to` — unix-миллисекунды
(по умолчанию последние 24 часа). `changePct` — изменение к цене открытия дня
(из ClickHouse, кэш 30 с). При подписке по WS сразу приходит снапшот
последней цены.

**Торговля** (требуется `Authorization: Bearer <accessToken>`; `userId`
подставляется из токена — чужой ордер выставить нельзя):

```http
GET    /portfolio
GET    /orders?status=&limit=
POST   /orders        { symbol, side, type, qty, price?, clientOrderId? } → 201
DELETE /orders/{id}
GET    /trades?limit=
```

## Устройство

- `QuoteHub` — одна подписка на Redis `quotes:ticks` на весь Gateway,
  fan-out клиентам через `SharedFlow` (медленный клиент теряет старые тики,
  но не тормозит остальных) + кэш `quote:last:<symbol>`.
- `ClickhouseClient` — свечи агрегируются из сырых тиков на лету
  (`argMin`/`argMax`/`min`/`max`), только параметризованные запросы (`param_*`).
- `DataServiceClient` — прокси с инжекцией W3C `traceparent`: trace-id идёт
  сквозь Gateway → Data-сервис до SQL-транзакций.
- Rate limiting — `RATE_LIMIT_RPS` запросов/с на IP (по умолчанию 30), 429 при превышении.

## Конфигурация (env)

| Переменная | По умолчанию |
|---|---|
| `PORT` | `8080` |
| `DATA_SERVICE_URL` | `http://localhost:8082` |
| `REDIS_ADDR` / `REDIS_PORT` | `localhost` / `6379` |
| `CLICKHOUSE_URL` / `CLICKHOUSE_USER` / `CLICKHOUSE_PASSWORD` / `CLICKHOUSE_DB` | `http://localhost:8123` / `trading` / `trading` / `trading` |
| `JWT_SECRET` | `dev-secret-change-me` |
| `RATE_LIMIT_RPS` | `30` |

## Запуск и тесты

```bash
./gradlew run            # нужны Redis, ClickHouse и запущенный data-service
./gradlew test
./gradlew installDist
```
