package io.github.gihoon1123.quant

import org.springframework.stereotype.Component
import java.text.NumberFormat
import java.time.Clock
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

@Component
class ComprehensiveReport(
    private val indicators: Indicators,
    private val clock: Clock,
) {
    private fun fixed(
        value: Double,
        digits: Int,
    ) = if (value.isNaN()) "NaN" else String.format(Locale.US, "%.${digits}f", value)

    private fun local(value: Double) = NumberFormat.getNumberInstance(Locale.US).apply { maximumFractionDigits = 3 }.format(value)

    fun generate(
        candles: List<CandleData>,
        exchange: Double? = null,
    ): String {
        if (candles.isEmpty()) return "❌ 데이터가 부족하여 분석할 수 없습니다."
        return try {
            val current = candles.last().close
            val previous = candles.getOrNull(candles.lastIndex - 1)?.close ?: current
            val change = current - previous
            val sign = if (change >= 0) "+" else ""
            val arrow = if (change >= 0) "⬆️" else "⬇️"
            val now = clock.instant()
            // Source adds nine hours and then uses the process timezone for the heading.
            val heading = now.plusSeconds(9 * 3600).atZone(ZoneId.systemDefault())
            val hour = if (heading.hour > 12) heading.hour - 12 else heading.hour
            val time = "${if (heading.hour < 12) "오전" else "오후"} ${"%02d".format(hour)}:${"%02d".format(heading.minute)}"
            val sma = listOf(5, 20, 50, 200).associateWith { runCatching { indicators.sma(candles, it).last().value }.getOrNull() }
            val ema = listOf(12, 26).associateWith { runCatching { indicators.ema(candles, it).last().value }.getOrNull() }
            val vwap = indicators.vwap(candles).lastOrNull()?.value
            val rsi = indicators.rsi(candles).lastOrNull()
            val macd = indicators.macd(candles).lastOrNull()
            val bands = indicators.bands(candles).lastOrNull()
            val volume = indicators.volume(candles).lastOrNull()
            buildString {
                append("📌 [BTCUSDT] 비트코인 (메이저코인)\n\n🔔 비트코인 15분 분석 리포트 ($time)\n\n💰 가격 정보\nBTC/USD: $${local(current)}\n")
                append(
                    if (exchange != null &&
                        exchange > 0
                    ) {
                        "원화: ₩${local(current * exchange)} (환율: $1 = ₩${local(exchange)})\n"
                    } else {
                        "원화: 환율 조회 불가로 표시할 수 없습니다\n"
                    },
                )
                val changeText =
                    if (change ==
                        0.0
                    ) {
                        "0.00% ($0.00)"
                    } else {
                        "$sign${fixed(change / previous * 100, 2)}% ($sign$${fixed(abs(change), 2)})"
                    }
                append("15분 변화: $changeText $arrow\n\n📈 이동평균선 (현재가 대비)\n")

                fun moving(
                    name: String,
                    value: Double?,
                ) {
                    if (value == null) return
                    val percentage = if (value > 0) (current - value) / value * 100 else 0.0
                    append(
                        "• $name: $${local(
                            value,
                        )} (${if (percentage >= 0) "+" else ""}${fixed(percentage, 2)}% ${if (percentage >= 0) "⬆️" else "⬇️"})\n",
                    )
                }
                sma.forEach { (period, value) -> moving("SMA$period", value) }
                ema.forEach { (period, value) -> moving("EMA$period", value) }
                moving("VWAP", vwap)
                if (vwap != null) append("\n")
                append("📊 기술 지표\n")
                if (rsi != null) {
                    val status =
                        if (rsi.isOverbought) {
                            "⚠️ 과매수 근접, 70까지 ${fixed(70 - rsi.value, 1)}"
                        } else if (rsi.isOversold) {
                            "⚠️ 과매도 근접, 30까지 ${fixed(
                                rsi.value - 30,
                                1,
                            )}"
                        } else {
                            "중립"
                        }
                    append("• RSI(14): ${fixed(rsi.value, 1)} ($status)\n")
                }
                if (macd !=
                    null
                ) {
                    append(
                        "• MACD: ${fixed(
                            macd.macdLine,
                            1,
                        )} / Signal: ${fixed(
                            macd.signalLine,
                            1,
                        )} / Hist: ${fixed(macd.histogram, 1)}\n→ ${if (macd.isGoldenCross) "📈 골든크로스 (강세)" else "📉 데드크로스 (약세)"}\n\n",
                    )
                }
                if (bands !=
                    null
                ) {
                    append(
                        "🎯 볼린저 밴드\n• 상단: $${local(
                            bands.upper,
                        )} (+${fixed(
                            (bands.upper - current) / current * 100,
                            2,
                        )}% ⬆️)\n• 중심: $${local(bands.middle)} (+0.00% ⬆️)\n• 하단: $${local(bands.lower)} (-${fixed(
                            (current - bands.lower) / current * 100,
                            2,
                        )}% ⬇️)\n• 현재 위치: ${fixed(bands.percentB * 100, 0)}% (중간 위치)\n\n",
                    )
                }
                if (volume !=
                    null
                ) {
                    append(
                        "📊 거래량 분석\n• 현재: ${fixed(
                            volume.currentVolume,
                            2,
                        )} BTC\n• 평균 대비: ${if (volume.volumeRatio >= 1) "+" else ""}${fixed(
                            volume.volumeRatio * 100,
                            0,
                        )}% 📊\n• OBV: ${if (volume.obv > 0) "+" else ""}${fixed(
                            volume.obv,
                            0,
                        )} (${if (volume.obv > 0) "상승" else "하락"} 지속)\n\n",
                    )
                }
                val short =
                    if (rsi ==
                        null
                    ) {
                        "데이터 부족"
                    } else if (rsi.isOverbought) {
                        "중립 (RSI 과매수 주의)"
                    } else if (rsi.isOversold) {
                        "강세 (RSI 과매도 반등)"
                    } else {
                        "중립"
                    }
                val medium =
                    if (macd == null) {
                        "데이터 부족"
                    } else if (macd.isGoldenCross) {
                        "강세 (MACD 골든크로스)"
                    } else {
                        "약세 (MACD 데드크로스)"
                    }
                val long = sma[200]?.let { if (current > it) "상승 (200일선 상회)" else "하락 (200일선 하회)" } ?: "데이터 부족"
                val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)
                append(
                    "💡 종합 판단\n단기: $short\n중기: $medium\n장기: $long\n\n🕒 분석 시점: ${formatter.format(now)} UTC (${formatter.format(
                        now.plusSeconds(
                            9 * 3600,
                        ),
                    )} KST)",
                )
            }
        } catch (error: Exception) {
            "❌ 종합 리포트 생성 중 오류가 발생했습니다."
        }
    }
}
