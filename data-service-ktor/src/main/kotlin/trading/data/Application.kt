package trading.data

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import trading.data.db.Database
import trading.data.model.ErrorResponse
import trading.data.otel.initTelemetry
import trading.data.otel.installServerTracing
import trading.data.redis.PriceCache
import trading.data.routes.internalRoutes
import trading.data.service.MatchingWorker
import trading.data.service.NotFoundException
import trading.data.service.OrderService
import trading.data.service.PortfolioService
import trading.data.service.ValidationException

/**
 * Data-сервис (#4): пользователи, счета, ордера, сделки, портфели.
 * Ktor + голый SQL (PostgreSQL), цены — из Redis (кэш + Pub/Sub).
 */
fun main() {
    initTelemetry()
    val config = Config()
    val log = LoggerFactory.getLogger("trading.data.Application")

    val db = Database(config.dbUrl, config.dbUser, config.dbPassword)
    val prices = PriceCache(config.redisHost, config.redisPort)
    prices.start()

    val orders = OrderService(db, prices)
    val portfolio = PortfolioService(db, prices)

    log.info("starting data-service on port {}", config.port)
    embeddedServer(Netty, port = config.port) {
        module(config, db, prices, orders, portfolio)
    }.start(wait = true)
}

fun Application.module(
    config: Config,
    db: Database,
    prices: PriceCache,
    orders: OrderService,
    portfolio: PortfolioService,
) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(CallLogging)
    install(StatusPages) {
        exception<ValidationException> { call, e ->
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse(e.message ?: "validation failed"))
        }
        exception<NotFoundException> { call, e ->
            call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "not found"))
        }
        exception<Exception> { call, e ->
            call.application.log.error("unhandled error", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal error"))
        }
    }
    installServerTracing()

    MatchingWorker(db, prices, orders).start(this)

    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }
        internalRoutes(config, db, orders, portfolio)
    }

    monitor.subscribe(io.ktor.server.application.ApplicationStopped) {
        prices.close()
        db.close()
    }
}
