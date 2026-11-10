package io.github.gihoon1123.quant

import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import java.time.Clock

@Service
class AutoTrading(
    private val futures: Futures,
    private val client: BinanceFutures,
    private val cache: Cache,
    private val environment: Environment,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    private fun config(
        key: String,
        fallback: Double,
    ) = environment.getProperty(key)?.toDoubleOrNull()?.takeIf { it != 0.0 } ?: fallback

    private fun number(
        map: Map<String, Any?>,
        keys: List<String>,
        fallback: Double,
    ): Double = keys.firstNotNullOfOrNull { key -> (map[key] as? Number)?.toDouble()?.takeIf { it != 0.0 && !it.isNaN() } } ?: fallback

    fun conditions(
        analysis: Map<String, Any?>,
        long: Boolean,
        switching: Boolean = false,
    ): Boolean {
        @Suppress("UNCHECKED_CAST")
        val indicators = analysis["indicators"] as? Map<String, Any?> ?: emptyMap()
        val price = number(analysis, listOf("currentPrice"), if (switching) Double.NaN else 0.0)
        val first = number(indicators, if (long) listOf("support1", "Support1") else listOf("resistance1", "Resistance1"), 0.0)
        val second =
            number(
                indicators,
                if (long) {
                    if (switching) listOf("support1", "Support2") else listOf("support2", "Support2")
                } else {
                    listOf("resistance2", "Resistance2")
                },
                0.0,
            )
        val rsi = number(indicators, listOf("RSI", "rsi"), 50.0)
        val volume = number(indicators, listOf("VolumeRatio", "volumeRatio"), 1.0)
        val touch = if (long) price <= first * 1.005 || price <= second * 1.005 else price >= first * 0.995 || price >= second * 0.995
        val count = listOf(touch, if (long) rsi < 30 else rsi > 70, volume > 1.5).count { it }
        return count >= if (switching || long) 2 else 1
    }

    fun positionSize(price: Double): Double =
        try {
            val balance = futures.balances().find { it["asset"] == "USDT" }
            val available = (balance?.get("availableBalance") as? Number)?.toDouble() ?: 0.0
            if (available <=
                0
            ) {
                0.0
            } else {
                available * config("AUTO_TRADING_POSITION_SIZE_PERCENT", 100.0) / 100 * config("AUTO_TRADING_LEVERAGE", 3.0) / price
            }
        } catch (error: Exception) {
            0.001
        }

    fun exit(
        symbol: String,
        price: Double,
        long: Boolean,
        stop: Boolean,
    ): Double {
        @Suppress("UNCHECKED_CAST")
        val atr = (cache.get("atr:$symbol") as? Map<String, Any?>)?.get("atr") as? Number
        val key = if (stop) "stop_loss" else "take_profit"
        val multiplier =
            (cache.get("config:atr_${key}_multiplier") as? Number)?.toDouble()?.takeIf { it != 0.0 }
                ?: config("ATR_${key.uppercase()}_MULTIPLIER", if (stop) 2.8 else 1.3)
        val distance =
            if (atr != null &&
                atr.toDouble() != 0.0
            ) {
                atr.toDouble() * multiplier
            } else {
                price *
                    config(
                        if (stop) "FUTURES_DEFAULT_STOP_LOSS_PERCENT" else "FUTURES_DEFAULT_TAKE_PROFIT_PERCENT",
                        if (stop) 0.02 else 0.04,
                    )
            }
        return price + if (long == stop) -distance else distance
    }

    @EventListener
    fun event(event: MarketEvent) {
        if (event.name == "analysis.completed") {
            runCatching {
                val symbol = event.data["symbol"].toString()

                @Suppress("UNCHECKED_CAST")
                val analysis = event.data["analysisResult"] as? Map<String, Any?> ?: return
                val raw = client.positions(symbol)
                val current =
                    (0 until raw.size()).map { raw[it] }.firstOrNull {
                        it["symbol"].asString() == symbol &&
                            it["positionAmt"].asString().toDouble() != 0.0
                    }
                val overall = analysis["overallSignal"]
                val long: Boolean =
                    if (current == null) {
                        when (overall) {
                            "STRONG_BUY" -> true
                            "STRONG_SELL", "SELL" -> false
                            else -> return
                        }
                    } else {
                        // Source getActivePositions returns raw DTOs with no side field; retain that boundary.
                        val side = current["side"]?.asString()
                        when {
                            side == "LONG" && overall in setOf("STRONG_SELL", "SELL") -> false
                            side == "SHORT" &&
                                overall in setOf("STRONG_BUY", "BUY") -> true
                            else -> return
                        }
                    }
                if (!conditions(analysis, long, current != null)) return
                if (current != null) futures.closeAll(symbol, if (long) "숏→롱 스위칭" else "롱→숏 스위칭")
                emit(symbol, analysis, long)
            }
        }
        if (event.name == "trading.signal") {
            runCatching {
                val symbol = event.data["symbol"].toString()
                val raw = client.positions(symbol)
                val exists = (0 until raw.size()).any { raw[it]["positionAmt"].asString().toDouble() != 0.0 }
                when (val signal = event.data["signal"]) {
                    "LONG", "SHORT" ->
                        if (!exists) {
                            futures.open(
                                symbol,
                                signal.toString(),
                                (event.data["quantity"] as Number).toDouble(),
                                config("AUTO_TRADING_LEVERAGE", 3.0).toInt(),
                            )
                        }
                    "CLOSE" -> if (exists) futures.closeAll(symbol, "자동 매매 청산 신호")
                }
            }
        }
    }

    private fun emit(
        symbol: String,
        analysis: Map<String, Any?>,
        long: Boolean,
    ) {
        val price = (analysis["currentPrice"] as? Number)?.toDouble() ?: Double.NaN
        val quantity = positionSize(price)
        if (quantity <= 0) return
        @Suppress("UNCHECKED_CAST")
        val strategies = analysis["strategies"] as? List<Map<String, Any?>> ?: emptyList()

        @Suppress("UNCHECKED_CAST")
        val details = strategies.firstOrNull { it["type"] == "PIVOT_REVERSAL" }?.get("details") as? Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val indicators = details?.get("indicators") as? Map<String, Any?> ?: emptyMap()
        val conditions = (details?.get("conditions") as? List<*>) ?: listOf(if (long) "롱 진입 조건 만족" else "숏 진입 조건 만족")
        val keys =
            listOf("pivotPoint", if (long) "support1" else "resistance1") + (if (long) listOf("support2") else listOf("resistance2")) +
                listOf("rsi", "volumeRatio")
        events.publishEvent(
            MarketEvent(
                "trading.signal",
                mapOf(
                    "eventId" to "auto_trading_${clock.millis()}",
                    "timestamp" to clock.instant(),
                    "symbol" to symbol,
                    "signal" to if (long) "LONG" else "SHORT",
                    "strategy" to "PIVOT_REVERSAL",
                    "entryPrice" to price,
                    "stopLoss" to exit(symbol, price, long, true),
                    "takeProfit" to exit(symbol, price, long, false),
                    "quantity" to quantity,
                    "source" to "AutoTradingService",
                    "metadata" to
                        mapOf(
                            "analysis" to analysis,
                            "conditions" to conditions.joinToString(", "),
                            "leverage" to config("AUTO_TRADING_LEVERAGE", 3.0),
                            "pivotDetails" to indicators.filterKeys { it in keys },
                        ),
                ),
            ),
        )
    }
}
