package io.github.gihoon1123.quant

import org.springframework.stereotype.Component
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

@Component
class TelegramReport {
    private val names =
        mapOf(
            "BTCUSDT" to "비트코인",
            "ETHUSDT" to "이더리움",
            "ADAUSDT" to "에이다",
            "SOLUSDT" to "솔라나",
            "DOGEUSDT" to "도지코인",
            "XRPUSDT" to "리플",
            "DOTUSDT" to "폴카닷",
            "AVAXUSDT" to "아발란체",
            "MATICUSDT" to "폴리곤",
            "LINKUSDT" to "체인링크",
        )
    private val categories =
        mapOf(
            "BTCUSDT" to "메이저코인",
            "ETHUSDT" to "메이저코인",
            "ADAUSDT" to "알트코인",
            "SOLUSDT" to "알트코인",
            "DOGEUSDT" to "밈코인",
            "XRPUSDT" to "결제코인",
            "DOTUSDT" to "플랫폼코인",
            "AVAXUSDT" to "플랫폼코인",
            "MATICUSDT" to "레이어2",
            "LINKUSDT" to "오라클",
        )

    private fun fixed(
        value: Double,
        digits: Int,
    ) = if (value.isNaN()) "NaN" else String.format(Locale.US, "%.${digits}f", value)

    private fun numeric(value: Any?): Double {
        val number =
            value
                ?.toString()
                ?.let {
                    Regex(
                        "^[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?",
                    ).find(it)?.value
                }?.toDoubleOrNull()
                ?: return Double.NaN
        return fixed(number, 2).toDoubleOrNull() ?: Double.NaN
    }

    private fun fallback(
        value: Double,
        fallback: Double,
    ) = if (value.isNaN() || value == 0.0) fallback else value

    private fun localized(
        value: Double,
        digits: Int = 3,
    ) = NumberFormat
        .getNumberInstance(Locale.US)
        .apply {
            maximumFractionDigits = digits
        }.format(value)

    private fun price(value: Double) =
        if (value.isNaN()) {
            "N/A"
        } else {
            "$${NumberFormat.getNumberInstance(Locale.US).apply {
                minimumFractionDigits = 2
                maximumFractionDigits = 2
            }.format(value)}"
        }

    private fun volume(value: Double) =
        when {
            value >= 1000000 -> "${fixed(value / 1000000, 2)}M"
            value >= 1000 -> "${fixed(value / 1000, 2)}K"
            else -> fixed(value, 2)
        }

    private fun time(
        value: Instant,
        zone: ZoneId,
    ) = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone).format(value)

    fun objective(
        symbol: String,
        signal: String,
        indicators: Map<String, Any?>,
        rawPrice: Double,
        timestamp: Instant,
    ): String {
        val current = numeric(rawPrice)
        val previous = fallback((indicators["prevPrice"] as? Number)?.toDouble() ?: Double.NaN, current)
        val change = current - previous
        val sign = if (change >= 0) "+" else ""
        val arrow = if (change >= 0) "⬆️" else "⬇️"

        fun value(
            key: String,
            fallback: Double = Double.NaN,
        ) = fallback(numeric(indicators[key]), fallback)

        fun percent(value: Double): String {
            if (value.isNaN() || value == 0.0) return "N/A"
            val percentage = (current - value) / value * 100
            return "${if (percentage >= 0) "+" else ""}${fixed(percentage, 2)}% ${if (percentage >= 0) "⬆️" else "⬇️"}"
        }
        val rsi = value("RSI", 50.0)
        val rsiStatus =
            when {
                rsi >= 70 -> "⚠️ 과매수 근접"
                rsi <= 30 -> "🟢 과매도 구간"
                rsi >= 60 -> "📈 강세 구간"
                rsi <= 40 -> "📉 약세 구간"
                else -> "📊 중립 구간"
            }
        val rsiWarning =
            when {
                rsi >= 70 -> ", 70까지 ${fixed(70 - rsi, 1)}"
                rsi <= 30 -> ", 30까지 ${fixed(rsi - 30, 1)}"
                else -> ""
            }
        val macd = value("MACD", 0.0)
        val macdSignal = value("MACDSignal", 0.0)
        val macdHist = value("MACDHist", 0.0)
        val upper = value("BBUpper", current * 1.02)
        val middle = value("BBMiddle", current)
        val lower = value("BBLower", current * 0.98)
        val position = (current - lower) / (upper - lower) * 100
        val bbStatus =
            when {
                position >= 80 -> "상단 근접"
                position <= 20 -> "하단 근접"
                else -> "중간 위치"
            }
        val currentVolume = value("Volume", 0.0)
        val averageVolume = value("AvgVolume", 1.0)
        val ratio = currentVolume / averageVolume
        val obv = value("OBV", 0.0)
        val exchange = (indicators["exchangeRate"] as? Number)?.toDouble()
        val conversion =
            if (exchange != null &&
                exchange > 0
            ) {
                "원화: ₩${localized((current * exchange).roundToLong().toDouble())} (환율: $1 = ₩${localized(exchange)})\n"
            } else {
                "원화: 환율 조회 불가로 표시할 수 없습니다\n"
            }
        val name = names[symbol] ?: symbol
        val category = categories[symbol] ?: "기타"
        val shortTerm =
            when {
                rsi >= 70 -> "중립 (RSI 과매수 주의)"
                signal == "BUY" -> "강세"
                signal == "SELL" -> "약세"
                else -> "중립"
            }
        val mediumTerm = if (macd > macdSignal) "강세 (MACD 골든크로스)" else "약세 (MACD 데드크로스)"
        val longTerm = if (current > value("SMA200", current)) "상승 (200일선 상회)" else "하락 (200일선 하회)"
        val clock = DateTimeFormatter.ofPattern("a hh:mm", Locale.KOREAN).withZone(ZoneId.systemDefault()).format(timestamp)
        val moving =
            listOf("SMA5", "SMA20", "SMA50", "SMA200", "EMA12", "EMA26", "VWAP").joinToString("\n") { key ->
                val number =
                    value(
                        key,
                        if (key in
                            setOf("SMA5", "SMA20")
                        ) {
                            Double.NaN
                        } else {
                            current
                        },
                    )
                ; "• $key: $${price(number)} (${percent(number)})"
            }
        return "📌 <b>[$symbol] $name ($category)</b>\n\n" +
            "🔔 <b>$name 15분 분석 리포트</b> ($clock)\n\n💰 <b>가격 정보</b>\nBTC/USD: ${price(current)}\n" + conversion +
            "15분 변화: $sign${fixed(
                change / previous * 100,
                2,
            )}% ($sign$${fixed(abs(change), 2)}) $arrow\n\n📈 <b>이동평균선 (현재가 대비)</b>\n$moving\n\n" +
            "📊 <b>기술 지표</b>\n• RSI(14): ${fixed(
                rsi,
                1,
            )} ($rsiStatus$rsiWarning)\n• MACD: $sign${fixed(
                macd,
                1,
            )} / Signal: $sign${fixed(
                macdSignal,
                1,
            )} / Hist: $sign${fixed(macdHist, 1)}\n→ ${if (macd > macdSignal) "📈 골든크로스 유지 (강세)" else "📉 데드크로스 (약세)"}\n\n" +
            "🎯 <b>볼린저 밴드</b>\n• 상단: $${price(
                upper,
            )} (${percent(
                upper,
            )})\n• 중심: $${price(
                middle,
            )} (${percent(middle)})\n• 하단: $${price(lower)} (${percent(lower)})\n• 현재 위치: ${fixed(position, 0)}% ($bbStatus)\n\n" +
            "📊 <b>거래량 분석</b>\n• 현재: ${volume(currentVolume)} BTC\n• 평균 대비: +${fixed((ratio - 1) * 100, 0)}% ${if (ratio >= 2) {
                "🔥"
            } else if (ratio >= 1.5) {
                "📈"
            } else {
                "📊"
            }}\n• OBV: $sign${localized(abs(obv))} (${if (obv > 0) "상승 지속" else "하락 지속"})\n\n" +
            "💡 <b>종합 판단</b>\n단기: $shortTerm\n중기: $mediumTerm\n장기: $longTerm\n\n🕒 분석 시점: ${time(
                timestamp,
                ZoneOffset.UTC,
            )} UTC (${time(timestamp, ZoneId.of("Asia/Seoul"))} KST)"
    }
}
