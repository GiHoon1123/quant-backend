package io.github.gihoon1123.quant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.TimeZone

class TelegramReportTest {
    @Test
    fun `objective report matches original Telegram client for three signals`() {
        val timezone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            val fixtures = JsonMapper.builder().build().readTree(javaClass.getResourceAsStream("/notification-reference.json"))
            for (index in 0 until fixtures.size()) {
                val fixture = fixtures[index]
                val result = fixture["result"]
                val indicators = result["indicators"]
                val keys =
                    listOf(
                        "SMA5",
                        "SMA20",
                        "SMA50",
                        "SMA200",
                        "EMA12",
                        "EMA26",
                        "VWAP",
                        "RSI",
                        "MACD",
                        "MACDSignal",
                        "MACDHist",
                        "Volume",
                        "AvgVolume",
                        "VolumeRatio",
                        "OBV",
                        "prevPrice",
                        "exchangeRate",
                    )
                val output =
                    TelegramReport().objective(
                        fixture["symbol"].asString(),
                        result["signal"].asString(),
                        keys.associateWith {
                            indicators[it].asDouble()
                        },
                        result["price"].asDouble(),
                        Instant.parse(result["timestamp"].asString()),
                    )
                assertEquals(fixture["expected"].asString(), output)
            }
        } finally {
            TimeZone.setDefault(timezone)
        }
    }
}
