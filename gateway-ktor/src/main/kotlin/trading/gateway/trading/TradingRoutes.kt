package trading.gateway.trading

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import trading.gateway.model.ErrorResponse

/**
 * Торговые маршруты: проксирование в Data-сервис с подстановкой userId
 * из JWT. Тела ответов передаются как есть (там уже строковые money-поля).
 */
fun Route.tradingRoutes(dataService: DataServiceClient) {
    val json = Json { ignoreUnknownKeys = true }

    get("/portfolio") {
        call.proxy(dataService.get("/internal/portfolio/${call.userId()}").passthrough())
    }

    get("/orders") {
        val status = call.request.queryParameters["status"]?.let { "&status=$it" } ?: ""
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.let { "&limit=$it" } ?: ""
        call.proxy(dataService.get("/internal/orders?userId=${call.userId()}$status$limit").passthrough())
    }

    post("/orders") {
        val raw = runCatching { json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
        if (raw == null) {
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("body must be a JSON object"))
            return@post
        }
        // клиент не может выставить ордер от чужого имени — userId только из JWT
        val withUser = JsonObject(raw + ("userId" to JsonPrimitive(call.userId())))
        call.proxy(dataService.postJson("/internal/orders", withUser.toString()).passthrough())
    }

    delete("/orders/{id}") {
        val id = call.parameters["id"]?.toLongOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("order id must be a number"))
            return@delete
        }
        call.proxy(dataService.delete("/internal/orders/$id?userId=${call.userId()}").passthrough())
    }

    get("/trades") {
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.let { "&limit=$it" } ?: ""
        call.proxy(dataService.get("/internal/trades?userId=${call.userId()}$limit").passthrough())
    }
}

private fun RoutingCall.userId(): Long =
    principal<JWTPrincipal>()?.subject?.toLongOrNull()
        ?: error("jwt principal is missing")

private suspend fun RoutingCall.proxy(response: Pair<Int, String>) {
    val (status, body) = response
    respondText(body, ContentType.Application.Json, HttpStatusCode.fromValue(status))
}
