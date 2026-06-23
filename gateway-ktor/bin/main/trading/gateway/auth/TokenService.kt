package trading.gateway.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import trading.gateway.Config
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Выпуск и проверка JWT (HS256). Access-токен — для API, refresh — только
 * для /auth/refresh (различаются claim'ом type).
 */
class TokenService(private val config: Config) {
    private val algorithm = Algorithm.HMAC256(config.jwtSecret)

    val verifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(config.jwtIssuer)
        .withClaim("type", "access")
        .build()

    private val refreshVerifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(config.jwtIssuer)
        .withClaim("type", "refresh")
        .build()

    fun issueAccess(userId: Long, login: String): String =
        JWT.create()
            .withIssuer(config.jwtIssuer)
            .withSubject(userId.toString())
            .withClaim("login", login)
            .withClaim("type", "access")
            .withExpiresAt(Instant.now().plus(config.accessTtlMinutes, ChronoUnit.MINUTES))
            .sign(algorithm)

    fun issueRefresh(userId: Long, login: String): String =
        JWT.create()
            .withIssuer(config.jwtIssuer)
            .withSubject(userId.toString())
            .withClaim("login", login)
            .withClaim("type", "refresh")
            .withExpiresAt(Instant.now().plus(config.refreshTtlDays, ChronoUnit.DAYS))
            .sign(algorithm)

    /** Возвращает (userId, login) из refresh-токена или null, если он невалиден. */
    fun verifyRefresh(token: String): Pair<Long, String>? = try {
        val decoded = refreshVerifier.verify(token)
        val userId = decoded.subject?.toLongOrNull() ?: return null
        val login = decoded.getClaim("login").asString() ?: return null
        userId to login
    } catch (_: JWTVerificationException) {
        null
    }

    /** Проверяет access-токен (для WebSocket, где нет Authorization-плагина). */
    fun verifyAccess(token: String): Pair<Long, String>? = try {
        val decoded = verifier.verify(token)
        val userId = decoded.subject?.toLongOrNull() ?: return null
        val login = decoded.getClaim("login").asString() ?: return null
        userId to login
    } catch (_: JWTVerificationException) {
        null
    }
}
