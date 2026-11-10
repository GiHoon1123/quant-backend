package io.github.gihoon1123.quant

import org.springframework.stereotype.Service
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

data class CandleData(
    val openTime: Long,
    val closeTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val quoteVolume: Double = 0.0,
    val trades: Int = 0,
    val takerBuyBaseVolume: Double = 0.0,
    val takerBuyQuoteVolume: Double = 0.0,
)

data class Average(
    val timestamp: Long,
    val value: Double,
    val type: String,
    val period: Int,
)

data class Vwap(
    val timestamp: Long,
    val value: Double,
)

data class Rsi(
    val timestamp: Long,
    val value: Double,
    val isOverbought: Boolean,
    val isOversold: Boolean,
)

data class Macd(
    val timestamp: Long,
    val value: Double,
    val macdLine: Double,
    val signalLine: Double,
    val histogram: Double,
    val isGoldenCross: Boolean,
    val isDeadCross: Boolean,
)

data class Bands(
    val timestamp: Long,
    val value: Double,
    val middle: Double,
    val upper: Double,
    val lower: Double,
    val percentB: Double,
    val bandwidth: Double,
)

data class VolumeAnalysis(
    val timestamp: Long,
    val value: Double,
    val currentVolume: Double,
    val volumeMA: Double,
    val volumeRatio: Double,
    val isVolumeSurge: Boolean,
    val obv: Double,
)

data class Threshold(
    val timestamp: Long,
    val price: Double,
    val average: Double,
    val type: String,
    val signal: String?,
)

@Service
class Indicators {
    private fun requireData(
        candles: List<CandleData>,
        period: Int,
        label: String,
    ) {
        require(period > 0) { "period must be positive" }
        require(candles.size >= period) { "$label 계산을 위한 데이터가 부족합니다. 필요: ${period}개, 현재: ${candles.size}개" }
    }

    fun sma(
        candles: List<CandleData>,
        period: Int,
    ): List<Average> {
        requireData(candles, period, "SMA")
        return (period - 1 until candles.size).map { i ->
            Average(
                candles[i].closeTime,
                (i - period + 1..i).sumOf { candles[it].close } / period,
                "SMA",
                period,
            )
        }
    }

    fun ema(
        candles: List<CandleData>,
        period: Int,
    ): List<Average> {
        requireData(candles, period, "EMA")
        val multiplier = 2.0 / (period + 1)
        var value = candles.take(period).sumOf { it.close } / period
        val result = mutableListOf(Average(candles[period - 1].closeTime, value, "EMA", period))
        for (i in period until candles.size) {
            value = candles[i].close * multiplier + value * (1 - multiplier)
            result.add(Average(candles[i].closeTime, value, "EMA", period))
        }
        return result
    }

    fun vwap(candles: List<CandleData>): List<Vwap> {
        var volume = 0.0
        var amount = 0.0
        return candles.map {
            volume += it.volume
            amount += (it.high + it.low + it.close) / 3 * it.volume
            Vwap(it.closeTime, if (volume == 0.0) 0.0 else amount / volume)
        }
    }

    fun rsi(
        candles: List<CandleData>,
        period: Int = 14,
    ): List<Rsi> {
        requireData(candles, period + 1, "RSI")
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val change = candles[i].close - candles[i - 1].close
            if (change > 0) gain += change else loss += abs(change)
        }
        gain /= period
        loss /= period

        fun result(i: Int): Rsi {
            // Retain the original avgLoss == 0 rule, which gives 99.0099 rather than 100.
            val rs = if (loss == 0.0) 100.0 else gain / loss
            val value = 100 - 100 / (1 + rs)
            return Rsi(candles[i].closeTime, value, value >= 70, value <= 30)
        }
        val results = mutableListOf(result(period))
        for (i in period + 1 until candles.size) {
            val change = candles[i].close - candles[i - 1].close
            gain = (gain * (period - 1) + max(change, 0.0)) / period
            loss = (loss * (period - 1) + max(-change, 0.0)) / period
            results.add(result(i))
        }
        return results
    }

    fun macd(
        candles: List<CandleData>,
        fast: Int = 12,
        slow: Int = 26,
        signalPeriod: Int = 9,
    ): List<Macd> {
        requireData(candles, max(slow, fast) + signalPeriod, "MACD")
        require(fast <= slow && signalPeriod > 0)
        val fastValues = ema(candles, fast).drop(slow - fast)
        val slowValues = ema(candles, slow)
        val values = fastValues.zip(slowValues).map { (f, s) -> f.timestamp to f.value - s.value }
        var signal = values.take(signalPeriod).sumOf { it.second } / signalPeriod
        val multiplier = 2.0 / (signalPeriod + 1)
        return (signalPeriod - 1 until values.size).map { i ->
            if (i >= signalPeriod) signal = values[i].second * multiplier + signal * (1 - multiplier)
            val value = values[i].second
            Macd(values[i].first, value, value, signal, value - signal, value > signal, value < signal)
        }
    }

    fun bands(
        candles: List<CandleData>,
        period: Int = 20,
        deviations: Double = 2.0,
    ): List<Bands> {
        requireData(candles, period, "볼린저밴드")
        return (period - 1 until candles.size).map { i ->
            val window = candles.subList(i - period + 1, i + 1)
            val middle = window.sumOf { it.close } / period
            val deviation = sqrt(window.sumOf { (it.close - middle).pow(2) } / period)
            val upper = middle + deviations * deviation
            val lower = middle - deviations * deviation
            Bands(
                candles[i].closeTime,
                middle,
                middle,
                upper,
                lower,
                (candles[i].close - lower) / (upper - lower),
                (upper - lower) / middle,
            )
        }
    }

    fun volume(
        candles: List<CandleData>,
        period: Int = 20,
    ): List<VolumeAnalysis> {
        requireData(candles, period, "거래량 분석")
        var obv = 0.0
        return (period - 1 until candles.size).map { i ->
            val average = (i - period + 1..i).sumOf { candles[it].volume } / period
            val ratio = candles[i].volume / average
            if (i > 0) {
                if (candles[i].close > candles[i - 1].close) obv += candles[i].volume
                if (candles[i].close < candles[i - 1].close) obv -= candles[i].volume
            }
            VolumeAnalysis(candles[i].closeTime, ratio, candles[i].volume, average, ratio, ratio >= 2, obv)
        }
    }

    fun atr(
        candles: List<CandleData>,
        period: Int = 14,
    ): Double {
        requireData(candles, period + 1, "ATR")
        return (1 until candles.size)
            .map { i ->
                maxOf(
                    candles[i].high - candles[i].low,
                    abs(candles[i].high - candles[i - 1].close),
                    abs(
                        candles[i].low - candles[i - 1].close,
                    ),
                )
            }.takeLast(period)
            .average()
    }

    fun threshold(
        candles: List<CandleData>,
        averages: List<Vwap>,
        type: String,
    ): List<Threshold> =
        (1 until averages.size).map { i ->
            val offset = candles.size - averages.size
            val previous = candles[i - 1 + offset].close
            val current = candles[i + offset].close
            val signal =
                when {
                    previous < averages[i - 1].value && current > averages[i].value -> "UP_BREAK"
                    previous > averages[i - 1].value && current < averages[i].value -> "DOWN_BREAK"
                    else -> null
                }
            Threshold(averages[i].timestamp, current, averages[i].value, type, signal)
        }
}
