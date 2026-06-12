package trading.data

import java.math.BigDecimal

data class Config(
    val port: Int = env("PORT", "8082").toInt(),
    val dbUrl: String = env("DB_URL", "jdbc:postgresql://localhost:5432/trading"),
    val dbUser: String = env("DB_USER", "trading"),
    val dbPassword: String = env("DB_PASSWORD", "trading"),
    val redisHost: String = env("REDIS_ADDR", "localhost"),
    val redisPort: Int = env("REDIS_PORT", "6379").toInt(),
    val startBalance: BigDecimal = BigDecimal(env("START_BALANCE", "1000000.00")),
) {
    companion object {
        private fun env(key: String, fallback: String): String =
            System.getenv(key)?.takeIf { it.isNotBlank() } ?: fallback
    }
}
