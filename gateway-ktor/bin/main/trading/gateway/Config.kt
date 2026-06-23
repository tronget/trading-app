package trading.gateway

data class Config(
    val port: Int = env("PORT", "8080").toInt(),
    val dataServiceUrl: String = env("DATA_SERVICE_URL", "http://localhost:8082"),
    val redisHost: String = env("REDIS_ADDR", "localhost"),
    val redisPort: Int = env("REDIS_PORT", "6379").toInt(),
    val clickhouseUrl: String = env("CLICKHOUSE_URL", "http://localhost:8123"),
    val clickhouseUser: String = env("CLICKHOUSE_USER", "trading"),
    val clickhousePassword: String = env("CLICKHOUSE_PASSWORD", "trading"),
    val clickhouseDb: String = env("CLICKHOUSE_DB", "trading"),
    val jwtSecret: String = env("JWT_SECRET", "dev-secret-change-me"),
    val jwtIssuer: String = env("JWT_ISSUER", "trading-gateway"),
    val accessTtlMinutes: Long = env("JWT_ACCESS_TTL_MIN", "15").toLong(),
    val refreshTtlDays: Long = env("JWT_REFRESH_TTL_DAYS", "7").toLong(),
    val rateLimitPerSecond: Int = env("RATE_LIMIT_RPS", "30").toInt(),
    val bcryptCost: Int = env("BCRYPT_COST", "10").toInt(),
) {
    companion object {
        private fun env(key: String, fallback: String): String =
            System.getenv(key)?.takeIf { it.isNotBlank() } ?: fallback
    }
}
