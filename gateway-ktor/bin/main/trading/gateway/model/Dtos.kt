package trading.gateway.model

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(val error: String)

// ---------- auth ----------

@Serializable
data class CredentialsRequest(val login: String, val password: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class RegisterResponse(val userId: Long, val accessToken: String, val refreshToken: String)

@Serializable
data class LoginResponse(val accessToken: String, val refreshToken: String)

@Serializable
data class AccessTokenResponse(val accessToken: String)

/** Ответ data-сервиса /internal/users/by-login (хэш наружу не отдаётся). */
@Serializable
data class UserWithHash(val id: Long, val login: String, val passwordHash: String)

@Serializable
data class CreatedUser(val id: Long, val login: String, val createdAt: String)

// ---------- quotes ----------

@Serializable
data class Instrument(val id: Long, val symbol: String, val name: String, val devIdx: Int)

@Serializable
data class QuoteItem(
    val symbol: String,
    val name: String,
    val last: Double? = null,
    val changePct: Double? = null,
    val volume: Long? = null,
)

@Serializable
data class Candle(
    val t: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
)

/** Тик из Redis quotes:ticks (контракт сервиса котировок #6). */
@Serializable
data class Tick(
    val symbol: String,
    val price: Double,
    val ts: Long,
    val volume: Int = 0,
    val seq: Long = 0,
)

@Serializable
data class SubscribeCommand(val action: String, val symbols: List<String> = emptyList())
