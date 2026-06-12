-- Схема БД торговой платформы (Data-сервис #4).
-- Все денежные операции выполняются в транзакциях; баланс не может уйти в минус (CHECK).

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    login         TEXT        NOT NULL UNIQUE,
    password_hash TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE accounts (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES users (id),
    currency     TEXT        NOT NULL DEFAULT 'USD',
    cash_balance NUMERIC(18, 2) NOT NULL DEFAULT 0 CHECK (cash_balance >= 0),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX accounts_user_currency_uq ON accounts (user_id, currency);

-- Маппинг тикеров на устройства драйвера /dev/stockN
CREATE TABLE instruments (
    id      BIGSERIAL PRIMARY KEY,
    symbol  TEXT NOT NULL UNIQUE,
    name    TEXT NOT NULL,
    dev_idx INT  NOT NULL
);

INSERT INTO instruments (symbol, name, dev_idx)
VALUES ('SBER', 'Sberbank', 0),
       ('GAZP', 'Gazprom', 1),
       ('AAPL', 'Apple Inc.', 2),
       ('BTC', 'Bitcoin', 3);

CREATE TABLE orders (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT      NOT NULL REFERENCES users (id),
    symbol          TEXT        NOT NULL,
    side            TEXT        NOT NULL CHECK (side IN ('BUY', 'SELL')),
    type            TEXT        NOT NULL CHECK (type IN ('MARKET', 'LIMIT')),
    qty             NUMERIC(18, 6) NOT NULL CHECK (qty > 0),
    price           NUMERIC(18, 6),
    filled_qty      NUMERIC(18, 6) NOT NULL DEFAULT 0,
    status          TEXT        NOT NULL DEFAULT 'NEW'
        CHECK (status IN ('NEW', 'FILLED', 'PARTIALLY_FILLED', 'CANCELLED', 'REJECTED')),
    client_order_id TEXT UNIQUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Для матчинга лимиток: выборка активных заявок по символу
CREATE INDEX orders_status_symbol_idx ON orders (status, symbol);
CREATE INDEX orders_user_idx ON orders (user_id, created_at DESC);

CREATE TABLE trades (
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT      NOT NULL REFERENCES orders (id),
    user_id     BIGINT      NOT NULL REFERENCES users (id),
    symbol      TEXT        NOT NULL,
    side        TEXT        NOT NULL CHECK (side IN ('BUY', 'SELL')),
    qty         NUMERIC(18, 6) NOT NULL,
    price       NUMERIC(18, 6) NOT NULL,
    fee         NUMERIC(18, 6) NOT NULL DEFAULT 0,
    executed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX trades_user_executed_idx ON trades (user_id, executed_at DESC);

CREATE TABLE positions (
    user_id   BIGINT      NOT NULL REFERENCES users (id),
    symbol    TEXT        NOT NULL,
    qty       NUMERIC(18, 6) NOT NULL CHECK (qty >= 0),
    avg_price NUMERIC(18, 6) NOT NULL,
    PRIMARY KEY (user_id, symbol)
);
