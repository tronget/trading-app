package trading.data.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection

/**
 * Пул соединений + транзакционный помощник.
 * Вся работа с БД — голый SQL через JDBC (без ORM, по требованию задания).
 */
class Database(url: String, user: String, password: String) {
    val dataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = url
        username = user
        this.password = password
        maximumPoolSize = 20
        minimumIdle = 2
        poolName = "data-service"
    })

    private val tracer = GlobalOpenTelemetry.getTracer("data-service")

    /**
     * Выполняет [block] в одной транзакции: commit при успехе, rollback при
     * исключении. Каждая транзакция — спан в OpenTelemetry.
     */
    suspend fun <T> tx(name: String, block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        val span = tracer.spanBuilder("db.tx $name").setSpanKind(SpanKind.CLIENT).startSpan()
        try {
            span.makeCurrent().use {
                dataSource.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val result = block(connection)
                        connection.commit()
                        result
                    } catch (e: Exception) {
                        connection.rollback()
                        throw e
                    } finally {
                        connection.autoCommit = true
                    }
                }
            }
        } catch (e: Exception) {
            span.recordException(e)
            span.setStatus(StatusCode.ERROR)
            throw e
        } finally {
            span.end()
        }
    }

    fun close() = dataSource.close()
}
