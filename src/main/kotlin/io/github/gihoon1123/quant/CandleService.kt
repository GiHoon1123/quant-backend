package io.github.gihoon1123.quant

import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class MarketEvent(
    val name: String,
    val data: Map<String, Any?>,
    val channel: String = "global",
)

@Service
class CandleService(
    private val repository: CandleRepository,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    private val memory = ConcurrentHashMap<String, List<CandleData>>()
    private val records = ConcurrentHashMap<String, Map<String, Any?>>()
    private val latest = ConcurrentHashMap<String, CandleData>()
    private val ongoing = ConcurrentHashMap<String, CandleData>()
    private val subscriptions = ConcurrentHashMap.newKeySet<String>()
    private val connections = ConcurrentHashMap<String, Boolean>()
    private val maxMemory = System.getenv("MAX_MEMORY_CANDLES")?.toIntOrNull() ?: 20000

    fun parse(message: JsonNode): CandleData? =
        runCatching {
            val k = message["k"] ?: message
            val required = listOf("t", "T", "o", "h", "l", "c", "v", "q", "V", "Q")
            if (required.any { k[it] == null || k[it].isNull }) return null

            fun number(key: String) = k[key].asString().toDouble()
            val candle =
                CandleData(
                    number("t").toLong(),
                    number("T").toLong(),
                    number("o"),
                    number("h"),
                    number("l"),
                    number("c"),
                    number("v"),
                    number("q"),
                    k["n"]?.asInt() ?: 0,
                    number("V"),
                    number("Q"),
                )
            repository.validate("parsed", "FUTURES", candle)
            candle
        }.getOrNull()

    @Synchronized
    fun accept(
        symbol: String,
        candle: CandleData,
        completed: Boolean,
    ) {
        repository.validate(symbol, "FUTURES", candle)
        val previous = memory[symbol].orEmpty()
        val next = if (previous.lastOrNull()?.openTime == candle.openTime) previous.dropLast(1) + candle else previous + candle
        memory[symbol] = next.takeLast(maxMemory)
        latest[symbol] = candle
        connections[symbol] = true
        ongoing[symbol] = candle
        runCatching { repository.record(symbol, candle.openTime) }.getOrNull()?.let { records[symbol] = it }
        events.publishEvent(MarketEvent("candle.updated", mapOf("symbol" to symbol, "candleData" to candle, "isCompleted" to completed)))
        if (completed) {
            try {
                val id = repository.save(symbol, "FUTURES", candle)
                if (previous.none { it.openTime == candle.openTime }) {
                    events.publishEvent(
                        MarketEvent(
                            "candle.saved",
                            mapOf(
                                "symbol" to symbol,
                                "market" to "FUTURES",
                                "timeframe" to "15m",
                                "candleData" to candle,
                                "isNewCandle" to true,
                                "savedAt" to clock.instant(),
                                "candleId" to id,
                            ),
                        ),
                    )
                }
            } catch (error: Exception) {
                events.publishEvent(
                    MarketEvent(
                        "candle.save.failed",
                        mapOf(
                            "symbol" to symbol,
                            "market" to "FUTURES",
                            "error" to error.message,
                            "openTime" to candle.openTime,
                        ),
                    ),
                )
            }
            events.publishEvent(
                MarketEvent(
                    "candle.15m.completed",
                    mapOf(
                        "eventId" to UUID.randomUUID().toString(),
                        "service" to "Candle15MService",
                        "symbol" to symbol,
                        "market" to "FUTURES",
                        "timeframe" to "15m",
                        "candle" to candle,
                        "timestamp" to clock.instant(),
                    ),
                ),
            )
        }
    }

    fun preload(symbol: String) {
        val stored = repository.latest(symbol, limit = maxMemory)
        if (stored.isNotEmpty()) {
            memory[symbol] = stored
            latest[symbol] = stored.last()
            repository.record(symbol, stored.last().openTime)?.let { records[symbol] = it }
        }
    }

    fun testCandle(
        symbol: String,
        candle: CandleData,
    ): Map<String, Any> {
        val id = repository.save(symbol, "FUTURES", candle)
        val key = "${symbol}_FUTURES_15m"
        memory[key] = (memory[key].orEmpty() + candle).takeLast(maxMemory)
        events.publishEvent(
            MarketEvent(
                "candle.saved",
                mapOf(
                    "symbol" to symbol,
                    "market" to "FUTURES",
                    "timeframe" to "15m",
                    "candleData" to candle,
                    "isNewCandle" to true,
                    "savedAt" to clock.instant(),
                    "candleId" to id,
                ),
            ),
        )
        return (
            repository.record(symbol, candle.openTime).orEmpty() +
                mapOf(
                    "id" to id,
                    "symbol" to symbol,
                    "market" to "FUTURES",
                    "timeframe" to "15m",
                    "openTime" to java.time.Instant.ofEpochMilli(candle.openTime),
                    "closeTime" to java.time.Instant.ofEpochMilli(candle.closeTime),
                    "open" to candle.open,
                    "high" to candle.high,
                    "low" to candle.low,
                    "close" to candle.close,
                    "volume" to candle.volume,
                    "quoteVolume" to candle.quoteVolume,
                    "trades" to candle.trades,
                    "takerBuyBaseVolume" to candle.takerBuyBaseVolume,
                    "takerBuyQuoteVolume" to candle.takerBuyQuoteVolume,
                )
        ).filterValues { it != null }.mapValues { it.value!! }
    }

    fun trigger(symbol: String) {
        val candle = memory["${symbol}_FUTURES_15m"]?.lastOrNull() ?: return
        events.publishEvent(
            MarketEvent(
                "candle.completed",
                mapOf(
                    "eventId" to UUID.randomUUID().toString(),
                    "symbol" to symbol,
                    "service" to "Candle15MService",
                    "timestamp" to clock.instant(),
                    "market" to "FUTURES",
                    "timeframe" to "15m",
                    "candle" to candle,
                ),
            ),
        )
    }

    fun latestRecord(symbol: String): CandleData? =
        records[symbol]?.let { row ->
            fun number(key: String): Double = row.getValue(key).toString().toDouble()
            CandleData(
                (row.getValue("openTime") as java.time.Instant).toEpochMilli(),
                (row.getValue("closeTime") as java.time.Instant).toEpochMilli(),
                number("open"),
                number("high"),
                number("low"),
                number("close"),
                number("volume"),
                number("quoteVolume"),
                (row.getValue("trades") as Number).toInt(),
                number("takerBuyBaseVolume"),
                number("takerBuyQuoteVolume"),
            )
        }

    fun allLatestRecords(): Map<String, CandleData> =
        records.keys.mapNotNull { symbol -> latestRecord(symbol)?.let { symbol to it } }.toMap()

    fun latest(symbol: String): CandleData? = latest[symbol]

    fun allLatest(): Map<String, CandleData> = latest.toMap()

    fun ongoing(symbol: String): CandleData? = ongoing[symbol]

    fun history(
        symbol: String,
        limit: Int,
    ): List<CandleData> = repository.latest(symbol, limit = limit)

    fun subscribe(symbol: String) {
        subscriptions.add(symbol)
        events.publishEvent(SubscriptionChanged(symbol, true))
        connections[symbol] = false
    }

    fun unsubscribe(symbol: String) {
        subscriptions.remove(symbol)
        events.publishEvent(SubscriptionChanged(symbol, false))
        connections.remove(symbol)
    }

    fun subscribed(): Set<String> = subscriptions.toSet()

    fun status(): Map<String, Any> =
        mapOf(
            "subscribedSymbols" to subscriptions.sorted(),
            "connectionStatus" to emptyMap<String, Any>(),
            "cacheSize" to latest.size,
            "ongoingCandlesCount" to ongoing.size,
        )

    fun memory(symbol: String): List<CandleData> = memory[symbol].orEmpty()
}
