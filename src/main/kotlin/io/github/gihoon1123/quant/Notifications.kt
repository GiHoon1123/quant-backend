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
                    "message" to text,
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

    @Synchronized
    fun statistics(): Map<String, Any> =
        mapOf(
            "totalSent" to sent.size,
            "recentMessages" to sent.values.toList().takeLast(10),
            "channelStats" to if (sent.isEmpty()) emptyMap<String, Int>() else mapOf("TELEGRAM" to sent.size),
        )
}
