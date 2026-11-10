package io.github.gihoon1123.quant

import jakarta.annotation.PreDestroy
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Clock
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Component
class PositionStreams(
    private val environment: Environment,
    private val clock: Clock,
    private val mapper: JsonMapper,
) {
    private data class Subscription(
        var references: Int = 0,
        var lastTick: Long = 0,
        var price: Double? = null,
    )

    private val subscriptions = ConcurrentHashMap<String, Subscription>()
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val client = HttpClient.newHttpClient()

    @Volatile private var stopped = false

    @EventListener
    fun event(event: MarketEvent) {
        when (event.name) {
            "futures.position.opened" -> open(event.data["symbol"].toString())
            "futures.position.closed" -> close(event.data["symbol"].toString())
        }
    }

    @Synchronized
    fun open(symbol: String) {
        val state = subscriptions.computeIfAbsent(symbol) { Subscription() }
        state.references++
        if (state.references == 1 && environment.getProperty("MARKET_STREAM_ENABLED", Boolean::class.java, false)) {
            listOf("trade", "kline_1m", "kline_5m").forEach { connect(symbol, it) }
        }
    }

    @Synchronized
    fun close(symbol: String) {
        val state = subscriptions[symbol] ?: return
        state.references = maxOf(0, state.references - 1)
        if (state.references > 0) return
        subscriptions.remove(symbol)
        listOf("trade", "kline_1m", "kline_5m").forEach { stream ->
            sockets.remove("$symbol:$stream")?.sendClose(WebSocket.NORMAL_CLOSURE, "unsubscribe")
        }
    }

    fun references(symbol: String): Int = subscriptions[symbol]?.references ?: 0

    fun tick(
        symbol: String,
        message: JsonNode,
    ): Double? {
        val state = subscriptions[symbol] ?: return null
        synchronized(state) {
            val now = clock.millis()
            if (state.lastTick != 0L && now - state.lastTick < 300) return null
            state.lastTick = now
            val data = message["data"] ?: message
            val price = (data["p"] ?: data["price"])?.asString()?.toDoubleOrNull()?.takeIf { it.isFinite() }
            state.price = price
            return price
        }
    }

    private fun connect(
        symbol: String,
        stream: String,
    ) {
        if (stopped || !subscriptions.containsKey(symbol)) return
        val key = "$symbol:$stream"
        val base = environment.getProperty("BINANCE_FUTURES_WS_URL") ?: "wss://fstream.binance.com/ws"
        val buffer = StringBuilder()
        client
            .newWebSocketBuilder()
            .buildAsync(
                URI.create("$base/${symbol.lowercase()}@$stream"),
                object : WebSocket.Listener {
                    override fun onOpen(webSocket: WebSocket) {
                        if (!subscriptions.containsKey(symbol) || stopped) {
                            webSocket.abort()
                            return
                        }
                        sockets[key] = webSocket
                        webSocket.request(1)
                    }

                    override fun onText(
                        webSocket: WebSocket,
                        data: CharSequence,
                        last: Boolean,
                    ): CompletionStage<*>? {
                        buffer.append(data)
                        if (last) {
                            if (stream == "trade") runCatching { tick(symbol, mapper.readTree(buffer.toString())) }
                            // The original 1m/5m callbacks only log and do not write or publish events.
                            buffer.setLength(0)
                        }
                        webSocket.request(1)
                        return null
                    }

                    override fun onClose(
                        webSocket: WebSocket,
                        statusCode: Int,
                        reason: String,
                    ): CompletionStage<*>? {
                        sockets.remove(key, webSocket)
                        retry(symbol, stream)
                        return null
                    }

                    override fun onError(
                        webSocket: WebSocket,
                        error: Throwable,
                    ) {
                        sockets.remove(key, webSocket)
                        retry(symbol, stream)
                    }
                },
            ).exceptionally {
                retry(symbol, stream)
                null
            }
    }

    private fun retry(
        symbol: String,
        stream: String,
    ) {
        if (!stopped && subscriptions.containsKey(symbol)) scheduler.schedule({ connect(symbol, stream) }, 5, TimeUnit.SECONDS)
    }

    @PreDestroy
    fun shutdown() {
        stopped = true
        scheduler.shutdownNow()
        sockets.values.forEach(WebSocket::abort)
        sockets.clear()
        subscriptions.clear()
    }
}
