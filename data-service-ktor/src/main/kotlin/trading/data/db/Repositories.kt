package trading.data.db

import trading.data.model.InstrumentDto
import trading.data.model.OrderDto
import trading.data.model.OrderStatus
import trading.data.model.OrderType
import trading.data.model.PositionDto
import trading.data.model.Side
import trading.data.model.TradeDto
import trading.data.model.UserDto
import trading.data.model.UserWithHashDto
import java.math.BigDecimal
import java.sql.Connection
import java.sql.ResultSet

/**
 * Репозитории на голом SQL. Все функции работают на переданном [Connection],
 * поэтому несколько вызовов составляются в одну транзакцию через Database.tx.
 * Только параметризованные запросы — защита от SQL-инъекций.
 */

// ---------- users / accounts ----------

object UserRepo {
    fun insert(c: Connection, login: String, passwordHash: String): UserDto =
        c.prepareStatement(
            "INSERT INTO users (login, password_hash) VALUES (?, ?) RETURNING id, login, created_at"
        ).use { st ->
            st.setString(1, login)
            st.setString(2, passwordHash)
            st.executeQuery().single { rs ->
                UserDto(rs.getLong("id"), rs.getString("login"), rs.getString("created_at"))
            }
        }

    fun findByLogin(c: Connection, login: String): UserWithHashDto? =
        c.prepareStatement("SELECT id, login, password_hash FROM users WHERE login = ?").use { st ->
            st.setString(1, login)
            st.executeQuery().singleOrNull { rs ->
                UserWithHashDto(rs.getLong("id"), rs.getString("login"), rs.getString("password_hash"))
            }
        }

    fun findById(c: Connection, id: Long): UserDto? =
        c.prepareStatement("SELECT id, login, created_at FROM users WHERE id = ?").use { st ->
            st.setLong(1, id)
            st.executeQuery().singleOrNull { rs ->
                UserDto(rs.getLong("id"), rs.getString("login"), rs.getString("created_at"))
            }
        }
}

object AccountRepo {
    fun insert(c: Connection, userId: Long, startBalance: BigDecimal, currency: String = "USD") {
        c.prepareStatement(
            "INSERT INTO accounts (user_id, currency, cash_balance) VALUES (?, ?, ?)"
        ).use { st ->
            st.setLong(1, userId)
            st.setString(2, currency)
            st.setBigDecimal(3, startBalance)
            st.executeUpdate()
        }
    }

    /** Блокирует строку счёта до конца транзакции (FOR UPDATE). */
    fun lockCash(c: Connection, userId: Long, currency: String = "USD"): BigDecimal? =
        c.prepareStatement(
            "SELECT cash_balance FROM accounts WHERE user_id = ? AND currency = ? FOR UPDATE"
        ).use { st ->
            st.setLong(1, userId)
            st.setString(2, currency)
            st.executeQuery().singleOrNull { rs -> rs.getBigDecimal("cash_balance") }
        }

    fun addCash(c: Connection, userId: Long, delta: BigDecimal, currency: String = "USD") {
        c.prepareStatement(
            "UPDATE accounts SET cash_balance = cash_balance + ? WHERE user_id = ? AND currency = ?"
        ).use { st ->
            st.setBigDecimal(1, delta)
            st.setLong(2, userId)
            st.setString(3, currency)
            st.executeUpdate()
        }
    }

    fun cash(c: Connection, userId: Long, currency: String = "USD"): BigDecimal? =
        c.prepareStatement(
            "SELECT cash_balance FROM accounts WHERE user_id = ? AND currency = ?"
        ).use { st ->
            st.setLong(1, userId)
            st.setString(2, currency)
            st.executeQuery().singleOrNull { rs -> rs.getBigDecimal("cash_balance") }
        }
}

object InstrumentRepo {
    fun all(c: Connection): List<InstrumentDto> =
        c.prepareStatement("SELECT id, symbol, name, dev_idx FROM instruments ORDER BY id").use { st ->
            st.executeQuery().list { rs ->
                InstrumentDto(rs.getLong("id"), rs.getString("symbol"), rs.getString("name"), rs.getInt("dev_idx"))
            }
        }

    fun exists(c: Connection, symbol: String): Boolean =
        c.prepareStatement("SELECT 1 FROM instruments WHERE symbol = ?").use { st ->
            st.setString(1, symbol)
            st.executeQuery().use { it.next() }
        }
}

// ---------- orders ----------

object OrderRepo {
    private const val FIELDS =
        "id, user_id, symbol, side, type, qty, price, filled_qty, status, client_order_id, created_at, updated_at"

    fun insert(
        c: Connection,
        userId: Long,
        symbol: String,
        side: Side,
        type: OrderType,
        qty: BigDecimal,
        price: BigDecimal?,
        clientOrderId: String?,
    ): OrderDto =
        c.prepareStatement(
            "INSERT INTO orders (user_id, symbol, side, type, qty, price, client_order_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING $FIELDS"
        ).use { st ->
            st.setLong(1, userId)
            st.setString(2, symbol)
            st.setString(3, side.name)
            st.setString(4, type.name)
            st.setBigDecimal(5, qty)
            st.setBigDecimal(6, price)
            st.setString(7, clientOrderId)
            st.executeQuery().single(::mapOrder)
        }

    fun findById(c: Connection, id: Long): OrderDto? =
        c.prepareStatement("SELECT $FIELDS FROM orders WHERE id = ?").use { st ->
            st.setLong(1, id)
            st.executeQuery().singleOrNull(::mapOrder)
        }

    fun findByClientOrderId(c: Connection, clientOrderId: String): OrderDto? =
        c.prepareStatement("SELECT $FIELDS FROM orders WHERE client_order_id = ?").use { st ->
            st.setString(1, clientOrderId)
            st.executeQuery().singleOrNull(::mapOrder)
        }

    fun listByUser(c: Connection, userId: Long, status: OrderStatus?, limit: Int): List<OrderDto> {
        val sql = buildString {
            append("SELECT $FIELDS FROM orders WHERE user_id = ?")
            if (status != null) append(" AND status = ?")
            append(" ORDER BY created_at DESC LIMIT ?")
        }
        return c.prepareStatement(sql).use { st ->
            var i = 1
            st.setLong(i++, userId)
            if (status != null) st.setString(i++, status.name)
            st.setInt(i, limit)
            st.executeQuery().list(::mapOrder)
        }
    }

    /**
     * Активные лимитки по символу, пересечённые текущей ценой.
     * FOR UPDATE SKIP LOCKED — чтобы матчинг не conflict-овал с отменой ордера.
     */
    fun lockCrossedLimitOrders(c: Connection, symbol: String, tickPrice: BigDecimal): List<OrderDto> =
        c.prepareStatement(
            "SELECT $FIELDS FROM orders " +
                "WHERE status = 'NEW' AND type = 'LIMIT' AND symbol = ? " +
                "AND ((side = 'BUY' AND price >= ?) OR (side = 'SELL' AND price <= ?)) " +
                "ORDER BY created_at FOR UPDATE SKIP LOCKED"
        ).use { st ->
            st.setString(1, symbol)
            st.setBigDecimal(2, tickPrice)
            st.setBigDecimal(3, tickPrice)
            st.executeQuery().list(::mapOrder)
        }

    /** Блокирует ордер для исполнения/отмены; null — если уже не NEW. */
    fun lockActive(c: Connection, id: Long): OrderDto? =
        c.prepareStatement("SELECT $FIELDS FROM orders WHERE id = ? AND status = 'NEW' FOR UPDATE").use { st ->
            st.setLong(1, id)
            st.executeQuery().singleOrNull(::mapOrder)
        }

    fun updateStatus(c: Connection, id: Long, status: OrderStatus, filledQty: BigDecimal? = null) {
        c.prepareStatement(
            "UPDATE orders SET status = ?, filled_qty = COALESCE(?, filled_qty), updated_at = now() WHERE id = ?"
        ).use { st ->
            st.setString(1, status.name)
            st.setBigDecimal(2, filledQty)
            st.setLong(3, id)
            st.executeUpdate()
        }
    }

    private fun mapOrder(rs: ResultSet) = OrderDto(
        id = rs.getLong("id"),
        userId = rs.getLong("user_id"),
        symbol = rs.getString("symbol"),
        side = Side.valueOf(rs.getString("side")),
        type = OrderType.valueOf(rs.getString("type")),
        qty = rs.getBigDecimal("qty"),
        price = rs.getBigDecimal("price"),
        filledQty = rs.getBigDecimal("filled_qty"),
        status = OrderStatus.valueOf(rs.getString("status")),
        clientOrderId = rs.getString("client_order_id"),
        createdAt = rs.getString("created_at"),
        updatedAt = rs.getString("updated_at"),
    )
}

// ---------- trades / positions ----------

object TradeRepo {
    fun insert(
        c: Connection,
        orderId: Long,
        userId: Long,
        symbol: String,
        side: Side,
        qty: BigDecimal,
        price: BigDecimal,
        fee: BigDecimal = BigDecimal.ZERO,
    ): TradeDto =
        c.prepareStatement(
            "INSERT INTO trades (order_id, user_id, symbol, side, qty, price, fee) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                "RETURNING id, order_id, user_id, symbol, side, qty, price, fee, executed_at"
        ).use { st ->
            st.setLong(1, orderId)
            st.setLong(2, userId)
            st.setString(3, symbol)
            st.setString(4, side.name)
            st.setBigDecimal(5, qty)
            st.setBigDecimal(6, price)
            st.setBigDecimal(7, fee)
            st.executeQuery().single(::mapTrade)
        }

    fun listByUser(c: Connection, userId: Long, limit: Int): List<TradeDto> =
        c.prepareStatement(
            "SELECT id, order_id, user_id, symbol, side, qty, price, fee, executed_at " +
                "FROM trades WHERE user_id = ? ORDER BY executed_at DESC LIMIT ?"
        ).use { st ->
            st.setLong(1, userId)
            st.setInt(2, limit)
            st.executeQuery().list(::mapTrade)
        }

    private fun mapTrade(rs: ResultSet) = TradeDto(
        id = rs.getLong("id"),
        orderId = rs.getLong("order_id"),
        userId = rs.getLong("user_id"),
        symbol = rs.getString("symbol"),
        side = Side.valueOf(rs.getString("side")),
        qty = rs.getBigDecimal("qty"),
        price = rs.getBigDecimal("price"),
        fee = rs.getBigDecimal("fee"),
        executedAt = rs.getString("executed_at"),
    )
}

object PositionRepo {
    /** Блокирует позицию (FOR UPDATE); null — позиции нет. */
    fun lock(c: Connection, userId: Long, symbol: String): PositionDto? =
        c.prepareStatement(
            "SELECT symbol, qty, avg_price FROM positions WHERE user_id = ? AND symbol = ? FOR UPDATE"
        ).use { st ->
            st.setLong(1, userId)
            st.setString(2, symbol)
            st.executeQuery().singleOrNull(::mapPosition)
        }

    fun upsert(c: Connection, userId: Long, symbol: String, qty: BigDecimal, avgPrice: BigDecimal) {
        c.prepareStatement(
            "INSERT INTO positions (user_id, symbol, qty, avg_price) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT (user_id, symbol) DO UPDATE SET qty = EXCLUDED.qty, avg_price = EXCLUDED.avg_price"
        ).use { st ->
            st.setLong(1, userId)
            st.setString(2, symbol)
            st.setBigDecimal(3, qty)
            st.setBigDecimal(4, avgPrice)
            st.executeUpdate()
        }
    }

    fun listByUser(c: Connection, userId: Long): List<PositionDto> =
        c.prepareStatement(
            "SELECT symbol, qty, avg_price FROM positions WHERE user_id = ? AND qty > 0 ORDER BY symbol"
        ).use { st ->
            st.setLong(1, userId)
            st.executeQuery().list(::mapPosition)
        }

    private fun mapPosition(rs: ResultSet) = PositionDto(
        symbol = rs.getString("symbol"),
        qty = rs.getBigDecimal("qty"),
        avgPrice = rs.getBigDecimal("avg_price"),
    )
}

// ---------- ResultSet helpers ----------

private inline fun <T> ResultSet.single(map: (ResultSet) -> T): T = use {
    check(next()) { "query returned no rows" }
    map(this)
}

private inline fun <T> ResultSet.singleOrNull(map: (ResultSet) -> T): T? = use {
    if (next()) map(this) else null
}

private inline fun <T> ResultSet.list(map: (ResultSet) -> T): List<T> = use {
    val result = mutableListOf<T>()
    while (next()) result += map(this)
    result
}
