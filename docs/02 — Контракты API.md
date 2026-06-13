# Контракты API

## Публичный API (Gateway :8080)

### Аутентификация
```
POST /auth/register   { login, password }      → 201 { userId, accessToken, refreshToken }
POST /auth/login      { login, password }      → 200 { accessToken, refreshToken } | 401
POST /auth/refresh    { refreshToken }         → 200 { accessToken } | 401
```
JWT HS256: access 15 мин, refresh 7 дней (claim `type` различает их).
Дальше всюду `Authorization: Bearer <accessToken>`.

### Котировки
```
GET /quotes                                    → [ { symbol, name, last, changePct, volume } ]
GET /quotes/{symbol}/history?interval&from&to&limit → [ { t, open, high, low, close, volume } ]
GET /ws?token=<jwt>          WebSocket
    → { "action":"subscribe", "symbols":["SBER"] } | "unsubscribe"
    ← { symbol, price, ts, volume, seq }
```
`interval ∈ {1m,5m,15m,1h,1d}`, `from/to` — unix millis (по умолчанию сутки).

### Торговля
```
GET    /portfolio                              → { cash, positions[], totalValue }
GET    /orders?status=&limit=                  → [ Order ]
POST   /orders   { symbol, side, type, qty, price?, clientOrderId? } → 201 Order
DELETE /orders/{id}                            → Order (CANCELLED)
GET    /trades?limit=                          → [ Trade ]
```
`side ∈ {BUY,SELL}`, `type ∈ {MARKET,LIMIT}`,
`status ∈ {NEW,FILLED,PARTIALLY_FILLED,CANCELLED,REJECTED}`.
Ошибки: `{ "error": "..." }`; 401 — токен, 422 — валидация, 429 — rate limit.
Money-поля — **строки** (`"qty":"2.5"`).

## Внутренний API (Data-сервис :8082, только для Gateway)

```
POST   /internal/users                     { login, passwordHash } → 201 | 409
GET    /internal/users/{id}
GET    /internal/users/by-login/{login}    → { id, login, passwordHash }
GET    /internal/instruments
POST   /internal/orders                    { userId, ... } → 201
GET    /internal/orders?userId=&status=&limit=
DELETE /internal/orders/{id}?userId=
GET    /internal/trades?userId=&limit=
GET    /internal/portfolio/{userId}
```

## Redis-контракт (quotes-go → потребители)

```
PUBLISH quotes:ticks        { "symbol":"SBER","price":305.5,"ts":1781297920237,"volume":158,"seq":25 }
SET quote:last:<symbol>     тот же JSON
```

## Сервис котировок (quotes-go :8081)

```
GET /health | /metrics | /v1/devices
GET /v1/ticks/latest[?device=N] | /v1/ticks/history?device=N&limit=K
GET /v1/stream[?device=N]       (SSE, event: tick)
```
