package trading.data.otel

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.extension.kotlin.asContextElement
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import kotlinx.coroutines.withContext

/**
 * Инициализирует OpenTelemetry SDK из стандартных env-переменных
 * (OTEL_EXPORTER_OTLP_ENDPOINT, OTEL_SERVICE_NAME). Без endpoint —
 * остаётся no-op GlobalOpenTelemetry.
 */
fun initTelemetry() {
    if (System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT").isNullOrBlank()) return
    AutoConfiguredOpenTelemetrySdk.builder()
        .setResultAsGlobal()
        .build()
}

private val headerGetter = object : TextMapGetter<Map<String, String>> {
    override fun keys(carrier: Map<String, String>): Iterable<String> = carrier.keys
    override fun get(carrier: Map<String, String>?, key: String): String? = carrier?.get(key)
}

/**
 * Серверный спан на каждый HTTP-запрос с извлечением W3C traceparent —
 * сквозной trace-id от Gateway до SQL-транзакций. Контекст пробрасывается
 * в корутины через asContextElement, поэтому Database.tx видит родителя.
 */
fun Application.installServerTracing() {
    val tracer = GlobalOpenTelemetry.getTracer("data-service")
    val propagator = GlobalOpenTelemetry.getPropagators().textMapPropagator

    intercept(ApplicationCallPipeline.Monitoring) {
        val headers = call.request.headers.entries().associate { it.key to it.value.first() }
        val parent = propagator.extract(Context.current(), headers, headerGetter)
        val span = tracer.spanBuilder("${call.request.httpMethod.value} ${call.request.path()}")
            .setParent(parent)
            .setSpanKind(SpanKind.SERVER)
            .startSpan()
        try {
            withContext(parent.with(span).asContextElement()) {
                proceed()
            }
            val status = call.response.status()?.value ?: 0
            span.setAttribute("http.response.status_code", status.toLong())
            if (status >= 500) span.setStatus(StatusCode.ERROR)
        } catch (e: Exception) {
            span.recordException(e)
            span.setStatus(StatusCode.ERROR)
            throw e
        } finally {
            span.end()
        }
    }
}
