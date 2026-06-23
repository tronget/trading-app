package trading.gateway.trading

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapSetter
import kotlinx.serialization.json.Json

private val traceSetter = TextMapSetter<MutableMap<String, String>> { carrier, key, value ->
    carrier?.put(key, value)
}

/**
 * HTTP-клиент к Data-сервису (#4). Прокидывает W3C traceparent, чтобы
 * trace-id шёл сквозь Gateway → Data-сервис.
 */
class DataServiceClient(private val baseUrl: String) {
    val json = Json { ignoreUnknownKeys = true }

    val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        expectSuccess = false
    }

    private fun traceHeaders(): Map<String, String> {
        val carrier = mutableMapOf<String, String>()
        GlobalOpenTelemetry.getPropagators().textMapPropagator
            .inject(Context.current(), carrier, traceSetter)
        return carrier
    }

    suspend fun get(path: String): HttpResponse =
        http.get("$baseUrl$path") {
            headers { traceHeaders().forEach { (k, v) -> append(k, v) } }
        }

    suspend fun postJson(path: String, body: String): HttpResponse =
        http.post("$baseUrl$path") {
            contentType(ContentType.Application.Json)
            setBody(body)
            headers { traceHeaders().forEach { (k, v) -> append(k, v) } }
        }

    suspend fun delete(path: String): HttpResponse =
        http.delete("$baseUrl$path") {
            headers { traceHeaders().forEach { (k, v) -> append(k, v) } }
        }
}

/** Тело и статус ответа data-сервиса как есть — Gateway не пере-сериализует. */
suspend fun HttpResponse.passthrough(): Pair<Int, String> = status.value to bodyAsText()
