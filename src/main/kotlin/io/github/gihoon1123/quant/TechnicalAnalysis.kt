package io.github.gihoon1123.quant

import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

@Service
class TechnicalAnalysis(
    private val repository: CandleRepository,
    private val indicators: Indicators,
    private val strategies: Strategies,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    val defaultSymbols =
        listOf(
            "BTCUSDT",
            "ETHUSDT",
            "ADAUSDT",
            "DOTUSDT",
            "LINKUSDT",
            "SOLUSDT",
            "MATICUSDT",
            "AVAXUSDT",
            "ATOMUSDT",
            "NEARUSDT",
        )

    fun analyze(
        symbol: String,
        timeframe: String = "15m",
    ): Map<String, Any> {
        val candles = repository.latest(symbol, limit = 20000)
        val results = strategies.basic(symbol, timeframe, candles) + strategies.advanced(symbol, timeframe, candles)
        val buys = results.count { it.signal in setOf("BUY", "STRONG_BUY") }
        val sells = results.count { it.signal in setOf("SELL", "STRONG_SELL") }
        val signal =
            when {
                buys > sells -> "BUY"
                sells > buys -> "SELL"
                else -> "NEUTRAL"
            }
        val result =
            mapOf(
                "symbol" to symbol,
                "timestamp" to clock.millis(),
                "overallSignal" to signal,
                "strategies" to results,
                "consensus" to maxOf(buys, sells).toDouble() / results.size,
                "timeframeSummary" to mapOf(timeframe to mapOf("signal" to signal, "strategyCount" to results.size)),
            )
        events.publishEvent(
            MarketEvent(
                "technical.analysis.completed",
                mapOf(
                    "eventId" to UUID.randomUUID().toString(),
                    "service" to "TechnicalAnalysisService",
                    "symbol" to symbol,
                    "signal" to signal,
                    "analyzedAt" to clock.instant(),
                    "timestamp" to clock.instant(),
                ),
            ),
        )
        return result
    }

    fun screen(
        symbols: List<String> = defaultSymbols,
        timeframe: String = "15m",
    ): Map<String, Map<String, Any>> =
        symbols
            .mapNotNull { symbol ->
                runCatching {
                    symbol to
                        analyze(symbol, timeframe)
                }.getOrNull()
            }.toMap()

    fun scan(
        name: String,
        timeframe: String,
        symbols: List<String> = defaultSymbols,
    ): List<StrategyResult> =
        symbols.mapNotNull { symbol ->
            runCatching {
                val candles = repository.latest(symbol, limit = 20000)
                val results = strategies.basic(symbol, timeframe, candles) + strategies.advanced(symbol, timeframe, candles)
                results.find { it.strategy == name } ?: results.first()
            }.getOrNull()
        }

    fun advanced(
        symbol: String,
        timeframe: String,
    ): StrategyResult = strategies.advanced(symbol, timeframe, repository.latest(symbol, limit = 20000)).first()

    fun summary(
        symbol: String,
        timeframe: String,
    ): Map<String, Any> {
        val candles = repository.latest(symbol, limit = 20000)
        val price = candles.last().close
        val sma = listOf(5, 20, 50, 200).associateWith { indicators.sma(candles, it).last().value }
        val rsi = indicators.rsi(candles).last()
        val macd = indicators.macd(candles).last()
        val bands = indicators.bands(candles).last()
        val volume = indicators.volume(candles).last()
        return mapOf(
            "symbol" to symbol,
            "timeframe" to timeframe,
            "timestamp" to clock.millis(),
            "currentPrice" to price,
            "indicators" to
                mapOf(
                    "priceVsMA" to
                        mapOf(
                            "above5MA" to (price > sma.getValue(5)),
                            "above20MA" to (price > sma.getValue(20)),
                            "above50MA" to (price > sma.getValue(50)),
                            "above200MA" to (price > sma.getValue(200)),
                            "ma5" to sma.getValue(5),
                            "ma20" to sma.getValue(20),
                            "ma50" to sma.getValue(50),
                            "ma200" to sma.getValue(200),
                            "ema12" to indicators.ema(candles, 12).last().value,
                            "ema26" to indicators.ema(candles, 26).last().value,
                            "vwap" to indicators.vwap(candles).last().value,
                        ),
                    "rsi" to
                        mapOf(
                            "value" to rsi.value,
                            "isOversold" to rsi.isOversold,
                            "isOverbought" to rsi.isOverbought,
                            "interpretation" to
                                if (rsi.isOversold) {
                                    "과매도"
                                } else if (rsi.isOverbought) {
                                    "과매수"
                                } else {
                                    "중립"
                                },
                        ),
                    "macd" to
                        mapOf(
                            "line" to macd.macdLine,
                            "signal" to macd.signalLine,
                            "histogram" to macd.histogram,
                            "isGoldenCross" to macd.isGoldenCross,
                            "interpretation" to if (macd.isGoldenCross) "강세" else "약세",
                        ),
                    "bollinger" to
                        mapOf(
                            "upper" to bands.upper,
                            "middle" to bands.middle,
                            "lower" to bands.lower,
                            "percentB" to bands.percentB,
                            "position" to
                                if (bands.percentB > 0.8) {
                                    "상단근접"
                                } else if (bands.percentB < 0.2) {
                                    "하단근접"
                                } else {
                                    "중간위치"
                                },
                        ),
                    "volume" to
                        mapOf(
                            "ratio" to volume.volumeRatio,
                            "isSurge" to volume.isVolumeSurge,
                            "obv" to volume.obv,
                            "interpretation" to
                                if (volume.isVolumeSurge) {
                                    "급증"
                                } else if (volume.volumeRatio > 1.5) {
                                    "증가"
                                } else {
                                    "평균"
                                },
                        ),
                ),
        )
    }
}
