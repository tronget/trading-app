# Data Service (#4)

Ядро бизнес-логики: пользователи, счета, ордера, сделки, портфели.
Kotlin + Ktor, **голый SQL** (JDBC + HikariCP, без ORM), PostgreSQL,
цены — из Redis (кэш `quote:last:<symbol>` + Pub/Sub `quotes:ticks`).

## Архитектура

- `db/Repositories.kt` — параметризованные SQL-запросы поверх `Connection`;
  несколько вызовов компонуются в одну транзакцию через `Database.tx`.
- `service/OrderService.kt` — размещение/исполнение ордеров:
  - **MARKET** исполняется сразу по последней цене из Redis-кэша;
  - **LIMIT** сохраняется со статусом `NEW` и матчится воркером;
  - идемпотентность по `clientOrderId` (повторный POST вернёт существующий ордер);
  - нехватка средств/позиции → `REJECTED`.
- `service/MatchingWorker.kt` — слушает `quotes:ticks`, исполняет пересечённые
  лимитки (`FOR UPDATE SKIP LOCKED`) по цене тика, не блокируя HTTP.
- Все изменения «сделка + позиция + баланс» — в одной транзакции; строки
  счёта/позиции блокируются `FOR UPDATE`, баланс защищён `CHECK (cash_balance >= 0)`.
- OpenTelemetry: спан на HTTP-запрос (с извлечением W3C `traceparent` от
  Gateway) + спан на каждую SQL-транзакцию. Включается переменной
  `OTEL_EXPORTER_OTLP_ENDPOINT`.

## Внутренний API (для Gateway #3)

```http
POST   /internal/users                      { login, passwordHash } → 201 | 409
GET    /internal/users/{id}
GET    /internal/users/by-login/{login}     → { id, login, passwordHash }
GET    /internal/instruments
POST   /internal/orders                     { userId, symbol, side, type, qty, price?, clientOrderId? } → 201
GET    /internal/orders?userId=&status=&limit=
GET    /internal/orders/{id}
DELETE /internal/orders/{id}?userId=        → CANCELLED
GET    /internal/trades?userId=&limit=
GET    /internal/portfolio/{userId}         → { cash, positions[{symbol,qty,avgPrice,last,pnl}], totalValue }
GET    /health
```

Денежные поля сериализуются **строками** (`"qty":"2.5"`), чтобы не терять
точность NUMERIC(18,6).

## Конфигурация (env)

| Переменная        | По умолчанию                              |
|-------------------|-------------------------------------------|
| `PORT`            | `8082`                                    |
| `DB_URL`          | `jdbc:postgresql://localhost:5432/trading`|
| `DB_USER` / `DB_PASSWORD` | `trading` / `trading`             |
| `REDIS_ADDR` / `REDIS_PORT` | `localhost` / `6379`            |
| `START_BALANCE`   | `1000000.00` (стартовый баланс при регистрации) |

## Запуск и тесты

```bash
./gradlew run            # нужны PostgreSQL (схема из deploy/init/postgres) и Redis
./gradlew test           # юнит-тесты бизнес-логики
./gradlew installDist    # дистрибутив в build/install/data-service
```
