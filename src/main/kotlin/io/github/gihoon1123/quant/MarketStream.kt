package io.github.gihoon1123.quant

import jakarta.annotation.PreDestroy
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Component
class MarketStream(
    private val candles: CandleService,
    private val mapper: JsonMapper,
) {
    private val client = HttpClient.newHttpClient()
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val reconnect = Executors.newSingleThreadScheduledExecutor()

    @Volatile private var stopped = false
    private val enabled = System.getenv("MARKET_STREAM_ENABLED") == "true"
    private val base = System.getenv("BINANCE_FUTURES_WS_URL") ?: "wss://fstream.binance.com/ws"

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (enabled) (System.getenv("MONITORED_SYMBOLS") ?: "BTCUSDT").split(',').filter { it.isNotBlank() }.forEach(candles::subscribe)
    }

    @EventListener
    fun subscription(event: SubscriptionChanged) {
        if (!event.added) {
            sockets.remove(event.symbol)?.sendClose(WebSocket.NORMAL_CLOSURE, "unsubscribe")
        } else if (enabled && !sockets.containsKey(event.symbol)) {
            connect(event.symbol)
        }
    }

    private fun connect(symbol: String) {
        if (stopped || symbol !in candles.subscribed()) return
        val buffer = StringBuilder()
        client
            .newWebSocketBuilder()
            .buildAsync(
                URI.create("$base/${symbol.lowercase()}@kline_15m"),
                object : WebSocket.Listener {
                    override fun onOpen(webSocket: WebSocket) {
                        sockets[symbol] = webSocket
                        webSocket.request(1)
                    }

                    override fun onText(
                        webSocket: WebSocket,
                        data: CharSequence,
                        last: Boolean,
                    ): CompletionStage<*>? {
                        buffer.append(data)
                        if (last) {
                            runCatching {
                                val message = mapper.readTree(buffer.toString())
                                val candle = candles.parse(message)
                                if (candle != null) candles.accept(symbol, candle, message["k"]?.get("x")?.asBoolean() == true)
                            }
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
                        sockets.remove(symbol, webSocket)
                        retry(symbol)
                        return null
                    }

                    override fun onError(
                        webSocket: WebSocket,
                        error: Throwable,
                    ) {
                        sockets.remove(symbol, webSocket)
                        retry(symbol)
                    }
                },
            ).exceptionally {
                retry(symbol)
                null
            }
    }

    private fun retry(symbol: String) {
        if (!stopped && symbol in candles.subscribed()) reconnect.schedule({ connect(symbol) }, 5, TimeUnit.SECONDS)
    }

    @PreDestroy
    fun stop() {
        stopped = true
        reconnect.shutdownNow()
        sockets.values.forEach(WebSocket::abort)
        sockets.clear()
    }
}

data class SubscriptionChanged(
    val symbol: String,
    val added: Boolean,
)
