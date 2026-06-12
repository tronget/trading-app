package trading.data.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.postgresql.util.PSQLException
import trading.data.Config
import trading.data.db.AccountRepo
import trading.data.db.Database
import trading.data.db.InstrumentRepo
import trading.data.db.TradeRepo
import trading.data.db.UserRepo
import trading.data.model.CreateUserRequest
import trading.data.model.ErrorResponse
import trading.data.model.OrderStatus
import trading.data.model.PlaceOrderRequest
import trading.data.service.NotFoundException
import trading.data.service.OrderService
import trading.data.service.PortfolioService
import trading.data.service.ValidationException

private const val UNIQUE_VIOLATION = "23505"

/**
 * Внутренний API для Gateway (#3). Наружу не публикуется — авторизация
 * и rate limiting выполняются на стороне Gateway.
 */
fun Route.internalRoutes(
    config: Config,
    db: Database,
    orders: OrderService,
    portfolio: PortfolioService,
) {
    post("/internal/users") {
        val request = call.receive<CreateUserRequest>()
        if (request.login.isBlank() || request.passwordHash.isBlank()) {
            throw ValidationException("login and passwordHash are required")
        }
        val user = try {
            db.tx("create_user") { c ->
                val created = UserRepo.insert(c, request.login.trim(), request.passwordHash)
                AccountRepo.insert(c, created.id, config.startBalance)
                created
            }
        } catch (e: PSQLException) {
            if (e.sqlState == UNIQUE_VIOLATION) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse("login already taken"))
                return@post
            }
            throw e
        }
        call.respond(HttpStatusCode.Created, user)
    }

    get("/internal/users/by-login/{login}") {
        val login = call.parameters["login"]!!
        val user = db.tx("find_user") { c -> UserRepo.findByLogin(c, login) }
            ?: throw NotFoundException("user $login not found")
        call.respond(user)
    }

    get("/internal/users/{id}") {
        val id = call.parameters["id"]?.toLongOrNull()
            ?: throw ValidationException("id must be a number")
        val user = db.tx("find_user") { c -> UserRepo.findById(c, id) }
            ?: throw NotFoundException("user $id not found")
        call.respond(user)
    }

    get("/internal/instruments") {
        call.respond(db.tx("instruments") { c -> InstrumentRepo.all(c) })
    }

    post("/internal/orders") {
        val request = call.receive<PlaceOrderRequest>()
        call.respond(HttpStatusCode.Created, orders.place(request))
    }

    get("/internal/orders") {
        val userId = call.queryLong("userId")
        val status = call.request.queryParameters["status"]?.let {
            runCatching { OrderStatus.valueOf(it) }.getOrNull()
                ?: throw ValidationException("unknown status $it")
        }
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
        call.respond(orders.list(userId, status, limit))
    }

    get("/internal/orders/{id}") {
        val id = call.parameters["id"]?.toLongOrNull()
            ?: throw ValidationException("id must be a number")
        call.respond(orders.get(id))
    }

    delete("/internal/orders/{id}") {
        val id = call.parameters["id"]?.toLongOrNull()
            ?: throw ValidationException("id must be a number")
        val userId = call.queryLong("userId")
        call.respond(orders.cancel(id, userId))
    }

    get("/internal/trades") {
        val userId = call.queryLong("userId")
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
        call.respond(db.tx("list_trades") { c -> TradeRepo.listByUser(c, userId, limit) })
    }

    get("/internal/portfolio/{userId}") {
        val userId = call.parameters["userId"]?.toLongOrNull()
            ?: throw ValidationException("userId must be a number")
        call.respond(portfolio.portfolio(userId))
    }
}

private fun io.ktor.server.application.ApplicationCall.queryLong(name: String): Long =
    request.queryParameters[name]?.toLongOrNull()
        ?: throw ValidationException("query parameter $name is required")
