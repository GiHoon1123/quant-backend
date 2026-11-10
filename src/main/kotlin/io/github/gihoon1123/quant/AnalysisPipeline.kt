package io.github.gihoon1123.quant

import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.time.Clock

@Service
class AnalysisPipeline(
    private val repository: CandleRepository,
    private val strategies: Strategies,
    private val analysis: TechnicalAnalysis,
    private val indicators: Indicators,
    private val cache: Cache,
    private val exchangeRate: ExchangeRate,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @EventListener
    fun saved(event: MarketEvent) {
        if (event.name != "candle.saved" || event.data["isNewCandle"] != true) return
        val symbol = event.data["symbol"].toString()
        val timeframe = event.data["timeframe"].toString()
        try {
            val candles = runCatching { repository.latest(symbol, limit = 200) }.getOrDefault(emptyList())
            if (candles.size >=
                15
            ) {
                cache.set(
                    "atr:$symbol",
                    mapOf(
                        "symbol" to symbol,
                        "atr" to indicators.atr(candles, 14),
                        "timestamp" to clock.instant(),
                        "period" to 14,
                        "candlesUsed" to candles.size,
                    ),
                )
            }
            val all = repository.latest(symbol, limit = 20000)
            strategies.basic(symbol, timeframe, all)
            strategies.advanced(symbol, timeframe, all)
            val summary = runCatching { analysis.summary(symbol, timeframe) }.getOrNull()
            val result = if (summary != null) runCatching { analysis.analyze(symbol, timeframe) }.getOrNull() else null

            @Suppress("UNCHECKED_CAST")
            val nested = summary?.get("indicators") as? Map<String, Map<String, Any?>> ?: emptyMap()
            val mapped =
                linkedMapOf<String, Any?>(
                    "SMA5" to nested["priceVsMA"]?.get("ma5"),
                    "SMA20" to nested["priceVsMA"]?.get("ma20"),
                    "SMA50" to nested["priceVsMA"]?.get("ma50"),
                    "SMA200" to nested["priceVsMA"]?.get("ma200"),
                    "EMA12" to nested["priceVsMA"]?.get("ema12"),
                    "EMA26" to nested["priceVsMA"]?.get("ema26"),
                    "VWAP" to nested["priceVsMA"]?.get("vwap"),
                    "RSI" to nested["rsi"]?.get("value"),
                    "MACD" to (nested["macd"]?.get("interpretation") ?: "N/A"),
                    "Volume" to nested["volume"]?.get("ratio"),
                    "AvgVolume" to 1.0,
                    "VolumeRatio" to nested["volume"]?.get("ratio"),
                )
            if (summary == null ||
                result == null
            ) {
                mapped.clear()
                mapped.putAll(
                    mapOf(
                        "SMA5" to null,
                        "SMA10" to null,
                        "SMA20" to null,
                        "RSI" to null,
                        "MACD" to "N/A",
                        "Volume" to null,
                        "AvgVolume" to 1.0,
                        "VolumeRatio" to null,
                    ),
                )
            }
            listOf("SMA5", "SMA10", "SMA20", "RSI", "MACD", "Volume", "AvgVolume", "VolumeRatio").forEach { key ->
                val value = mapped[key]
                if (value == null || value == 0.0 || value == 0 || value == "") mapped[key] = "N/A"
            }
            mapped["exchangeRate"] = exchangeRate.rate()
            @Suppress("UNCHECKED_CAST")
            val results = result?.get("strategies") as? List<StrategyResult> ?: emptyList()
            val signal = result?.get("overallSignal") ?: "HOLD"
            events.publishEvent(
                MarketEvent(
                    "analysis.completed",
                    mapOf(
                        "symbol" to symbol,
                        "timeframe" to timeframe,
                        "candleData" to event.data["candleData"],
                        "analyzedAt" to clock.instant(),
                        "analysisResult" to
                            mapOf(
                                "signal" to signal,
                                "overallSignal" to signal,
                                "currentPrice" to (summary?.get("currentPrice") ?: 0),
                                "timestamp" to (summary?.get("timestamp") ?: clock.millis()),
                                "indicators" to mapped,
                                "strategies" to
                                    results.map {
                                        mapOf(
                                            "name" to it.strategy,
                                            "signal" to it.signal,
                                            "type" to it.strategy,
                                            "details" to it.details,
                                        )
                                    },
                            ),
                    ),
                    channel = "technical",
                ),
            )
        } catch (error: Exception) {
            events.publishEvent(
                MarketEvent(
                    "analysis.error",
                    mapOf(
                        "symbol" to symbol,
                        "error" to error.message,
                        "timestamp" to clock.instant(),
                    ),
                    channel = "technical",
                ),
            )
        }
    }
}
