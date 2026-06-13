package com.trading.app.data

import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString

/** Интерфейсы — для подмены фейками в тестах ViewModel. */

interface AuthRepository {
    suspend fun register(login: String, password: String)
    suspend fun login(login: String, password: String)
    suspend fun logout()
    suspend fun isLoggedIn(): Boolean
}

interface MarketRepository {
    suspend fun quotes(): List<QuoteItem>
    suspend fun history(symbol: String, interval: String, limit: Int): List<Candle>
    fun ticker(symbols: List<String>): Flow<Tick>
}

interface TradingRepository {
    suspend fun portfolio(): Portfolio
    suspend fun orders(): List<Order>
    suspend fun trades(): List<Trade>
    suspend fun placeOrder(request: PlaceOrderRequest): Order
    suspend fun cancelOrder(id: Long): Order
}

class AuthRepositoryImpl(private val api: ApiClient, private val tokens: TokenStore) : AuthRepository {
    override suspend fun register(login: String, password: String) {
        val response: RegisterResponse = with(api) {
            http.post("$baseUrl/auth/register") { jsonBody(CredentialsRequest(login, password)) }.orThrow()
        }
        tokens.save(response.accessToken, response.refreshToken)
    }

    override suspend fun login(login: String, password: String) {
        val response: LoginResponse = with(api) {
            http.post("$baseUrl/auth/login") { jsonBody(CredentialsRequest(login, password)) }.orThrow()
        }
        tokens.save(response.accessToken, response.refreshToken)
    }

    override suspend fun logout() = tokens.clear()

    override suspend fun isLoggedIn(): Boolean = tokens.accessToken.first() != null
}

class MarketRepositoryImpl(private val api: ApiClient, private val tokens: TokenStore) : MarketRepository {
    override suspend fun quotes(): List<QuoteItem> = with(api) {
        http.get("$baseUrl/quotes").orThrow()
    }

    override suspend fun history(symbol: String, interval: String, limit: Int): List<Candle> = with(api) {
        http.get("$baseUrl/quotes/$symbol/history") {
            parameter("interval", interval)
            parameter("limit", limit)
        }.orThrow()
    }

    /** Поток тиков по WebSocket; переподключается при обрыве. */
    override fun ticker(symbols: List<String>): Flow<Tick> = flow {
        val wsUrl = api.baseUrl.replaceFirst("http", "ws")
        while (true) {
            try {
                val token = tokens.current().first ?: break
                api.http.webSocket("$wsUrl/ws?token=$token") {
                    send(Frame.Text(api.json.encodeToString(SubscribeCommand("subscribe", symbols))))
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val tick = runCatching {
                            api.json.decodeFromString<Tick>(frame.readText())
                        }.getOrNull() ?: continue
                        emit(tick)
                    }
                }
            } catch (e: Exception) {
                // обрыв сети — ретрай ниже
            }
            delay(2000)
        }
    }
}

class TradingRepositoryImpl(private val api: ApiClient) : TradingRepository {
    override suspend fun portfolio(): Portfolio = with(api) {
        http.get("$baseUrl/portfolio").orThrow()
    }

    override suspend fun orders(): List<Order> = with(api) {
        http.get("$baseUrl/orders").orThrow()
    }

    override suspend fun trades(): List<Trade> = with(api) {
        http.get("$baseUrl/trades").orThrow()
    }

    override suspend fun placeOrder(request: PlaceOrderRequest): Order = with(api) {
        http.post("$baseUrl/orders") { jsonBody(request) }.orThrow()
    }

    override suspend fun cancelOrder(id: Long): Order = with(api) {
        http.delete("$baseUrl/orders/$id").orThrow()
    }
}
