package io.github.gihoon1123.quant

import org.springframework.stereotype.Service
import java.time.Clock
import java.util.Locale
import kotlin.math.abs

data class StrategyDetails(
    val indicators: Map<String, Any>,
    val conditions: List<String>,
    val notes: String,
)

data class StrategyResult(
    val strategy: String,
    val symbol: String,
    val timeframe: String,
    val signal: String,
    val timestamp: Long,
    val details: StrategyDetails,
)

@Service
class Strategies(
    private val indicators: Indicators,
    private val clock: Clock,
) {
    private fun fixed(
        value: Double,
        precision: Int = 1,
    ): String = String.format(Locale.ROOT, "%.${precision}f", value)

    private fun result(
        name: String,
        symbol: String,
        timeframe: String,
        signal: String,
        values: Map<String, Any>,
        conditions: List<String>,
        notes: String,
    ) = StrategyResult(name, symbol, timeframe, signal, clock.millis(), StrategyDetails(values, conditions, notes))

    fun basic(
        symbol: String,
        timeframe: String,
        candles: List<CandleData>,
    ): List<StrategyResult> =
        listOf(
            { movingAverage(symbol, timeframe, candles.takeLast(100)) },
            { bollinger(symbol, timeframe, candles.takeLast(50)) },
            { macd(symbol, timeframe, candles.takeLast(100)) },
            { pivot(symbol, timeframe, candles.takeLast(50)) },
            { triple(symbol, timeframe, candles.takeLast(100)) },
        ).mapNotNull { runCatching(it).getOrNull() }

    fun movingAverage(
        symbol: String,
        timeframe: String,
        candles: List<CandleData>,
    ): StrategyResult {
        val sma20 = indicators.sma(candles, 20)
        val sma50 = indicators.sma(candles, 50)
        val current20 = sma20.last().value
        val current50 = sma50.last().value
        val volumeRatio = candles.last().volume / (candles.takeLast(20).sumOf { it.volume } / 20)
        val conditions = mutableListOf<String>()
        val signal =
            when {
                sma20[sma20.size - 2].value < sma50[sma50.size - 2].value && current20 > current50 -> {
                    conditions += "골든크로스 발생 - SMA20이 SMA50 상향 돌파"
                    conditions +=
                        if (volumeRatio > 1.5) "거래량 급증 확인 - 평균 대비 ${fixed(volumeRatio)}배" else "거래량 정상 - 평균 대비 ${fixed(volumeRatio)}배"
                    "STRONG_BUY"
                }
                sma20[sma20.size - 2].value > sma50[sma50.size - 2].value && current20 < current50 -> {
                    conditions += "데드크로스 발생 - SMA20이 SMA50 하향 돌파"
                    conditions +=
                        if (volumeRatio > 1.5) "거래량 급증 확인 - 평균 대비 ${fixed(volumeRatio)}배" else "거래량 정상 - 평균 대비 ${fixed(volumeRatio)}배"
                    "STRONG_SELL"
                }
                current20 > current50 -> {
                    conditions += "상승 트렌드 유지 - SMA20 > SMA50"
                    "WEAK_BUY"
                }
                else -> {
                    conditions += "하락 트렌드 유지 - SMA20 < SMA50"
                    "WEAK_SELL"
                }
            }
        return result(
            "MA_CROSSOVER",
            symbol,
            timeframe,
            signal,
            mapOf(
                "sma20" to current20,
                "sma50" to current50,
                "crossoverStrength" to abs(current20 - current50) / current50 * 100,
                "volumeRatio" to volumeRatio,
            ),
            conditions,
            "이동평균 크로스오버 전략 - 표준 20/50 크로스오버",
        )
    }

    fun bollinger(
        symbol: String,
        timeframe: String,
        candles: List<CandleData>,
    ): StrategyResult {
        val band = indicators.bands(candles).last()
        val rsi = indicators.rsi(candles).last()
        val current = candles.last().close
        val conditions = mutableListOf<String>()
        val signal =
            when {
                current >= band.upper && rsi.value > 70 -> {
                    conditions +=
                        listOf("볼린저 밴드 상단 터치", "RSI 과매수 확인: ${fixed(rsi.value)}", "하락 반전 신호 - 과매수 구간에서 매도")
                    "STRONG_SELL"
                }
                current <= band.lower && rsi.value < 30 -> {
                    conditions +=
                        listOf("볼린저 밴드 하단 터치", "RSI 과매도 확인: ${fixed(rsi.value)}", "상승 반전 신호 - 과매도 구간에서 매수")
                    "STRONG_BUY"
                }
                band.percentB > 0.8 -> {
                    conditions += "밴드 상단 근접 - 매도 고려"
                    "WEAK_SELL"
                }
                band.percentB < 0.2 -> {
                    conditions += "밴드 하단 근접 - 매수 고려"
                    "WEAK_BUY"
                }
                else -> {
                    conditions += "밴드 중간 위치 - 중립"
                    "NEUTRAL"
                }
            }
        return result(
            "BOLLINGER_REVERSAL",
            symbol,
            timeframe,
            signal,
            mapOf(
                "upper" to band.upper,
                "middle" to band.middle,
                "lower" to band.lower,
                "percentB" to band.percentB,
                "rsi" to rsi.value,
                "bandwidth" to band.bandwidth,
            ),
            conditions,
            "볼린저 밴드 반전 전략 - 표준 20/2 설정",
        )
    }

    fun macd(
        symbol: String,
        timeframe: String,
        candles: List<CandleData>,
    ): StrategyResult {
        val values = indicators.macd(candles)
        val current = values.last()
        val previous = values[values.size - 2]
        val conditions = mutableListOf<String>()
        val signal =
            when {
                previous.macdLine < previous.signalLine && current.macdLine > current.signalLine -> {
                    conditions += "MACD 골든크로스 발생"
                    conditions += if (current.macdLine > 0) "0선 위에서 골든크로스 - 강한 상승 신호" else "0선 아래에서 골든크로스 - 약한 상승 신호"
                    if (current.histogram > previous.histogram) conditions += "히스토그램 증가 - 모멘텀 강화"
                    if (current.macdLine > 0) "STRONG_BUY" else "BUY"
                }
                previous.macdLine > previous.signalLine && current.macdLine < current.signalLine -> {
                    conditions += "MACD 데드크로스 발생"
                    conditions += if (current.macdLine < 0) "0선 아래에서 데드크로스 - 강한 하락 신호" else "0선 위에서 데드크로스 - 약한 하락 신호"
                    if (current.histogram < previous.histogram) conditions += "히스토그램 감소 - 모멘텀 약화"
                    if (current.macdLine < 0) "STRONG_SELL" else "SELL"
                }
                current.isGoldenCross -> {
                    conditions += "MACD 상승 트렌드 유지"
                    "WEAK_BUY"
                }
                else -> {
                    conditions += "MACD 하락 트렌드 유지"
                    "WEAK_SELL"
                }
            }
        return result(
            "MACD_CROSSOVER",
            symbol,
            timeframe,
            signal,
            mapOf(
                "macdLine" to current.macdLine,
                "signalLine" to current.signalLine,
                "histogram" to current.histogram,
                "isGoldenCross" to if (current.isGoldenCross) 1 else 0,
                "isDeadCross" to if (current.isDeadCross) 1 else 0,
            ),
            conditions,
            "MACD 크로스오버 전략 - 표준 12/26/9 설정",
        )
    }

    fun pivot(
        symbol: String,
        timeframe: String,
        candles: List<CandleData>,
    ): StrategyResult {
        val previous = candles[candles.size - 2]
        val pp = (previous.high + previous.low + previous.close) / 3
        val r1 = 2 * pp - previous.low
        val r2 = pp + previous.high - previous.low
        val r3 = previous.high + 2 * (pp - previous.low)
        val s1 = 2 * pp - previous.high
        val s2 = pp - (previous.high - previous.low)
        val s3 = previous.low - 2 * (previous.high - pp)
        val rsi = indicators.rsi(candles).last().value
        val price = candles.last().close
        val volume = candles.last().volume / (candles.takeLast(20).sumOf { it.volume } / 20)
        val conditions = mutableListOf<String>()
        val signal =
            when {
                (price <= s1 * 1.005 || price <= s2 * 1.005) && rsi < 30 -> {
                    conditions +=
                        listOf(
                            "피벗 지지선 터치",
                            "RSI 과매도 확인: ${fixed(rsi)}",
                            if (volume >
                                1.5
                            ) {
                                "거래량 급증 - 평균 대비 ${fixed(volume)}배"
                            } else {
                                "거래량 정상 - 평균 대비 ${fixed(volume)}배"
                            },
                        )
                    if (volume > 1.5) "STRONG_BUY" else "BUY"
                }
                (price >= r1 * 0.995 || price >= r2 * 0.995) && rsi > 70 -> {
                    conditions +=
                        listOf(
                            "피벗 저항선 터치",
                            "RSI 과매수 확인: ${fixed(rsi)}",
                            if (volume >
                                1.5
                            ) {
                                "거래량 급증 - 평균 대비 ${fixed(volume)}배"
                            } else {
                                "거래량 정상 - 평균 대비 ${fixed(volume)}배"
                            },
                        )
                    if (volume > 1.5) "STRONG_SELL" else "SELL"
                }
                price > pp && price < r1 -> {
                    conditions += "피벗 중간 레벨 - 상승 모멘텀"
                    "WEAK_BUY"
                }
                price < pp && price > s1 -> {
                    conditions += "피벗 중간 레벨 - 하락 모멘텀"
                    "WEAK_SELL"
                }
                else -> {
                    conditions += "피벗 중립 구간"
                    "NEUTRAL"
                }
            }
        return result(
            "PIVOT_REVERSAL",
            symbol,
            timeframe,
            signal,
            mapOf(
                "pivotPoint" to pp,
                "resistance1" to r1,
                "resistance2" to r2,
                "resistance3" to r3,
                "support1" to s1,
                "support2" to s2,
                "support3" to s3,
                "currentPrice" to price,
                "rsi" to rsi,
                "volumeRatio" to volume,
            ),
            conditions,
            "피벗 반전 전략 - 표준 피벗 포인트 계산",
        )
    }

    fun triple(
        symbol: String,
        timeframe: String,
        candles: List<CandleData>,
    ): StrategyResult {
        val sma = indicators.sma(candles, 20).last().value
        val rsi = indicators.rsi(candles).last().value
        val volume = indicators.volume(candles).last().volumeRatio
        val price = candles.last().close
        val buy = listOf(price > sma, rsi in 30.0..70.0, volume >= 1.2)
        val sell = listOf(price < sma, rsi >= 70, volume <= 0.8)
        val conditions = mutableListOf<String>()
        val signal =
            when {
                buy.all { it } -> {
                    conditions +=
                        listOf("가격이 20일선 위 위치", "RSI 건전 구간: ${fixed(rsi)}", "거래량 증가: 평균 대비 ${fixed(volume)}배", "삼중 확인 매수 신호 - 높은 신뢰도")
                    "STRONG_BUY"
                }
                sell.all { it } -> {
                    conditions +=
                        listOf("가격이 20일선 아래 위치", "RSI 과매수: ${fixed(rsi)}", "거래량 감소: 평균 대비 ${fixed(volume)}배", "삼중 확인 매도 신호 - 높은 신뢰도")
                    "STRONG_SELL"
                }
                buy.count { it } >= 2 -> {
                    conditions += "부분 확인 매수 신호 (${buy.count { it }}/3 조건 충족)"
                    "BUY"
                }
                sell.count { it } >= 2 -> {
                    conditions += "부분 확인 매도 신호 (${sell.count { it }}/3 조건 충족)"
                    "SELL"
                }
                else -> {
                    conditions += "삼중 확인 조건 미충족 - 중립"
                    "NEUTRAL"
                }
            }
        return result(
            "TRIPLE_CONFIRMATION",
            symbol,
            timeframe,
            signal,
            mapOf(
                "currentPrice" to price,
                "sma20" to sma,
                "rsi" to rsi,
                "volumeRatio" to volume,
            ),
            conditions,
            "삼중 확인 전략 - 이동평균 + RSI + 거래량 동시 확인",
        )
    }

    fun advanced(
        symbol: String,
        timeframe: String,
        candles: List<CandleData>,
    ): List<StrategyResult> {
        val results = mutableListOf<StrategyResult>()
        // These constants are original source behavior, not newly implemented pattern detection.
        results +=
            result(
                "SMART_MONEY_FLOW",
                symbol,
                timeframe,
                "BUY",
                mapOf(
                    "institutionalFlow" to 0.8,
                    "volumeProfile" to 0.75,
                    "orderFlow" to 0.6,
                ),
                listOf("기관 자금 유입 감지 - 대량 매집 패턴 확인", "지지선에서 대량 거래 확인 - 바닥 다지기 완료"),
                "스마트 머니 플로우 분석 - 기관투자자 자금 흐름 추적",
            )
        runCatching { multiTimeframe(symbol, candles.takeLast(100)) }.getOrNull()?.let(results::add)
        results +=
            result(
                "PATTERN_RECOGNITION",
                symbol,
                timeframe,
                "NEUTRAL",
                mapOf("doubleBottom" to 0, "headAndShoulders" to 0, "triangle" to 0),
                emptyList(),
                "차트 패턴 인식 전략",
            )
        results +=
            result(
                "ELLIOTT_WAVE",
                symbol,
                timeframe,
                "NEUTRAL",
                mapOf("currentWave" to 3, "waveCompletion" to 0.6, "trendDirection" to 1),
                emptyList(),
                "엘리어트 웨이브 분석",
            )
        runCatching { dayTrading(symbol, timeframe, candles.takeLast(100)) }.getOrNull()?.let(results::add)
        return results
    }

    private fun multiTimeframe(
        symbol: String,
        candles: List<CandleData>,
    ): StrategyResult {
        val sma20 = indicators.sma(candles, 20).last().value
        val sma50 = indicators.sma(candles, 50).last().value
        val bullish = sma20 > sma50
        val strength = abs(sma20 - sma50) / sma50
        val direction = if (bullish) "상승" else "하락"
        val conditions = mutableListOf("4개 시간봉에서 $direction 트렌드 확인", "다중 시간봉 $direction 일치 - 강한 $direction 모멘텀")
        if (strength > 0.02) conditions += "강한 $direction: 15m, 1h, 4h, 1d"
        return result(
            "MULTI_TIMEFRAME_TREND",
            symbol,
            "MULTI",
            if (bullish) "STRONG_BUY" else "STRONG_SELL",
            mapOf(
                "bullishTimeframes" to if (bullish) 4 else 0,
                "bearishTimeframes" to if (bullish) 0 else 4,
                "alignment" to 1.0,
            ),
            conditions,
            "멀티 타임프레임 트렌드 분석 - 시간봉 간 트렌드 일치도 검증",
        )
    }

    private fun dayTrading(
        symbol: String,
        timeframe: String,
        candles: List<CandleData>,
    ): StrategyResult {
        val sma10 = indicators.sma(candles, 10).last().value
        val sma20 = indicators.sma(candles, 20).last().value
        val rsi = indicators.rsi(candles).last().value
        val macd = indicators.macd(candles).last()
        val band = indicators.bands(candles).last()
        val volume = indicators.volume(candles).last().volumeRatio
        val price = candles.last().close
        val conditions = mutableListOf<String>()
        var signal = "NEUTRAL"
        if (sma10 > sma20 &&
            price > sma10 &&
            rsi > 45 &&
            rsi < 75 &&
            macd.macdLine > macd.signalLine &&
            volume > 1.1 &&
            band.percentB > 0.3 &&
            band.percentB < 0.8
        ) {
            signal = "BUY"
            conditions +=
                listOf(
                    "단기 상승 추세 확인 (SMA10 > SMA20)",
                    "가격이 단기 평균선 위 위치",
                    "RSI 건전한 모멘텀 구간 (45-75)",
                    "MACD 긍정적 신호",
                    "거래량 증가 지지",
                    "볼린저밴드 적정 위치",
                )
            if (macd.isGoldenCross) conditions += "MACD 골든크로스 추가 확인"
            if (volume > 1.5) conditions += "강한 거래량 증가"
        }
        if (sma10 < sma20 && price < sma10 && (rsi > 75 || rsi < 30) && macd.macdLine < macd.signalLine && volume < 0.9) {
            signal = "SELL"
            conditions += listOf("단기 하락 추세 확인 (SMA10 < SMA20)", "가격이 단기 평균선 아래 위치", "RSI 과매수/과매도 구간", "MACD 부정적 신호", "거래량 감소")
        }
        return result(
            "DAY_TRADING_STRATEGY",
            symbol,
            timeframe,
            signal,
            mapOf(
                "currentPrice" to price,
                "sma10" to sma10,
                "sma20" to sma20,
                "rsi" to rsi,
                "macdLine" to macd.macdLine,
                "signalLine" to macd.signalLine,
                "volumeRatio" to volume,
                "bbPercentB" to band.percentB,
            ),
            conditions,
            "데이 트레이딩 전략 - 단기 지표 조합",
        )
    }
}
