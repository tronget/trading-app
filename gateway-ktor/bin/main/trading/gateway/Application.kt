package trading.gateway

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import trading.gateway.auth.TokenService
import trading.gateway.auth.authRoutes
import trading.gateway.model.ErrorResponse
import trading.gateway.otel.initTelemetry
import trading.gateway.otel.installServerTracing
import trading.gateway.quotes.ClickhouseClient
import trading.gateway.quotes.QuoteHub
import trading.gateway.quotes.quoteRoutes
import trading.gateway.trading.DataServiceClient
import trading.gateway.trading.tradingRoutes
import kotlin.time.Duration.Companion.seconds

/**
 * Gateway (#3): единая точка входа для клиентов — JWT-аутентификация,
 * rate limiting, WebSocket-стрим котировок, проксирование торговли
 * в Data-сервис и история свечей из ClickHouse.
 */
fun main() {
    initTelemetry()
    val config = Config()
    val log = LoggerFactory.getLogger("trading.gateway.Application")

    val tokens = TokenService(config)
    val dataService = DataServiceClient(config.dataServiceUrl)
    val clickhouse = ClickhouseClient(config)
    val hub = QuoteHub(config.redisHost, config.redisPort)
    hub.start()

    log.info("starting gateway on port {}", config.port)
    embeddedServer(Netty, port = config.port) {
        module(config, tokens, dataService, clickhouse, hub)
    }.start(wait = true)
}

fun Application.module(
    config: Config,
    tokens: TokenService,
    dataService: DataServiceClient,
    clickhouse: ClickhouseClient,
    hub: QuoteHub,
) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(CallLogging)
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Delete)
    }
    install(WebSockets) {
        pingPeriod = 30.seconds
    }
    install(RateLimit) {
        register(RateLimitName("api")) {
            rateLimiter(limit = config.rateLimitPerSecond, refillPeriod = 1.seconds)
            requestKey { call -> call.request.origin.remoteHost }
        }
    }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, e ->
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse(e.message ?: "bad request"))
        }
        exception<Exception> { call, e ->
            call.application.log.error("unhandled error", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal error"))
        }
        status(HttpStatusCode.TooManyRequests) { call, status ->
            call.respond(status, ErrorResponse("rate limit exceeded"))
        }
    }
    install(Authentication) {
        jwt("jwt") {
            verifier(tokens.verifier)
            validate { credential ->
                if (credential.payload.subject?.toLongOrNull() != null) JWTPrincipal(credential.payload) else null
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("token is invalid or expired"))
            }
        }
    }
    installServerTracing()

    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }

        rateLimit(RateLimitName("api")) {
            authRoutes(tokens, dataService, config.bcryptCost)
            quoteRoutes(hub, clickhouse, dataService, tokens)

            authenticate("jwt") {
                tradingRoutes(dataService)
            }
        }
    }

    monitor.subscribe(io.ktor.server.application.ApplicationStopped) {
        hub.close()
    }
}
