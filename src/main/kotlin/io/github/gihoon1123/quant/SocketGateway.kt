package io.github.gihoon1123.quant

import io.socket.engineio.server.EngineIoServer
import io.socket.engineio.server.EngineIoWebSocket
import io.socket.engineio.server.utils.ParseQS
import io.socket.socketio.server.SocketIoServer
import io.socket.socketio.server.SocketIoSocket
import jakarta.annotation.PreDestroy
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.json.JSONArray
import org.json.JSONObject
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.socket.BinaryMessage
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.WebSocketMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.handler.AbstractWebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

@Controller
class SocketGateway(
    private val candles: CandleService,
    private val mapper: JsonMapper,
    private val clock: Clock,
) : AbstractWebSocketHandler(),
    HandshakeInterceptor {
    private val engine = EngineIoServer()
    private val server = SocketIoServer(engine)
    private val clients = ConcurrentHashMap<String, Pair<SocketIoSocket, MutableSet<String>>>()

    init {
        server.namespace("/candle15m").on("connection") { args ->
            val socket = args[0] as SocketIoSocket
            val symbols = ConcurrentHashMap.newKeySet<String>()
            clients[socket.id] = socket to symbols
            send(
                socket,
                "connected",
                mapOf(
                    "message" to "15분봉 캔들 데이터 웹소켓에 연결되었습니다.",
                    "clientId" to socket.id,
                    "timestamp" to clock.instant(),
                ),
            )
            socket.on("subscribe") { input ->
                val requested = symbols(input.firstOrNull())
                requested.forEach { symbol ->
                    if (symbols.add(symbol)) {
                        candles.latestRecord(symbol)?.let {
                            send(
                                socket,
                                "candle.latest",
                                mapOf(
                                    "symbol" to symbol,
                                    "candle" to it,
                                    "timestamp" to clock.instant(),
                                ),
                            )
                        }
                    }
                }
                send(
                    socket,
                    "subscribed",
                    mapOf(
                        "symbols" to requested,
                        "totalSubscriptions" to symbols.size,
                        "timestamp" to clock.instant(),
                    ),
                )
            }
            socket.on("unsubscribe") { input ->
                val requested = symbols(input.firstOrNull())
                symbols.removeAll(requested.toSet())
                send(
                    socket,
                    "unsubscribed",
                    mapOf(
                        "symbols" to requested,
                        "remainingSubscriptions" to symbols.size,
                        "timestamp" to clock.instant(),
                    ),
                )
            }
            socket.on("getSubscriptions") { _ ->
                send(
                    socket,
                    "subscriptions",
                    mapOf(
                        "symbols" to symbols.sorted(),
                        "count" to symbols.size,
                        "timestamp" to clock.instant(),
                    ),
                )
            }
            socket.on("disconnect") { _ -> clients.remove(socket.id) }
        }
    }

    private fun symbols(input: Any?): List<String> =
        when (input) {
            is String -> listOf(input)
            is JSONArray -> (0 until input.length()).map { input.getString(it) }
            else -> emptyList()
        }

    private fun send(
        socket: SocketIoSocket,
        event: String,
        value: Any,
    ) = socket.send(event, JSONObject(mapper.writeValueAsString(value)))

    fun serverStatistics(): Map<String, Any> {
        val symbols =
            clients.values
                .flatMap { it.second }
                .groupingBy { it }
                .eachCount()
        return mapOf(
            "totalConnections" to clients.size,
            "subscriptionStats" to
                mapOf(
                    "totalClients" to clients.size,
                    "totalSubscriptions" to clients.values.sumOf { it.second.size },
                    "symbolSubscriptions" to symbols,
                ),
            "serviceStats" to candles.status(),
            "timestamp" to clock.instant(),
        )
    }

    fun broadcastServerStats() {
        val stats = serverStatistics()
        clients.values.forEach { send(it.first, "server.stats", stats) }
    }

    @EventListener
    fun event(event: MarketEvent) {
        val symbol = event.data["symbol"] as? String ?: return
        val name =
            when (event.name) {
                "candle.updated" -> "candle.update"
                "candle.15m.completed" -> "candle.completed"
                else -> return
            }
        val data =
            if (name ==
                "candle.update"
            ) {
                mapOf(
                    "symbol" to symbol,
                    "candle" to event.data["candleData"],
                    "isCompleted" to event.data["isCompleted"],
                    "timestamp" to clock.instant(),
                )
            } else {
                mapOf(
                    "symbol" to symbol,
                    "candle" to event.data["candle"],
                    "timestamp" to clock.instant(),
                )
            }
        clients.values.filter { symbol in it.second }.forEach { send(it.first, name, data) }
    }

    @RequestMapping("/socket.io/", headers = ["Connection!=Upgrade"])
    fun polling(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        engine.handleRequest(request, response)
    }

    override fun beforeHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        attributes: MutableMap<String, Any>,
    ): Boolean {
        attributes["query"] = request.uri.rawQuery ?: ""
        attributes["headers"] = request.headers.headerSet().associate { it.key to it.value.toList() }
        return true
    }

    override fun afterHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        exception: Exception?,
    ) = Unit

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val bridge =
            object : EngineIoWebSocket() {
                override fun getQuery(): Map<String, String> = ParseQS.decode(session.attributes["query"] as String)

                @Suppress("UNCHECKED_CAST")
                override fun getConnectionHeaders(): Map<String, List<String>> = session.attributes["headers"] as Map<String, List<String>>

                override fun write(message: String) {
                    synchronized(session) { session.sendMessage(TextMessage(message)) }
                }

                override fun write(message: ByteArray) {
                    synchronized(session) { session.sendMessage(BinaryMessage(message)) }
                }

                override fun close() {
                    session.close()
                }
            }
        session.attributes["bridge"] = bridge
        engine.handleWebSocket(bridge)
    }

    override fun handleMessage(
        session: WebSocketSession,
        message: WebSocketMessage<*>,
    ) {
        val payload =
            if (message is BinaryMessage) {
                ByteArray(
                    message.payload.remaining(),
                ).also { message.payload.get(it) }
            } else {
                message.payload
            }
        (session.attributes["bridge"] as EngineIoWebSocket).emit("message", payload)
    }

    override fun afterConnectionClosed(
        session: WebSocketSession,
        status: CloseStatus,
    ) {
        (session.attributes["bridge"] as? EngineIoWebSocket)?.emit("close")
    }

    override fun handleTransportError(
        session: WebSocketSession,
        exception: Throwable,
    ) {
        (session.attributes["bridge"] as? EngineIoWebSocket)?.emit("error", "write error", exception.message)
    }

    @PreDestroy
    fun stop() {
        engine.shutdown()
    }
}

@Configuration
@EnableWebSocket
class SocketConfiguration(
    private val gateway: SocketGateway,
) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(gateway, "/socket.io/").addInterceptors(gateway).setAllowedOrigins("*")
    }
}
