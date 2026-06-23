package com.trading.app.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Ошибка API с сообщением от сервера (поле error в JSON-ответе). */
class ApiException(val status: Int, message: String) : Exception(message)

/**
 * HTTP-клиент к Gateway: Bearer-токен на каждый запрос, авто-refresh при 401
 * и повтор исходного запроса с новым access-токеном.
 */
class ApiClient(val baseUrl: String, private val tokens: TokenStore) {
    val json = Json { ignoreUnknownKeys = true }

    val http = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(this@ApiClient.json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
        install(WebSockets)
        expectSuccess = false
    }

    init {
        http.plugin(HttpSend).intercept { request ->
            val (access, _) = tokens.current()
            if (access != null && !request.headers.contains(HttpHeaders.Authorization)) {
                request.header(HttpHeaders.Authorization, "Bearer $access")
            }
            val call = execute(request)
            if (call.response.status != HttpStatusCode.Unauthorized) {
                return@intercept call
            }

            // access протух — пробуем refresh и повторяем запрос один раз
            val refreshed = tryRefresh() ?: return@intercept call
            request.headers.remove(HttpHeaders.Authorization)
            request.header(HttpHeaders.Authorization, "Bearer $refreshed")
            execute(request)
        }
    }

    private suspend fun tryRefresh(): String? {
        val (_, refresh) = tokens.current()
        if (refresh == null) return null
        val response = http.post("$baseUrl/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(refresh))
        }
        if (response.status != HttpStatusCode.OK) {
            tokens.clear()
            return null
        }
        val token = response.body<AccessTokenResponse>().accessToken
        tokens.save(token)
        return token
    }

    /** Разбирает ответ: 2xx → тело, иначе ApiException с серверным сообщением. */
    suspend inline fun <reified T> HttpResponse.orThrow(): T {
        if (status.value in 200..299) return body()
        val message = runCatching {
            json.decodeFromString<ErrorResponse>(bodyAsText()).error
        }.getOrElse { "HTTP ${status.value}" }
        throw ApiException(status.value, message)
    }
}

fun HttpRequestBuilder.jsonBody(body: Any) {
    contentType(ContentType.Application.Json)
    setBody(body)
}
