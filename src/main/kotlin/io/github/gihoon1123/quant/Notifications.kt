package io.github.gihoon1123.quant

import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.LinkedHashMap
import java.util.UUID

@Service
class Notifications(
    private val telegram: Telegram,
    private val report: TelegramReport,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    private val sent = LinkedHashMap<String, Map<String, Any?>>()

    @EventListener
    fun event(event: MarketEvent) {
        if (event.channel != "technical" || event.name !in setOf("analysis.completed", "technical.analysis.completed")) return
        analysis(event.data)
    }

    @Synchronized
    fun analysis(event: Map<String, Any?>) {
        val symbol = event["symbol"].toString()

        @Suppress("UNCHECKED_CAST")
        val analysis =
            event["analysisResult"] as? Map<String, Any?>
                ?: mapOf("signal" to (event["signal"] ?: "UNKNOWN"), "indicators" to (event["indicators"] ?: emptyMap<String, Any>()))
        val signal = analysis["signal"]?.toString() ?: "UNKNOWN"
        if (signal == "HOLD") return
        val analyzed =
            when (val value = event["analyzedAt"]) {
                is Instant -> value
                is String -> runCatching { Instant.parse(value) }.getOrDefault(clock.instant())
                else -> clock.instant()
            }
        val key = listOf(symbol, signal, "ANALYSIS_RESULT", analysis["timeframe"] ?: "", analyzed.toString().take(16)).joinToString("-")
        if (!sent.containsKey(key)) {
            @Suppress("UNCHECKED_CAST")
            val raw = analysis["indicators"] as? Map<String, Any?> ?: emptyMap()
            val keys =
                listOf("SMA5", "SMA20", "SMA50", "SMA200", "EMA12", "EMA26", "VWAP", "RSI", "MACD", "Volume", "AvgVolume", "VolumeRatio")
            val indicators =
                keys.associateWith { raw[it]?.takeUnless { value -> value == 0.0 || value == 0 || value == "" } ?: "N/A" } +
                    mapOf("exchangeRate" to raw["exchangeRate"])
            val price =
                (analysis["currentPrice"] as? Number)?.toDouble()?.takeIf { it != 0.0 } ?: (analysis["price"] as? Number)?.toDouble() ?: 0.0
            val text = report.objective(symbol, analysis["overallSignal"]?.toString() ?: signal, indicators, price, analyzed)
            telegram.text(text)
            sent[key] =
                mapOf(
                    "id" to UUID.randomUUID().toString(),
                    "type" to "ANALYSIS_RESULT",
                    "channel" to "TELEGRAM",
                    "priority" to
                        if (signal in
                            setOf("STRONG_BUY", "STRONG_SELL")
                        ) {
                            "HIGH"
                        } else {
                            "MEDIUM"
                        },
                    "title" to "$symbol $signal 신호",
                    "message" to internalMessage(symbol, analysis),
                    "symbol" to symbol,
                    "data" to mapOf("analysisResult" to analysis, "analyzedAt" to analyzed),
                    "createdAt" to clock.instant(),
                    "status" to "SENT",
                    "sentAt" to clock.instant(),
                )
        }
        events.publishEvent(
            MarketEvent(
                "notification.analysis.completed",
                mapOf(
                    "eventId" to UUID.randomUUID().toString(),
                    "symbol" to symbol,
                    "signal" to signal,
                    "analyzedAt" to analyzed,
                    "service" to "NotificationService",
                    "timestamp" to clock.instant(),
                ),
            ),
        )
    }

    fun internalMessage(
        symbol: String,
        analysis: Map<String, Any?>,
    ): String {
        val signal = analysis["signal"].toString()
        val emoji =
            when (signal) {
                "BUY", "STRONG_BUY" -> "📈"
                "SELL", "STRONG_SELL" -> "📉"
                else -> "📊"
            }
        val timestamp = (analysis["timestamp"] as? Number)?.toLong()?.let(Instant::ofEpochMilli) ?: clock.instant()
        val formatter =
            java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss")
        val utc = formatter.withZone(java.time.ZoneOffset.UTC).format(timestamp)
        val kst = formatter.withZone(java.time.ZoneId.of("Asia/Seoul")).format(timestamp)
        val timeframe = analysis["timeframe"]?.toString()

        @Suppress("UNCHECKED_CAST")
        val indicators = analysis["indicators"] as? Map<String, Any?>
        val lines = mutableListOf<String>()

        fun truthy(value: Any?) = value != null && value != false && value != 0 && value != 0.0 && value != ""

        fun value(value: Any?): String =
            if (value is Double &&
                value.isFinite()
            ) {
                java.math.BigDecimal
                    .valueOf(value)
                    .stripTrailingZeros()
                    .toPlainString()
            } else {
                value.toString()
            }
        if (indicators != null) {
            for ((key, label) in listOf("sma" to "SMA", "rsi" to "RSI", "macd" to "MACD")) {
                if (truthy(indicators[key])) {
                    lines +=
                        "• $label: ${value(indicators[key])}"
                }
            }
            val suffix = if (timeframe != null) " ($timeframe 기준)" else ""

            fun volumes(
                volume: String,
                average: String,
                ratio: String,
            ) {
                if (indicators[volume] != null) lines += "• 거래량: ${value(indicators[volume])} BTC$suffix"
                if (indicators[average] != null) lines += "• 평균 거래량: ${value(indicators[average])} BTC$suffix"
                if (indicators[ratio] != null) lines += "• 거래량 비율: ${value(indicators[ratio])}배"
            }
            volumes("volume", "avgVolume", "volumeRatio")
            for ((key, label) in listOf(
                "SMA5" to "SMA5 (단기): $",
                "SMA10" to "SMA10 (중기): $",
                "SMA20" to "SMA20 (장기): $",
                "RSI" to "RSI: ",
                "MACD" to "MACD: ",
            )) {
                if (truthy(indicators[key])) lines += "• $label${value(indicators[key])}"
            }
            volumes("Volume", "AvgVolume", "VolumeRatio")
        }
        val body = if (indicators == null) "• 분석 중..." else lines.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: "• 데이터 수집 중..."
        return listOfNotNull(
            "$emoji **$symbol 기술적 분석 완료**",
            timeframe?.let {
                "⏱️ **시간대**: $it"
            },
            "🎯 **신호**: $signal",
            "📈 **주요 지표**:",
            body,
            "🕒 **분석 시점**: $utc UTC ($kst KST)",
        ).joinToString("\n")
    }

    @Synchronized
    fun statistics(): Map<String, Any> =
        mapOf(
            "totalSent" to sent.size,
            "recentMessages" to sent.values.toList().takeLast(10),
            "channelStats" to if (sent.isEmpty()) emptyMap<String, Int>() else mapOf("TELEGRAM" to sent.size),
        )
}
