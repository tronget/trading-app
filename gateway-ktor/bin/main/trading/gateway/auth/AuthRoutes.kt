package trading.gateway.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import trading.gateway.model.AccessTokenResponse
import trading.gateway.model.CreatedUser
import trading.gateway.model.CredentialsRequest
import trading.gateway.model.ErrorResponse
import trading.gateway.model.LoginResponse
import trading.gateway.model.RefreshRequest
import trading.gateway.model.RegisterResponse
import trading.gateway.model.UserWithHash
import trading.gateway.trading.DataServiceClient
import trading.gateway.trading.passthrough

/** Регистрация, логин и обновление токена. Пароли хэшируются bcrypt'ом. */
fun Route.authRoutes(tokens: TokenService, dataService: DataServiceClient, bcryptCost: Int = 10) {
    val json = Json { ignoreUnknownKeys = true }

    post("/auth/register") {
        val credentials = call.receive<CredentialsRequest>()
        if (credentials.login.isBlank() || credentials.password.length < 4) {
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("login required, password must be at least 4 chars"))
            return@post
        }

        val hash = BCrypt.withDefaults().hashToString(bcryptCost, credentials.password.toCharArray())
        val body = buildJsonObject {
            put("login", credentials.login.trim())
            put("passwordHash", hash)
        }
        val (status, responseBody) = dataService.postJson("/internal/users", body.toString()).passthrough()
        if (status != HttpStatusCode.Created.value) {
            call.respondText(responseBody, ContentType.Application.Json, HttpStatusCode.fromValue(status))
            return@post
        }

        val user = json.decodeFromString<CreatedUser>(responseBody)
        call.respond(
            HttpStatusCode.Created,
            RegisterResponse(
                userId = user.id,
                accessToken = tokens.issueAccess(user.id, user.login),
                refreshToken = tokens.issueRefresh(user.id, user.login),
            ),
        )
    }

    post("/auth/login") {
        val credentials = call.receive<CredentialsRequest>()
        val (status, responseBody) = dataService.get("/internal/users/by-login/${credentials.login.trim()}").passthrough()
        if (status != HttpStatusCode.OK.value) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid login or password"))
            return@post
        }

        val user = json.decodeFromString<UserWithHash>(responseBody)
        val verified = BCrypt.verifyer().verify(credentials.password.toCharArray(), user.passwordHash)
        if (!verified.verified) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid login or password"))
            return@post
        }

        call.respond(
            LoginResponse(
                accessToken = tokens.issueAccess(user.id, user.login),
                refreshToken = tokens.issueRefresh(user.id, user.login),
            ),
        )
    }

    post("/auth/refresh") {
        val request = call.receive<RefreshRequest>()
        val verified = tokens.verifyRefresh(request.refreshToken)
        if (verified == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid refresh token"))
            return@post
        }
        val (userId, login) = verified
        call.respond(AccessTokenResponse(tokens.issueAccess(userId, login)))
    }
}
